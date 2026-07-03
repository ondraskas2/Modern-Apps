package com.vayunmathur.photos.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device semantic image search with **MobileCLIP** on ONNX Runtime
 * (`com.microsoft.onnxruntime`, MIT — no Google Play Services, no ML Kit, so the
 * code stays F-Droid clean). This is the same on-device approach Ente Photos
 * uses. Everything runs locally; the models ship as unrestricted assets.
 *
 * CLIP puts images and text in **one shared embedding space**: the image encoder
 * turns a photo into a vector, the text encoder turns a search phrase into a
 * vector, and their cosine similarity says how well the phrase describes the
 * photo — so "a dog on the beach" can find beach-dog photos that contain no text
 * at all (that is what OCR search can't do).
 *
 * Two encoders, run independently and both L2-normalised so cosine == dot:
 *  - IMAGE ([imageEmbedding]): the photo is resized (shortest side to
 *    [IMAGE_SIZE]) and centre-cropped to [IMAGE_SIZE]x[IMAGE_SIZE], pixels are
 *    rescaled to [0,1] (MobileCLIP does NOT subtract an ImageNet mean/std —
 *    see [preprocess]), packed NCHW, and run through [ASSET_VISION].
 *  - TEXT ([textEmbedding]): the query is tokenised by [ClipTokenizer] (CLIP
 *    byte-level BPE, 77-token context) and run through [ASSET_TEXT].
 *
 * ## Bundled model assets (in `photos/src/main/assets/clip/`)
 *
 * Sourced from the HuggingFace ONNX export `Xenova/mobileclip_s0` (a re-export of
 * Apple's `ml-mobileclip` MobileCLIP-S0), which ships ready-to-run ONNX encoders:
 *  - [ASSET_VISION] `clip/vision_model.onnx`  — image encoder.
 *      input  `pixel_values` FLOAT [1,3,256,256]; output `image_embeds` FLOAT [1,512].
 *  - [ASSET_TEXT]   `clip/text_model.onnx`    — text encoder.
 *      input  `input_ids` INT64 [1,77];         output `text_embeds`  FLOAT [1,512].
 *  - [ClipTokenizer.MERGES_ASSET] `clip/bpe_simple_vocab_16e6.txt` — CLIP BPE vocab.
 *
 * The files shipped here are the **fp32 vision encoder** (~45 MB) and the
 * **int8-quantized text encoder** (~43 MB). This split is deliberate: int8
 * quantization of the VISION encoder collapses the embedding space (all photos
 * look alike), whereas the int8 TEXT encoder keeps full ranking quality — so the
 * vision side stays fp32 for correctness while the (larger) text side is
 * quantized for size. To shave ~22 MB you can drop in `vision_model_fp16.onnx`
 * from the same repo (fp16 vision matches fp32 quality in testing); the fp16
 * TEXT export is NOT usable (it trips an ONNX Runtime layer-norm fusion). Input
 * size, [0,1] rescale and 512-d output are identical across variants, so no code
 * change is needed to swap.
 *
 * If ANY asset is missing or fails to load, the embedder is inert: [isAvailable]
 * returns false and semantic search is simply disabled (no crash). The
 * background [ClipWorker] and the search path both check this first.
 *
 * Low-power by design: single-threaded ONNX sessions, one image at a time,
 * inputs downscaled before inference. Sessions are created lazily and reused.
 */
object ClipEmbedder {
    const val ASSET_VISION = "clip/vision_model.onnx"
    const val ASSET_TEXT = "clip/text_model.onnx"

    /** Square RGB input side the MobileCLIP image encoder expects. */
    const val IMAGE_SIZE = 256

    /** Shared-space embedding dimension (MobileCLIP-S0 = 512). Also read from the model. */
    const val EMBEDDING_DIM = 512

    /**
     * Bump whenever the model or preprocessing changes. [ClipWorker] compares it
     * against a stored value and, on mismatch, clears every stored embedding and
     * re-embeds so photos are re-indexed with the new model.
     */
    const val EMBEDDER_VERSION = 1

    private const val TAG = "ClipEmbedder"

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()

    @Volatile private var visionSession: OrtSession? = null
    @Volatile private var textSession: OrtSession? = null
    @Volatile private var tokenizer: ClipTokenizer? = null
    @Volatile private var initTried = false
    @Volatile private var initOk = false

    /** True only if all model + vocab assets are present in the APK. */
    fun assetsPresent(context: Context): Boolean =
        assetExists(context, ASSET_VISION) &&
            assetExists(context, ASSET_TEXT) &&
            assetExists(context, ClipTokenizer.MERGES_ASSET)

    /** True if the encoders + tokenizer are loaded and ready. */
    fun isAvailable(context: Context): Boolean = ensureInit(context)

    private fun ensureInit(context: Context): Boolean {
        if (initOk) return true
        synchronized(lock) {
            if (initOk) return true
            if (initTried) return false
            initTried = true
            return try {
                val app = context.applicationContext
                val visionBytes = readAsset(app, ASSET_VISION) ?: run {
                    Log.w(TAG, "Missing $ASSET_VISION; semantic search disabled"); return false
                }
                val textBytes = readAsset(app, ASSET_TEXT) ?: run {
                    Log.w(TAG, "Missing $ASSET_TEXT; semantic search disabled"); return false
                }
                val tok = ClipTokenizer.load(app) ?: run {
                    Log.w(TAG, "Missing ${ClipTokenizer.MERGES_ASSET}; semantic search disabled"); return false
                }
                val opts = OrtSession.SessionOptions().apply {
                    // Single-threaded keeps sustained CPU/battery use low.
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                visionSession = env.createSession(visionBytes, opts)
                textSession = env.createSession(textBytes, opts)
                tokenizer = tok
                initOk = true
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialise MobileCLIP — drop $ASSET_VISION, $ASSET_TEXT and ${ClipTokenizer.MERGES_ASSET} into photos assets (see ClipEmbedder docs).", e)
                closeLocked()
                false
            }
        }
    }

    /**
     * Embed [bitmap] into an L2-normalised image vector, or null if the models
     * are unavailable or inference fails. The caller's bitmap is not recycled.
     */
    fun imageEmbedding(context: Context, bitmap: Bitmap): FloatArray? {
        if (!ensureInit(context)) return null
        val session = visionSession ?: return null
        val input = preprocess(bitmap) ?: return null
        return try {
            synchronized(lock) {
                val inputName = session.inputNames.iterator().next()
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
                ).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val out = result.get(0) as OnnxTensor
                        val vec = FloatArray(out.info.shape.last().toInt())
                        out.floatBuffer.get(vec)
                        l2Normalize(vec)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image embedding failed", e)
            null
        }
    }

    /**
     * Embed a search [text] into an L2-normalised vector in the SAME space as
     * [imageEmbedding], or null if unavailable. One fast text-encoder run.
     */
    fun textEmbedding(context: Context, text: String): FloatArray? {
        if (!ensureInit(context)) return null
        val session = textSession ?: return null
        val tok = tokenizer ?: return null
        val ids = tok.tokenize(text)
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        return try {
            synchronized(lock) {
                val inputName = session.inputNames.iterator().next()
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longIds),
                    longArrayOf(1, ids.size.toLong()),
                ).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val out = result.get(0) as OnnxTensor
                        val vec = FloatArray(out.info.shape.last().toInt())
                        out.floatBuffer.get(vec)
                        l2Normalize(vec)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text embedding failed", e)
            null
        }
    }

    /** Release the ONNX sessions (e.g. when a background batch is done). */
    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        try { visionSession?.close() } catch (_: Exception) {}
        try { textSession?.close() } catch (_: Exception) {}
        visionSession = null
        textSession = null
        tokenizer = null
        initOk = false
        initTried = false
    }

    /**
     * Resize [src] so its shortest side is [IMAGE_SIZE], centre-crop to a square,
     * and pack it as an NCHW float array with pixels rescaled to [0,1] (RGB).
     * Returns null on failure.
     */
    private fun preprocess(src: Bitmap): FloatArray? {
        return try {
            val safe = if (src.config == Bitmap.Config.HARDWARE || src.config == null) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else src

            val scale = IMAGE_SIZE.toFloat() / minOf(safe.width, safe.height)
            val sw = maxOf(IMAGE_SIZE, Math.round(safe.width * scale))
            val sh = maxOf(IMAGE_SIZE, Math.round(safe.height * scale))
            val scaled = Bitmap.createScaledBitmap(safe, sw, sh, true)
            val left = (sw - IMAGE_SIZE) / 2
            val top = (sh - IMAGE_SIZE) / 2
            val cropped = Bitmap.createBitmap(scaled, left, top, IMAGE_SIZE, IMAGE_SIZE)

            val px = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            cropped.getPixels(px, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            if (scaled != safe) scaled.recycle()
            if (cropped != scaled) cropped.recycle()
            if (safe != src) safe.recycle()

            val area = IMAGE_SIZE * IMAGE_SIZE
            val out = FloatArray(3 * area)
            for (i in 0 until area) {
                val p = px[i]
                out[i] = ((p shr 16) and 0xFF) / 255f            // R plane
                out[area + i] = ((p shr 8) and 0xFF) / 255f       // G plane
                out[2 * area + i] = (p and 0xFF) / 255f           // B plane
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Image preprocess failed", e)
            null
        }
    }

    // ---- Shared math + (de)serialisation helpers ----

    /** Cosine similarity of two vectors (unit vectors → just the dot product). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return -1f
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun l2Normalize(values: FloatArray): FloatArray {
        var norm = 0f
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return values
        return FloatArray(values.size) { values[it] / norm }
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }

    private fun assetExists(context: Context, name: String): Boolean = try {
        context.assets.open(name).close()
        true
    } catch (_: Exception) {
        false
    }

    private fun readAsset(context: Context, name: String): ByteArray? = try {
        context.assets.open(name).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
