package com.mateof.passvault.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mateof.passvault.crypto.Primitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android KeyStore, on a real device.
 *
 * The only test here that cannot be a JVM test: `AndroidKeyStore` is a platform provider backed by
 * hardware where the device has it, and there is no way to exercise it off-device. Everything built
 * around it is covered by `WalletRepositoryTest` against the in-memory key; this covers the adapter
 * itself, which is the part that either talks to the platform correctly or does not.
 */
@RunWith(AndroidJUnit4::class)
class KeyStoreDeviceKeysTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStoredKey() {
        context.getSharedPreferences("passvault.keys", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun producesAKeyOfTheRightLength() {
        val keys = KeyStoreDeviceKeys(context)

        assertEquals(Primitives.KEY_BYTES, keys.vaultKey().size)
    }

    @Test
    fun returnsTheSameKeyAcrossInstances() {
        // What makes the wallet readable after a restart: a new instance has to unwrap the same key
        // rather than generate a fresh one and orphan every stored row.
        val first = KeyStoreDeviceKeys(context).vaultKey().copyOf()

        val second = KeyStoreDeviceKeys(context).vaultKey()

        assertArrayEquals(first, second)
    }

    @Test
    fun storesTheKeyWrappedRatherThanInTheClear() {
        val keys = KeyStoreDeviceKeys(context)
        val vaultKey = keys.vaultKey()

        val stored = context
            .getSharedPreferences("passvault.keys", android.content.Context.MODE_PRIVATE)
            .getString("wrapped_vault_key", null)

        assertNotNull(stored)
        val decoded = com.mateof.passvault.crypto.Base64Url.decode(stored!!)
        assertFalse(
            "the vault key must not be recoverable from preferences",
            decoded.toList().windowed(vaultKey.size).any { it.toByteArray().contentEquals(vaultKey) },
        )
    }

    @Test
    fun theWholeWalletRoundTripsWithAKeyStoreKey() {
        val keys = KeyStoreDeviceKeys(context)
        val nonce = Primitives.randomNonce()
        val aad = "passvault/v1/field:tickets.barcode_cipher:ticket-1"

        val sealed = Primitives.seal(keys.vaultKey(), nonce, "8412-DEVICE-0001".toByteArray(), aad)
        val opened = Primitives.open(KeyStoreDeviceKeys(context).vaultKey(), nonce, sealed, aad)

        assertEquals("8412-DEVICE-0001", String(opened))
    }
}
