package com.mateof.passvault.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Deciding whether a published release is newer than the one running.
 *
 * The whole updater rests on this one comparison, and the failure it exists to prevent is the
 * quiet one: a version test that gets `0.10.0` wrong reports "you are up to date" for ever, and
 * nobody finds out until somebody asks why their phone is three versions behind.
 */
class VersionTest {

    private fun newer(later: String, than: String): Boolean =
        Version.parse(later) > Version.parse(than)

    @Test
    fun `a higher patch supersedes a lower one`() {
        assertThat(newer("0.4.1", "0.4.0")).isTrue()
    }

    @Test
    fun `a higher minor supersedes a lower one`() {
        assertThat(newer("0.5.0", "0.4.9")).isTrue()
    }

    @Test
    fun `ten is newer than nine, which a string comparison gets backwards`() {
        assertThat(newer("0.10.0", "0.9.0")).isTrue()
        assertThat("0.10.0" > "0.9.0").isFalse()
    }

    @Test
    fun `the tag's leading v is not part of the version`() {
        assertThat(Version.parse("v0.4.0")).isEqualTo(Version.parse("0.4.0"))
    }

    @Test
    fun `a missing component is zero, so 0_5 and 0_5_0 are the same version`() {
        assertThat(Version.parse("0.5")).isEqualTo(Version.parse("0.5.0"))
        assertThat(newer("0.5", "0.5.0")).isFalse()
    }

    @Test
    fun `the same version is not an update`() {
        assertThat(newer("0.4.0", "0.4.0")).isFalse()
    }

    @Test
    fun `an older release is not offered as one`() {
        assertThat(newer("0.3.0", "0.4.0")).isFalse()
    }

    @Test
    fun `a release supersedes its own pre-releases`() {
        assertThat(newer("1.0.0", "1.0.0-rc2")).isTrue()
        assertThat(newer("1.0.0-rc2", "1.0.0")).isFalse()
    }

    @Test
    fun `a later pre-release supersedes an earlier one`() {
        assertThat(newer("1.0.0-rc2", "1.0.0-rc1")).isTrue()
    }

    @Test
    fun `a tag nobody can read is never announced as an upgrade`() {
        assertThat(newer("latest", "0.4.0")).isFalse()
        assertThat(newer("", "0.4.0")).isFalse()
        assertThat(newer("0.4.x", "0.4.0")).isFalse()
    }
}
