package com.vayunmathur.camera.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoProcessor {
    fun adjustSpeed(inputFile: File, outputFile: File, speedFactor: Float) {
        val retriever = MediaMetadataRetriever()
        val rotation = try {
            retriever.setDataSource(inputFile.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { retriever.release() }

        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.path)

        val muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        var videoTrackIndex = -1
        var muxerTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                muxerTrackIndex = muxer.addTrack(format)
                break
            }
        }

        if (videoTrackIndex < 0) {
            extractor.release()
            muxer.release()
            return
        }

        extractor.selectTrack(videoTrackIndex)
        muxer.start()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        // Collect all sample timestamps first for smooth interpolation
        val samples = mutableListOf<Triple<Long, Int, Int>>() // timestamp, size, flags
        val dataChunks = mutableListOf<ByteArray>()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val chunk = ByteArray(size)
            buffer.position(0)
            buffer.get(chunk, 0, size)
            dataChunks.add(chunk)
            samples.add(Triple(extractor.sampleTime, size, extractor.sampleFlags))
            extractor.advance()
        }

        // Write with evenly distributed timestamps to avoid jitter at boundaries
        val totalDuration = if (samples.isNotEmpty()) samples.last().first else 0L
        val outputDuration = (totalDuration / speedFactor).toLong()

        for (i in samples.indices) {
            val (originalPts, size, flags) = samples[i]
            val chunk = dataChunks[i]

            // Linearly remap timestamps for perfectly even spacing
            val normalizedPosition = if (totalDuration > 0) originalPts.toDouble() / totalDuration else 0.0
            val newPts = (normalizedPosition * outputDuration).toLong()

            buffer.clear()
            buffer.put(chunk)
            info.size = size
            info.flags = flags
            info.presentationTimeUs = newPts
            info.offset = 0

            muxer.writeSampleData(muxerTrackIndex, buffer, info)
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }
}
