package com.wifivpn.app.log

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LogRedactorTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun ssid_neverEchoesInput() {
        val out = LogRedactor.ssid(context, "MyHomeNetwork")
        assertFalse(out.contains("MyHomeNetwork"))
        assertTrue(out.startsWith("#"))
    }

    @Test
    fun ssid_isStableForSameValue() {
        assertEquals(LogRedactor.ssid(context, "U6"), LogRedactor.ssid(context, "U6"))
    }

    @Test
    fun ssid_differsBetweenNetworks() {
        assertNotEquals(LogRedactor.ssid(context, "U6"), LogRedactor.ssid(context, "Cafe"))
    }

    @Test
    fun ssid_blankIsNone() {
        assertEquals("none", LogRedactor.ssid(context, null))
        assertEquals("none", LogRedactor.ssid(context, "   "))
    }

    @Test
    fun assoc_keepsKindButHidesValue() {
        val out = LogRedactor.assoc(context, "bssid:aa:bb:cc:dd:ee:ff")
        assertTrue(out.startsWith("bssid#"))
        assertFalse(out.contains("aa:bb:cc:dd:ee:ff"))
        assertEquals("none", LogRedactor.assoc(context, null))
    }

    @Test
    fun endpoint_keepsPortButHidesHost() {
        val out = LogRedactor.endpoint(context, "vpn.example.com:51820")
        assertFalse(out.contains("vpn.example.com"))
        assertTrue(out.endsWith(":51820"))
    }

    @Test
    fun endpoint_hostWithoutPortIsFullyHidden() {
        val out = LogRedactor.endpoint(context, "vpn.example.com")
        assertFalse(out.contains("vpn.example.com"))
        assertTrue(out.startsWith("#"))
    }
}
