package dev.xykell.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun compareVersions_equal() {
        assertEquals(0, Updater.compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, Updater.compareVersions("2.5.3", "2.5.3"))
    }

    @Test
    fun compareVersions_majorDifference() {
        assertTrue(Updater.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(Updater.compareVersions("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun compareVersions_minorDifference() {
        assertTrue(Updater.compareVersions("1.2.0", "1.1.9") > 0)
        assertTrue(Updater.compareVersions("1.1.0", "1.2.0") < 0)
    }

    @Test
    fun compareVersions_patchDifference() {
        assertTrue(Updater.compareVersions("1.0.2", "1.0.1") > 0)
        assertTrue(Updater.compareVersions("1.0.1", "1.0.2") < 0)
    }

    @Test
    fun compareVersions_preRelease() {
        // Pre-release versions are less than release
        assertTrue(Updater.compareVersions("1.0.0-alpha", "1.0.0") < 0)
        assertTrue(Updater.compareVersions("1.0.0", "1.0.0-alpha") > 0)
        assertTrue(Updater.compareVersions("1.0.0-beta", "1.0.0-rc") < 0)
    }

    @Test
    fun validateMetadata_valid() {
        val json = org.json.JSONObject().apply {
            put("version", "2.0.0")
            put("changelog", "Fixed bugs")
            put("url", "https://example.com/update.apk")
            put("checksum", "a".repeat(64))
        }.toString()
        assertNull(Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_missingVersion() {
        val json = """{"changelog":"test"}"""
        assertNotEquals(null, Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_invalidUrl() {
        val json = """{"version":"2.0.0","url":"http://insecure.com"}"""
        assertNotEquals(null, Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_httpUrl() {
        val json = """{"version":"2.0.0","url":"http://example.com"}"""
        assertNotEquals(null, Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_validHttpsUrl() {
        val json = """{"version":"2.0.0","url":"https://example.com"}"""
        assertNull(Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_invalidChecksum() {
        val json = """{"version":"2.0.0","checksum":"not-hex"}"""
        assertNotEquals(null, Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_validChecksum() {
        val json = org.json.JSONObject().apply {
            put("version", "2.0.0")
            put("checksum", "a".repeat(64))
        }.toString()
        assertNull(Updater.validateMetadata(json))
    }

    @Test
    fun validateMetadata_changelogTooLong() {
        val json = """{"version":"2.0.0","changelog":"${"x".repeat(10001)}"}"""
        assertNotEquals(null, Updater.validateMetadata(json))
    }

    @Test
    fun parseMetadata_valid() {
        val json = """{"version":"2.0.0","changelog":"Fixed bugs","url":"https://example.com"}"""
        val meta = Updater.parseMetadata(json)
        assertNotNull(meta)
        assertEquals("2.0.0", meta!!.version)
        assertEquals("Fixed bugs", meta.changelog)
        assertEquals("https://example.com", meta.url)
    }

    @Test
    fun parseMetadata_invalidReturnsNull() {
        val json = """{"version":""}"""
        assertNull(Updater.parseMetadata(json))
    }

    @Test
    fun isValidVersionFormat_valid() {
        assertTrue(Updater.isValidVersionFormat("1.0.0"))
        assertTrue(Updater.isValidVersionFormat("2.10.5"))
        assertTrue(Updater.isValidVersionFormat("1.0.0-alpha"))
        assertTrue(Updater.isValidVersionFormat("1.0.0-rc.1"))
    }

    @Test
    fun isValidVersionFormat_invalid() {
        assertFalse(Updater.isValidVersionFormat(""))
        assertFalse(Updater.isValidVersionFormat("1.0"))
        assertFalse(Updater.isValidVersionFormat("not-a-version"))
    }

    @Test
    fun isValidChecksum_valid() {
        assertTrue(Updater.isValidChecksum("a".repeat(64)))
        assertTrue(Updater.isValidChecksum("0".repeat(64)))
        assertTrue(Updater.isValidChecksum("ABCDEF1234567890".repeat(4)))
    }

    @Test
    fun isValidChecksum_invalid() {
        assertFalse(Updater.isValidChecksum(""))
        assertFalse(Updater.isValidChecksum("xyz"))
        assertFalse(Updater.isValidChecksum("a".repeat(63)))
        assertFalse(Updater.isValidChecksum("a".repeat(65)))
    }
}