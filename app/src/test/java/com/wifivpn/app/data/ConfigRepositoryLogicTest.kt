package com.wifivpn.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [ConfigRepository] companion helpers (no Android framework).
 */
class ConfigRepositoryLogicTest {

    @Test
    fun normalizeSsid_trimsAndStripsQuotes() {
        assertEquals("U6", ConfigRepository.normalizeSsid("  U6  "))
        assertEquals("U6", ConfigRepository.normalizeSsid("\"U6\""))
        assertEquals("Cafe WiFi", ConfigRepository.normalizeSsid("\" Cafe WiFi \""))
    }

    @Test
    fun normalizeSsid_blankReturnsNull() {
        assertNull(ConfigRepository.normalizeSsid(""))
        assertNull(ConfigRepository.normalizeSsid("   "))
        assertNull(ConfigRepository.normalizeSsid("\"\""))
        assertNull(ConfigRepository.normalizeSsid("\"  \""))
    }

    @Test
    fun clampRetryAttempts_bounds() {
        assertEquals(
            ConfigRepository.MIN_VPN_RETRY_ATTEMPTS,
            ConfigRepository.clampRetryAttempts(0)
        )
        assertEquals(
            ConfigRepository.MIN_VPN_RETRY_ATTEMPTS,
            ConfigRepository.clampRetryAttempts(-5)
        )
        assertEquals(10, ConfigRepository.clampRetryAttempts(10))
        assertEquals(
            ConfigRepository.MAX_VPN_RETRY_ATTEMPTS,
            ConfigRepository.clampRetryAttempts(999)
        )
    }

    @Test
    fun clampRetryDelaySeconds_bounds() {
        assertEquals(
            ConfigRepository.MIN_VPN_RETRY_DELAY_SECONDS,
            ConfigRepository.clampRetryDelaySeconds(1)
        )
        assertEquals(30, ConfigRepository.clampRetryDelaySeconds(30))
        assertEquals(
            ConfigRepository.MAX_VPN_RETRY_DELAY_SECONDS,
            ConfigRepository.clampRetryDelaySeconds(500)
        )
    }

    @Test
    fun parseAssociationEntries_validAndInvalid() {
        val raw = setOf(
            "nid:16|U6",
            "bssid:aa:bb:cc:dd:ee:ff|Home",
            "bad-no-pipe",
            "|missing-key",
            "key-only|",
            "  nid:3  |  \"Cafe\"  "
        )
        val map = ConfigRepository.parseAssociationEntries(raw)
        assertEquals("U6", map["nid:16"])
        assertEquals("Home", map["bssid:aa:bb:cc:dd:ee:ff"])
        assertEquals("Cafe", map["nid:3"])
        assertEquals(3, map.size)
    }

    @Test
    fun encodeAndParseAssociationEntries_roundTrip() {
        val original = mapOf(
            "nid:16" to "U6",
            "bssid:11:22:33:44:55:66" to "Office"
        )
        val encoded = ConfigRepository.encodeAssociationEntries(original)
        val parsed = ConfigRepository.parseAssociationEntries(encoded)
        assertEquals(original, parsed)
    }

    @Test
    fun encodeAssociationEntries_skipsBlank() {
        val encoded = ConfigRepository.encodeAssociationEntries(
            mapOf(
                "" to "U6",
                "nid:1" to "   ",
                "nid:2" to "OK"
            )
        )
        assertEquals(setOf("nid:2|OK"), encoded)
    }

    @Test
    fun parseAssociationEntries_empty() {
        assertTrue(ConfigRepository.parseAssociationEntries(emptySet()).isEmpty())
        assertTrue(ConfigRepository.encodeAssociationEntries(emptyMap()).isEmpty())
    }
}
