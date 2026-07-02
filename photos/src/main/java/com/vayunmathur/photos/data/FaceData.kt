package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One unnamed person discovered by on-device face clustering. Faces whose
 * embeddings are close enough (cosine similarity, see
 * [com.vayunmathur.photos.util.FaceRecognizer]) share a [Person]. Nobody is
 * named — a cluster is just "the same face seen across photos".
 *
 * [centroid] is the running mean of every face embedding in the cluster, packed
 * as bytes; it is updated incrementally as new faces are added. The `rep*`
 * fields point at one representative face (a photo id plus its normalised
 * bounding box, 0..1) so the UI can show a cropped thumbnail without a name.
 */
@Entity
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val centroid: ByteArray,
    val faceCount: Int,
    val repPhotoId: Long,
    val repLeft: Float,
    val repTop: Float,
    val repRight: Float,
    val repBottom: Float,
)

/**
 * A face detected in a library photo. [clusterId] is the [Person] it was
 * grouped into. [embedding] is kept so faces can be re-clustered later without
 * re-scanning the original photo.
 */
@Entity(indices = [Index(value = ["photoId"]), Index(value = ["clusterId"])])
data class PhotoFace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val clusterId: Long,
    val embedding: ByteArray,
)

@Dao
interface FaceDao {
    @Insert
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Query("SELECT * FROM Person")
    suspend fun getPersons(): List<Person>

    /** All clusters, for the People view. */
    @Query("SELECT * FROM Person")
    fun personsFlow(): Flow<List<Person>>

    @Query("DELETE FROM Person")
    suspend fun clearPersons()

    @Query("DELETE FROM Person WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Insert
    suspend fun insertPhotoFaces(faces: List<PhotoFace>)

    @Query("DELETE FROM PhotoFace WHERE photoId IN (:photoIds)")
    suspend fun deletePhotoFacesByPhotoIds(photoIds: List<Long>)

    @Query("DELETE FROM PhotoFace")
    suspend fun clearPhotoFaces()

    /** Move every face from one cluster to another (used by the merge pass). */
    @Query("UPDATE PhotoFace SET clusterId = :newId WHERE clusterId = :oldId")
    suspend fun reassignCluster(oldId: Long, newId: Long)

    /** Every detected face, for grouping photos by cluster in the UI. */
    @Query("SELECT * FROM PhotoFace")
    fun allFacesFlow(): Flow<List<PhotoFace>>

    @Query("SELECT DISTINCT photoId FROM PhotoFace WHERE clusterId = :clusterId")
    suspend fun photoIdsForCluster(clusterId: Long): List<Long>
}
