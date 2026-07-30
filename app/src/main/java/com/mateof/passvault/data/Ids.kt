package com.mateof.passvault.data

import com.mateof.passvault.crypto.Primitives
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Identifiers and instants, with the server's conventions.
 *
 * These are not cosmetic. Operations are ordered, compared and deduplicated on both sides, so a row
 * written by a phone and a row written by the server have to be the same shape or the comparison
 * quietly means something different depending on who wrote it. Mirrors `packages/db/src/portable.ts`.
 */
object Ids {

    /**
     * A UUIDv7: 48 bits of millisecond timestamp, then randomness.
     *
     * Time-ordered on purpose. Version 4 sorts randomly, which turns an append-only log into random
     * inserts across the index and makes "the operations this device produced, oldest first" a sort
     * rather than a scan. The wallet used version 4 before the log existed and it did not matter
     * then; it does now.
     */
    fun newId(): String {
        val bytes = Primitives.randomBytes(16)
        val timestamp = System.currentTimeMillis()

        // Big-endian 48-bit timestamp in the first six bytes.
        for (index in 0 until 6) {
            bytes[index] = (timestamp shr (8 * (5 - index)) and 0xFF).toByte()
        }
        // Version 7 in the high nibble of byte 6, variant 10 in the top bits of byte 8.
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

        return buildString(36) {
            for (index in bytes.indices) {
                if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                append("%02x".format(bytes[index].toInt() and 0xFF))
            }
        }
    }

    private val INSTANT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * ISO-8601 UTC with millisecond precision, always exactly 24 characters.
     *
     * Fixed width is the point: a string comparison is then a chronological one, which is what lets
     * the sync cursor be a plain `received_at > cursor` rather than a date comparison that behaves
     * differently on each side.
     */
    fun toInstant(at: Instant = Instant.now()): String = INSTANT_FORMAT.format(at)

    const val INSTANT_LENGTH = 24
}

/** Parses a UUID, returning null rather than throwing, for values that arrived from a peer. */
fun String.asUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
