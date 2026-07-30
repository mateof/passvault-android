package com.mateof.passvault.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The signed operation log, on disk.
 *
 * Append-only. Nothing here is ever updated in place except an operation's `state`, and that only
 * ever moves out of quarantine — an operation the device could not apply when it arrived, because
 * the device that signed it was unknown, becomes applicable once that device's key turns up.
 *
 * The body is ciphertext for the same reason ticket barcodes are: an operation body carries the
 * barcode itself. Everything the log has to sort, page or deduplicate on stays readable.
 */
@Entity(
    tableName = "operations",
    indices = [Index("event_id"), Index("received_at"), Index("state")],
)
data class OperationEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "actor_user_id") val actorUserId: String?,
    val lamport: Long,
    @ColumnInfo(name = "wall_clock") val wallClock: String,
    val type: String,
    @ColumnInfo(name = "body_cipher") val bodyCipher: ByteArray,
    val signature: String,
    /** APPLIED, QUARANTINED or REJECTED. */
    val state: String,
    val reason: String?,
    /**
     * Arrival order, which is what the sync cursor pages on.
     *
     * Deliberately not the logical order. Logical order decides outcomes; a cursor answers "what
     * have I already been given", and an operation with a low lamport can arrive late. Paging by
     * logical order would step over it and never hand it out.
     */
    @ColumnInfo(name = "received_at") val receivedAt: String,
) {
    override fun equals(other: Any?) = other is OperationEntity && operationId == other.operationId
    override fun hashCode() = operationId.hashCode()
}

/**
 * A device whose signature this one can check.
 *
 * Learned from a `device.register` operation or from a pairing handshake. Without the row, an
 * operation that device signed is retained in quarantine rather than dropped, because the honest
 * cause is almost always a peer whose key simply has not been exchanged yet.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "signing_public_key") val signingPublicKey: String,
    @ColumnInfo(name = "agreement_public_key") val agreementPublicKey: String?,
    @ColumnInfo(name = "user_id") val userId: String?,
    val name: String?,
    val status: String,
) {
    override fun equals(other: Any?) = other is DeviceEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

@Dao
interface OperationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(operation: OperationEntity): Long

    @Query("SELECT * FROM operations WHERE operation_id = :operationId")
    suspend fun byId(operationId: String): OperationEntity?

    /** Everything for an event in logical order, which is what a replay consumes. */
    @Query("SELECT * FROM operations WHERE event_id = :eventId AND state = 'APPLIED'")
    suspend fun appliedFor(eventId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE state = 'APPLIED'")
    suspend fun applied(): List<OperationEntity>

    @Query("SELECT DISTINCT event_id FROM operations")
    suspend fun eventIds(): List<String>

    /**
     * A page of operations the peer has not been given, in arrival order.
     *
     * Fixed-width instants, so a string comparison is a chronological one and the cursor needs no
     * date handling on either side.
     */
    @Query(
        "SELECT * FROM operations WHERE event_id = :eventId AND state = 'APPLIED' " +
            "AND received_at > :cursor ORDER BY received_at ASC, operation_id ASC LIMIT :limit",
    )
    suspend fun since(eventId: String, cursor: String, limit: Int): List<OperationEntity>

    @Query("SELECT MAX(lamport) FROM operations WHERE event_id = :eventId")
    suspend fun maxLamport(eventId: String): Long?

    @Query("SELECT * FROM operations WHERE state = 'QUARANTINED' AND device_id = :deviceId")
    suspend fun quarantinedFrom(deviceId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE state = 'QUARANTINED' AND event_id = :eventId")
    suspend fun quarantinedFor(eventId: String): List<OperationEntity>

    @Query("UPDATE operations SET state = :state, reason = :reason WHERE operation_id = :operationId")
    suspend fun setState(operationId: String, state: String, reason: String?)

    @Query("SELECT COUNT(*) FROM operations WHERE event_id = :eventId")
    suspend fun countFor(eventId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun device(deviceId: String): DeviceEntity?

    @Query("SELECT * FROM devices")
    suspend fun devices(): List<DeviceEntity>
}

/** The states an operation can be stored in, matching the server's. */
object OperationState {
    const val APPLIED = "APPLIED"
    const val QUARANTINED = "QUARANTINED"
    const val REJECTED = "REJECTED"
}

/** Why an operation is not applied. Reported to the user, so each one has to be explainable. */
object OperationReason {
    const val UNKNOWN_DEVICE = "unknown_device"
    const val UNKNOWN_TYPE = "unknown_type"
    const val BAD_SIGNATURE = "bad_signature"
    const val NOT_PERMITTED = "not_permitted"
    const val SCOPE_MISMATCH = "scope_mismatch"
    const val NOT_APPLICABLE = "not_applicable"
}
