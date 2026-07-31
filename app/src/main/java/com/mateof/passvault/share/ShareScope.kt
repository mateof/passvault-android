package com.mateof.passvault.share

/**
 * What a transfer is about to hand over.
 *
 * Until this existed, pairing two phones exchanged both wallets entirely: `allApplied()`, every
 * event, every ticket. That is right for two devices belonging to the same person and wrong for
 * every other case — passing one seat to a friend meant passing them the concert you are going to
 * next month and the flight after that.
 *
 * The three cases are the three things somebody actually means by "share":
 *
 *   * [Everything] — my other phone. What the old behaviour did, kept deliberately.
 *   * [Event] — this concert, all of it.
 *   * [Tickets] — these two seats out of the twelve.
 *
 * The last one hands over an incomplete view on purpose, and the interface says so: the receiver
 * gets the event and the tickets named, not the ten that were not. That is the point, and it is
 * also why it needs saying — a wallet showing two of twelve looks like a failed transfer unless
 * somebody was told it would.
 */
sealed interface ShareScope {

    data object Everything : ShareScope

    data class Event(val eventId: String, val eventName: String) : ShareScope

    data class Tickets(
        val eventId: String,
        val eventName: String,
        val ticketIds: List<String>,
    ) : ShareScope
}
