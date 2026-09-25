package com.wifivpn.app.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the trusted-Wi‑Fi fail-open bug: when the platform redacts the
 * SSID *and* every association key, association memory must not be able to claim the
 * network is trusted. Getting this wrong disables the VPN on hostile access points.
 *
 * Robolectric reports no Wi‑Fi association, so [WifiConnectivityMonitor.currentAssociationKeys]
 * is empty — exactly the "everything redacted" case we must fail closed on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WifiAssociationMemoryTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun monitor(memory: Map<String, String>): WifiConnectivityMonitor =
        WifiConnectivityMonitor(context).apply { setTrustedAssociations(memory) }

    private fun resolve(
        monitor: WifiConnectivityMonitor,
        trusted: Set<String>
    ): Pair<String, String>? {
        val method = WifiConnectivityMonitor::class.java
            .getDeclaredMethod("resolveFromAssociationMemory", Set::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(monitor, trusted) as Pair<String, String>?
    }

    @Test
    fun soleRememberedSsid_isNotTrustedWithoutKeyMatch() {
        val m = monitor(mapOf("nid:3" to "Home"))
        assertNull(resolve(m, setOf("Home")))
    }

    @Test
    fun soleTrustedSsid_isNotTrustedWithoutKeyMatch() {
        val m = monitor(mapOf("bssid:aa:bb:cc:dd:ee:ff" to "Home"))
        assertNull(resolve(m, setOf("Home")))
    }

    @Test
    fun emptyMemory_isNotTrusted() {
        assertNull(resolve(monitor(emptyMap()), setOf("Home")))
    }

    @Test
    fun emptyTrustedList_isNotTrusted() {
        val m = monitor(mapOf("nid:3" to "Home"))
        assertNull(resolve(m, emptySet()))
    }

    @Test
    fun memoryForUntrustedSsid_isNotTrusted() {
        val m = monitor(mapOf("nid:3" to "OldHome"))
        assertNull(resolve(m, setOf("Home")))
    }

    @Test
    fun matchLabelIsStable() {
        // The service treats this label as "came from memory, do not re-persist it".
        assertEquals("assoc_memory", WifiConnectivityMonitor.MATCH_ASSOC_MEMORY)
    }
}
