package com.mateof.passvault.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Local storage.
 *
 * The column conventions match the server's, so a row means the same thing on both sides: instants
 * as fixed-width ISO-8601 text, booleans as integers, money as integer cents, and anything of value
 * as ciphertext in a `_cipher` column.
 *
 * What is encrypted follows the same rule as the server. Barcodes, labels, seats and venue names
 * are ciphertext; identifiers, states and timestamps stay readable so the database can still sort
 * and filter. Encrypting everything would make the wallet unusable and protect little — knowing a
 * ticket exists is not knowing what its barcode says.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name_cipher") val nameCipher: ByteArray,
    @ColumnInfo(name = "venue_cipher") val venueCipher: ByteArray?,
    @ColumnInfo(name = "starts_at") val startsAt: String?,
    @ColumnInfo(name = "default_assignment_mode") val defaultAssignmentMode: String,
    @ColumnInfo(name = "password_protected") val passwordProtected: Int,
    // The mark this event is recognised by, in the clear. A category and a colour are not the
    // ticket data — encrypting them would mean a wallet that cannot draw its own list until
    // every event is decrypted, which is the problem the mark exists to solve.
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "colour") val colour: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
) {
    override fun equals(other: Any?) = other is EventEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

@Entity(tableName = "tickets", indices = [Index("event_id"), Index("assignment_state")])
data class TicketEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "label_cipher") val labelCipher: ByteArray?,
    @ColumnInfo(name = "seat_cipher") val seatCipher: ByteArray?,
    @ColumnInfo(name = "barcode_format") val barcodeFormat: String?,
    @ColumnInfo(name = "barcode_cipher") val barcodeCipher: ByteArray?,
    @ColumnInfo(name = "assignment_mode") val assignmentMode: String,
    @ColumnInfo(name = "assignment_state") val assignmentState: String,
    @ColumnInfo(name = "holder_label_cipher") val holderLabelCipher: ByteArray?,
    @ColumnInfo(name = "payment_state") val paymentState: String?,
    @ColumnInfo(name = "amount_cents") val amountCents: Int?,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "exported_at") val exportedAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
) {
    override fun equals(other: Any?) = other is TicketEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

/** A ticket with the event fields the wallet shows, so the list is one query rather than N+1. */
data class TicketWithEvent(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label_cipher") val labelCipher: ByteArray?,
    @ColumnInfo(name = "seat_cipher") val seatCipher: ByteArray?,
    @ColumnInfo(name = "assignment_state") val assignmentState: String,
    @ColumnInfo(name = "payment_state") val paymentState: String?,
    @ColumnInfo(name = "amount_cents") val amountCents: Int?,
    @ColumnInfo(name = "currency") val currency: String?,
    @ColumnInfo(name = "holder_label_cipher") val holderLabelCipher: ByteArray?,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "name_cipher") val eventNameCipher: ByteArray,
    @ColumnInfo(name = "starts_at") val startsAt: String?,
)

/** An event as the list shows it: a name, a date and how many tickets are inside. */
data class EventWithCount(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name_cipher") val nameCipher: ByteArray,
    @ColumnInfo(name = "venue_cipher") val venueCipher: ByteArray?,
    @ColumnInfo(name = "starts_at") val startsAt: String?,
    @ColumnInfo(name = "icon") val icon: String?,
    @ColumnInfo(name = "colour") val colour: String?,
    @ColumnInfo(name = "ticket_count") val ticketCount: Int,
    @ColumnInfo(name = "provisional_count") val provisionalCount: Int,
)

