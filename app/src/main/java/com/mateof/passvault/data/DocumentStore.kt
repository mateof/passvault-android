package com.mateof.passvault.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mateof.passvault.crypto.Primitives
import java.io.File

/**
 * The document a set of tickets came from, kept.
 *
 * Splitting a PDF and throwing the original away loses whatever was not a barcode: the page of
 * instructions, the map of the venue, the terms that say what happens if it rains. Those pages are
 * exactly the ones ingestion excludes, because it excludes pages with no barcode — so the rule that
 * makes the split correct is the same rule that would lose the rest of the document.
 *
 * Stored the way the `.tkpak` format stores blobs and the way the server does: ciphertext in a file,
 * outside the database. A row here is a name and a size; the bytes are on disk under the vault key.
 */
@Entity(tableName = "documents", indices = [Index("event_id")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "byte_count") val byteCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
) {
    override fun equals(other: Any?) = other is DocumentEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE event_id = :eventId ORDER BY created_at ASC")
    suspend fun forEvent(eventId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: String): DocumentEntity?

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * The bytes themselves.
 *
 * In the app's private storage and encrypted with the vault key, so a backup, a file manager or
 * another application reading the directory gets ciphertext. The associated data names the document
 * id, so a file cannot be swapped for another one and still open.
 */
class DocumentStore(
    private val context: Context,
    private val keys: DeviceKeys,
) {
    private fun directory(): File = File(context.filesDir, "documents").apply { mkdirs() }

    private fun fileFor(id: String) = File(directory(), "$id.bin")

    private fun aad(id: String) = "passvault/v1/document:$id"

    fun write(id: String, bytes: ByteArray) {
        val nonce = Primitives.randomNonce()
        fileFor(id).writeBytes(nonce + Primitives.seal(keys.vaultKey(), nonce, bytes, aad(id)))
    }

    /** Null rather than throwing when the file is gone: a missing document is not a broken wallet. */
    fun read(id: String): ByteArray? {
        val file = fileFor(id)
        if (!file.isFile) return null
        val stored = file.readBytes()
        if (stored.size <= Primitives.NONCE_BYTES) return null
        return runCatching {
            Primitives.open(
                keys.vaultKey(),
                stored.copyOfRange(0, Primitives.NONCE_BYTES),
                stored.copyOfRange(Primitives.NONCE_BYTES, stored.size),
                aad(id),
            )
        }.getOrNull()
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }
}
