package com.vayunmathur.photos.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.FaceDao
import com.vayunmathur.photos.data.PhotoOCR
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.VideoData
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo())
        val database = applicationContext.buildDatabase<PhotoDatabase>()
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        
        val triggeredUris = triggeredContentUris
        val lastGeneration = dataStore.getLong("last_photos_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        if (triggeredUris.isNotEmpty()) {
            syncPhotos(applicationContext, database, triggeredUris.toList())
        } else {
            syncPhotos(applicationContext, database, null, lastGeneration)
        }
        
        val photos = database.photoDao().getAll()
        setExifData(photos, database, applicationContext)
        
        if (dataStore.getBoolean("image_understanding_enabled", false)) {
            OCRWorker.enqueue(applicationContext)
        }

        // Face grouping is always on (no opt-in). The worker is inert if the
        // model asset is missing.
        FaceWorker.enqueue(applicationContext)
        
        dataStore.setLong("last_photos_generation", currentGeneration)
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 101,
        channelId = "sync_worker",
        channelName = "Photo Sync",
        title = "Syncing Photos",
        text = "Indexing photos and extracting text...",
    )

    companion object {
        const val WORK_NAME = "SyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(500, TimeUnit.MILLISECONDS)
                .setTriggerContentMaxDelay(2, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class OCRWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        if (!dataStore.getBoolean("image_understanding_enabled", false)) {
            return@withContext WorkResult.success()
        }
        ocrMutex.withLock {
            setForeground(createForegroundInfo())
            val database =
                applicationContext.buildDatabase<PhotoDatabase>()
            val photos = database.photoDao().getAll()
            runOCR(photos, database, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 102,
        channelId = "ocr_worker",
        channelName = "Photo Indexing",
        title = "Analyzing Photos",
        text = "Extracting scene information...",
    )

    companion object {
        private const val WORK_NAME = "OCRWorker"
        private val ocrMutex = Mutex() // Shared across all instances of OCRWorker


        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OCRWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

suspend fun syncPhotos(context: Context, database: PhotoDatabase, uris: List<Uri>? = null, lastGeneration: Long = 0L) {
    val photoDao = database.photoDao()
    // Single read of the local DB reused for both deletion detection and update diffing.
    val existing = photoDao.getAll()
    // 1. Get all IDs currently in MediaStore to detect deletions
    val allMediaStoreIds = mutableSetOf<Long>()
    fun collectIds(baseUri: Uri) {
        try {
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(baseUri, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    try {
                        allMediaStoreIds.add(cursor.getLong(idCol))
                    } catch (e: Exception) {
                        Log.e("SyncWorker", "Error reading ID from MediaStore cursor", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error querying MediaStore for IDs: $baseUri", e)
        }
    }
    collectIds(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    collectIds(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    // 2. Handle deletions
    val localIds = existing.map { it.id }.toSet()
    val toDelete = if (uris != null) {
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            photoDao.deleteByIds(chunk)
        }
    }

    // 3. Process additions/updates
    val selection = when {
        uris != null -> {
            val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (ids.isEmpty()) null else "_id IN (${ids.joinToString(",")})"
        }
        lastGeneration > 0 -> {
            "${MediaStore.MediaColumns.GENERATION_MODIFIED} > $lastGeneration"
        }
        else -> null
    }

    val existingPhotos = existing.associateBy { it.id }
    val newOrUpdatedPhotos = mutableListOf<Photo>()

    fun processCursor(cursor: android.database.Cursor, isVideo: Boolean) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val isTrashedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val durationColumn = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1

        val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        while (cursor.moveToNext()) {
            try {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateTaken = cursor.getLongOrNull(dateTakenColumn)
                val date = if (dateTaken != null && dateTaken > 0) dateTaken else (cursor.getLong(dateAddedColumn) * 1000)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val isTrashed = cursor.getInt(isTrashedColumn) == 1
                val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
                val videoData = if (isVideo) VideoData(cursor.getLong(durationColumn)) else null

                val existing = existingPhotos[id]
                if (existing == null || existing.date != date || existing.uri != contentUri || existing.videoData != videoData || existing.width != width || existing.height != height || existing.dateModified != dateModified || existing.isTrashed != isTrashed) {
                    newOrUpdatedPhotos += Photo(id, name, contentUri, date, width, height, dateModified, existing?.exifSet ?: false, existing?.lat, existing?.long, videoData, isTrashed, faceScanned = existing?.faceScanned ?: false)
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Error processing photo/video from cursor", e)
            }
        }
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.IS_TRASHED),
            bundle, null
        )?.use { processCursor(it, false) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for images", e)
    }

    try {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.IS_TRASHED),
            bundle, null
        )?.use { processCursor(it, true) }
    } catch (e: Exception) {
        Log.e("SyncWorker", "Error querying MediaStore for videos", e)
    }

    if (newOrUpdatedPhotos.isNotEmpty()) {
        photoDao.upsertAll(newOrUpdatedPhotos)
    }
}

private fun Context.syncForegroundInfo(
    notificationId: Int,
    channelId: String,
    channelName: String,
    title: String,
    text: String,
): ForegroundInfo {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    )
    val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()
    return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

suspend fun setExifData(photos: List<Photo>, database: PhotoDatabase, context: Context) = coroutineScope {
    val photoDao = database.photoDao()
    val ps = photos.filter { !it.exifSet }.sortedByDescending { it.date }
    ps.chunked(50).forEach { photosChunk ->
        val newPhotos = photosChunk.map { photo ->
            async(Dispatchers.IO) {
                try {
                    val (lat, long) = context.contentResolver.openInputStream(
                        MediaStore.setRequireOriginal(
                            photo.uri.toUri()
                        )
                    )?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val latLong = exif.latLong
                        val lat = latLong?.getOrNull(0)
                        val long = latLong?.getOrNull(1)
                        listOf(lat, long)
                    } ?: listOf(null, null)
                    photo.copy(exifSet = true, lat = lat, long = long)
                } catch (_: Exception) {
                    photo.copy(exifSet = true) // Mark as set even on error to avoid retry every time
                }
            }
        }.awaitAll()
        photoDao.upsertAll(newPhotos)
    }
}

suspend fun runOCR(photos: List<Photo>, database: PhotoDatabase, context: Context) = coroutineScope {
    val photoDao = database.photoDao()
    val dataStore = DataStoreUtils.getInstance(context)
    // Find photos that don't have OCR yet
    // Since it's FTS4, we might want to optimize this, but for now we'll just check existence
    // Actually, we can get all photoIds from PhotoOCR and filter
    val ocrIds = database.query(SimpleSQLiteQuery("SELECT rowid FROM PhotoOCR"), null).use { cursor ->
        val ids = mutableSetOf<Long>()
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(0))
        }
        ids
    }

    val ps = photos.filter { it.id !in ocrIds && it.videoData == null }.sortedByDescending { it.date }
    if (ps.isEmpty()) return@coroutineScope

    val ocrManager = OCRManager(context)

    // Check if model is available before processing
    if (!ocrManager.isAvailable()) {
        Log.w("SyncWorker", "OpenAssistant not installed, skipping OCR processing")
        return@coroutineScope
    }

    ps.forEach { photo ->
        ensureActive()
        if (!dataStore.getBoolean("image_understanding_enabled", false)) {
            return@coroutineScope
        }

        try {
            val result = ocrManager.runOCR(photo.uri.toUri())
            if (result != null) {
                val (ocrText, description) = result
                photoDao.upsertOCR(PhotoOCR(photo.id, ocrText, description))
                Log.i("SyncWorker", "OCR for ${photo.id} produced text: ${ocrText.take(50)}, description: ${description.take(50)}")
            } else {
                Log.w("SyncWorker", "OCR for ${photo.id} returned null, will retry later")
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error running OCR for photo ${photo.id}, will retry later", e)
        }

        // Throttle calls to the (shared, single-threaded) inference service.
        delay(OCR_INTER_ITEM_DELAY_MS)
    }
}

private const val OCR_INTER_ITEM_DELAY_MS = 30_000L

class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        faceMutex.withLock {
            setForeground(createForegroundInfo())
            val database = applicationContext.buildDatabase<PhotoDatabase>()
            runFaceIndexing(database, applicationContext)
            WorkResult.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo = applicationContext.syncForegroundInfo(
        notificationId = 103,
        channelId = "face_worker",
        channelName = "People Indexing",
        title = "Finding People",
        text = "Grouping photos of the same person on-device...",
    )

    companion object {
        const val WORK_NAME = "FaceWorker"
        private val faceMutex = Mutex()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FaceWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * Scan any not-yet-scanned library photos for faces, then group each detected
 * face into a [Person] cluster by cosine similarity of its embedding — all
 * on-device, unsupervised, and unnamed.
 *
 * Clustering is greedy and incremental: each face joins the nearest existing
 * cluster if similarity >= [FaceRecognizer.CLUSTER_THRESHOLD], otherwise it
 * starts a new cluster. Each cluster keeps a running-mean [Person.centroid] that
 * is updated as faces are added, so we never have to re-scan old photos.
 */
suspend fun runFaceIndexing(database: PhotoDatabase, context: Context) {
    val photoDao = database.photoDao()
    val faceDao = database.faceDao()
    val dataStore = DataStoreUtils.getInstance(context)

    // Feature is inert without the on-device models (see FaceRecognizer docs).
    // Return WITHOUT marking photos scanned so they get processed once the
    // model assets are present.
    if (!FaceRecognizer.modelsAvailable(context)) {
        Log.w("FaceWorker", "Face models missing; skipping face indexing")
        return
    }

    // If the embedder model/version changed, the old embeddings are
    // incompatible: drop all clusters + faces and re-scan every photo so they
    // get re-grouped with the new model. Photo rows themselves are untouched.
    val storedVersion = dataStore.getLong("face_embedder_version") ?: 0L
    if (storedVersion != FaceRecognizer.EMBEDDER_VERSION.toLong()) {
        faceDao.clearPersons()
        faceDao.clearPhotoFaces()
        photoDao.resetFaceScanned()
        dataStore.setLong("face_embedder_version", FaceRecognizer.EMBEDDER_VERSION.toLong())
    }

    // Load existing clusters into memory once; centroids are cached as floats and
    // updated in place so we avoid re-reading them for every face.
    val clusters = faceDao.getPersons()
        .map { Cluster(it, FaceRecognizer.bytesToFloats(it.centroid)) }
        .toMutableList()

    val photos = photoDao.getUnscannedForFaces().sortedByDescending { it.date }
    for (photo in photos) {
        try {
            val bitmap = loadBitmapForFaces(context, photo.uri.toUri())
            if (bitmap != null) {
                val faces = FaceRecognizer.detectAndEmbed(context, bitmap)
                bitmap.recycle()
                val rows = faces.map { face ->
                    val clusterId = assignToCluster(face, photo, clusters, faceDao)
                    PhotoFace(
                        photoId = photo.id,
                        clusterId = clusterId,
                        embedding = FaceRecognizer.floatsToBytes(face.embedding),
                    )
                }
                if (rows.isNotEmpty()) faceDao.insertPhotoFaces(rows)
            }
        } catch (e: Exception) {
            Log.e("FaceWorker", "Error scanning faces for photo ${photo.id}", e)
        }

        // Mark scanned regardless of outcome so we don't retry forever.
        photoDao.upsertAll(listOf(photo.copy(faceScanned = true)))
    }

    // Second pass: fold together clusters whose centroids ended up very close,
    // which trims duplicate person-groups created early in the scan.
    mergeSimilarClusters(faceDao)
}

/** A cluster held in memory during a scan: its [Person] row plus cached centroid. */
private class Cluster(var person: Person, var centroid: FloatArray)

/**
 * Put [face] in the nearest cluster above [FaceRecognizer.CLUSTER_THRESHOLD],
 * updating that cluster's running-mean centroid, or start a new cluster. Returns
 * the cluster (person) id the face was assigned to.
 */
private suspend fun assignToCluster(
    face: FaceRecognizer.DetectedFace,
    photo: Photo,
    clusters: MutableList<Cluster>,
    faceDao: FaceDao,
): Long {
    var best: Cluster? = null
    var bestSim = FaceRecognizer.CLUSTER_THRESHOLD
    for (cluster in clusters) {
        val sim = FaceRecognizer.similarity(face.embedding, cluster.centroid)
        if (sim >= bestSim) {
            bestSim = sim
            best = cluster
        }
    }

    if (best != null) {
        val n = best.person.faceCount
        val mean = FloatArray(best.centroid.size) { i ->
            (best.centroid[i] * n + face.embedding[i]) / (n + 1)
        }
        // Centroid is a running mean kept L2-normalised so cosine stays well-behaved.
        best.centroid = FaceRecognizer.l2Normalize(mean)
        best.person = best.person.copy(
            centroid = FaceRecognizer.floatsToBytes(best.centroid),
            faceCount = n + 1,
        )
        faceDao.updatePerson(best.person)
        return best.person.id
    }

    val person = Person(
        centroid = FaceRecognizer.floatsToBytes(face.embedding),
        faceCount = 1,
        repPhotoId = photo.id,
        repLeft = face.left,
        repTop = face.top,
        repRight = face.right,
        repBottom = face.bottom,
    )
    val id = faceDao.insertPerson(person)
    clusters += Cluster(person.copy(id = id), face.embedding.copyOf())
    return id
}

/**
 * Greedily merge clusters whose centroids are within [FaceRecognizer.MERGE_THRESHOLD]
 * cosine of each other. Faces from the merged cluster are moved over and the
 * centroid becomes the face-count-weighted, L2-normalised mean. O(n^2) over the
 * (small) number of person-clusters.
 */
private suspend fun mergeSimilarClusters(faceDao: FaceDao) {
    val persons = faceDao.getPersons().toMutableList()
    var i = 0
    while (i < persons.size) {
        var j = i + 1
        while (j < persons.size) {
            val a = persons[i]
            val b = persons[j]
            val sim = FaceRecognizer.similarity(
                FaceRecognizer.bytesToFloats(a.centroid),
                FaceRecognizer.bytesToFloats(b.centroid),
            )
            if (sim >= FaceRecognizer.MERGE_THRESHOLD) {
                val na = a.faceCount
                val nb = b.faceCount
                val ca = FaceRecognizer.bytesToFloats(a.centroid)
                val cb = FaceRecognizer.bytesToFloats(b.centroid)
                val mean = FloatArray(ca.size) { (ca[it] * na + cb[it] * nb) / (na + nb) }
                val merged = a.copy(
                    centroid = FaceRecognizer.floatsToBytes(FaceRecognizer.l2Normalize(mean)),
                    faceCount = na + nb,
                )
                faceDao.reassignCluster(b.id, a.id)
                faceDao.updatePerson(merged)
                faceDao.deletePerson(b.id)
                persons[i] = merged
                persons.removeAt(j)
            } else {
                j++
            }
        }
        i++
    }
}

private fun loadBitmapForFaces(context: Context, uri: Uri): Bitmap? {
    return try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val maxDim = maxOf(info.size.width, info.size.height)
            val target = 720
            if (maxDim > target) {
                val scale = target.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        Log.e("FaceWorker", "Failed to decode $uri for faces", e)
        null
    }
}