@Dao
interface WalletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTickets(tickets: List<TicketEntity>)

    /**
     * The wallet list.
     *
     * A Flow, so a claim being confirmed updates the screen without anything asking for it.
     *
     * Ordered by the event start with nulls last: a ticket for tonight matters more than one with
     * no date, and SQLite sorts NULL first, which would put every undated ticket at the top.
     */
    @Query(
        "SELECT t.id, t.label_cipher, t.seat_cipher, t.assignment_state, t.payment_state, " +
            "t.amount_cents, t.currency, t.holder_label_cipher, " +
            "e.id AS event_id, e.name_cipher, e.starts_at " +
            "FROM tickets t JOIN events e ON e.id = t.event_id " +
            "ORDER BY CASE WHEN e.starts_at IS NULL THEN 1 ELSE 0 END, e.starts_at ASC, t.created_at ASC",
    )
    fun wallet(): Flow<List<TicketWithEvent>>

    /**
     * One ticket, with its barcode.
     *
     * Separate from the list query on purpose: the list never selects a barcode, so scrolling forty
     * rows neither decrypts forty payloads nor holds them all in memory.
     */
    @Query(
        "SELECT t.*, e.name_cipher AS event_name_cipher FROM tickets t " +
            "JOIN events e ON e.id = t.event_id WHERE t.id = :ticketId",
    )
    suspend fun ticket(ticketId: String): TicketWithBarcode?

    @Query("SELECT COUNT(*) FROM tickets")
    suspend fun ticketCount(): Int

    /**
     * The events, with how many tickets each holds.
     *
     * One query with a join rather than a list of events and a count per event, because the wallet
     * screen would otherwise issue one query per row and the number of rows is the number of
     * events somebody is going to.
     *
     * Ordered by when the event starts, undated last: a ticket for tonight matters more than one
     * with no date, and SQLite sorts NULL first, which would put every undated event at the top.
     */
    @Query("UPDATE events SET icon = :icon, colour = :colour WHERE id = :eventId")
    suspend fun setEventMark(eventId: String, icon: String?, colour: String?)

    @Query(
        "SELECT e.id, e.name_cipher, e.venue_cipher, e.starts_at, e.icon, e.colour, " +
            "COUNT(t.id) AS ticket_count, " +
            "SUM(CASE WHEN t.assignment_state = 'PROVISIONAL' THEN 1 ELSE 0 END) AS provisional_count " +
            "FROM events e LEFT JOIN tickets t ON t.event_id = e.id " +
            "GROUP BY e.id " +
            "ORDER BY CASE WHEN e.starts_at IS NULL THEN 1 ELSE 0 END, e.starts_at ASC, e.created_at DESC",
    )
    fun events(): Flow<List<EventWithCount>>

    /** The tickets of one event. */
    @Query(
        "SELECT t.id, t.label_cipher, t.seat_cipher, t.assignment_state, t.payment_state, " +
            "t.amount_cents, t.currency, t.holder_label_cipher, " +
            "e.id AS event_id, e.name_cipher, e.starts_at " +
            "FROM tickets t JOIN events e ON e.id = t.event_id " +
            "WHERE t.event_id = :eventId ORDER BY t.created_at ASC",
    )
    fun ticketsOf(eventId: String): Flow<List<TicketWithEvent>>

    @Query("DELETE FROM tickets WHERE id = :ticketId")
    suspend fun deleteTicket(ticketId: String)
}

data class TicketWithBarcode(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label_cipher") val labelCipher: ByteArray?,
    @ColumnInfo(name = "seat_cipher") val seatCipher: ByteArray?,
    @ColumnInfo(name = "barcode_format") val barcodeFormat: String?,
    @ColumnInfo(name = "barcode_cipher") val barcodeCipher: ByteArray?,
    @ColumnInfo(name = "assignment_state") val assignmentState: String,
    @ColumnInfo(name = "holder_label_cipher") val holderLabelCipher: ByteArray?,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "event_name_cipher") val eventNameCipher: ByteArray,
)

@Database(
    entities = [
        EventEntity::class,
        TicketEntity::class,
        OperationEntity::class,
        DeviceEntity::class,
        DocumentEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class PassVaultDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    abstract fun operationDao(): OperationDao

    abstract fun documentDao(): DocumentDao
}

/**
 * Version 4 gives an event a mark: an icon and a colour.
 *
 * Two nullable text columns and nothing else. Null is the honest value for every event that
 * already existed — nobody chose anything for them — and the interface derives a mark from the
 * identifier in that case, so an old wallet is colourful immediately without anybody being asked
 * to sit down and label twelve events before it looks like anything.
 *
 * In the clear, like `starts_at` beside them. A category and a colour are not what a ticket is
 * worth, and encrypting them would mean a list that cannot be drawn until every event key is
 * open — which is the state the mark exists to fix.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN icon TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN colour TEXT")
    }
}

/**
 * Version 3 keeps the document a set of tickets was split out of.
 *
 * Only a row: the bytes live on disk as ciphertext, the way the `.tkpak` format and the server both
 * store a blob. Nothing existing moves, so wallets from version 2 keep every ticket they had and
 * simply have no source document for them — which is the truth, because it was thrown away.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `documents` (
                `id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `media_type` TEXT NOT NULL,
                `page_count` INTEGER NOT NULL,
                `byte_count` INTEGER NOT NULL,
                `created_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_event_id` ON `documents` (`event_id`)")
    }
}

/**
 * Version 1 held only derived state; version 2 adds the log it should have been derived from.
 *
 * Written by hand rather than left to a destructive fallback. A wallet is the only copy of tickets
 * somebody paid for, and the tickets already in it predate the log — they stay exactly where they
 * are, and the log starts empty and grows from here.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `operations` (
                `operation_id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `actor_user_id` TEXT,
                `lamport` INTEGER NOT NULL,
                `wall_clock` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `body_cipher` BLOB NOT NULL,
                `signature` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `reason` TEXT,
                `received_at` TEXT NOT NULL,
                PRIMARY KEY(`operation_id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_event_id` ON `operations` (`event_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_received_at` ON `operations` (`received_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_state` ON `operations` (`state`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `devices` (
                `id` TEXT NOT NULL,
                `signing_public_key` TEXT NOT NULL,
                `agreement_public_key` TEXT,
                `user_id` TEXT,
                `name` TEXT,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
