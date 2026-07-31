package com.mateof.passvault.update

/**
 * Comparing two version names.
 *
 * The release tag is the only version a GitHub release carries. `versionCode` — the number
 * Android actually enforces an upgrade against — is derived from the CI run number and appears
 * nowhere in the release metadata, so a client deciding whether it is out of date has the name
 * and nothing else.
 *
 * The rules are the small subset of semantic versioning this project uses, written out rather
 * than pulled in:
 *
 *   * a leading `v` is dropped, because that is how the tags are written;
 *   * numeric components compare numerically, so `0.10.0` is newer than `0.9.0` — the one thing
 *     a string comparison gets wrong, and it gets it wrong exactly when it starts to matter;
 *   * trailing zeros are dropped on parsing, so `0.5` and `0.5.0` are the same version and are
 *     also *equal*. Comparing equal while not being equal is a contract violation that surfaces
 *     as two versions sorting the same and de-duplicating differently;
 *   * a suffix marks a pre-release and sorts *before* the same numbers without one, so `1.0.0`
 *     supersedes `1.0.0-rc2` rather than being mistaken for it.
 *
 * Anything unparseable is `NONE`, which is older than every real version. A tag nobody can read
 * must not be announced as an upgrade.
 */
data class Version private constructor(
    private val parts: List<Int>,
    private val preRelease: String?,
    private val known: Boolean,
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        if (known != other.known) return if (known) 1 else -1

        val width = maxOf(parts.size, other.parts.size)
        for (index in 0 until width) {
            val mine = parts.getOrElse(index) { 0 }
            val theirs = other.parts.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return when {
            preRelease == other.preRelease -> 0
            // A release beats its own pre-releases; between two pre-releases, alphabetical is
            // enough for `rc1` and `rc2` and this project has never needed more.
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        /** A version this code could not read. Older than everything, including itself. */
        val NONE = Version(emptyList(), null, known = false)

        fun parse(raw: String): Version {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return NONE
            val separator = trimmed.indexOfFirst { it == '-' || it == '+' }
            val numbers = if (separator >= 0) trimmed.take(separator) else trimmed
            val suffix = if (separator >= 0) trimmed.substring(separator + 1) else null
            val parts = numbers.split('.').map { it.toIntOrNull() ?: return NONE }
            return Version(
                // `0.5` and `0.5.0` have to produce the same value, not merely compare the same.
                parts = parts.dropLastWhile { it == 0 },
                preRelease = suffix?.takeIf { it.isNotEmpty() },
                known = true,
            )
        }
    }
}
