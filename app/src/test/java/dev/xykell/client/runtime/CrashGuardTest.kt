package dev.xykell.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {

    @Test
    fun redactSensitive_redactsAccessToken() {
        val input = "access_token=abc123def456"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
        assertTrue(!redacted.contains("abc123def456"))
    }

    @Test
    fun redactSensitive_redactsRefreshToken() {
        val input = "refresh_token=xyz789"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun redactSensitive_redactsBearerToken() {
        val input = "Authorization: Bearer mysecret123"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
        assertTrue(!redacted.contains("mysecret123"))
    }

    @Test
    fun redactSensitive_redactsCookie() {
        val input = "cookie=session123; path=/"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun redactSensitive_redactsPassword() {
        val input = "password=secret123"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun redactSensitive_redactsSecret() {
        val input = "client_secret=mysecret"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun redactSensitive_doesNotRedactNormalText() {
        val input = "This is a normal error message with no secrets"
        val redacted = CrashGuard.redactSensitive(input)
        assertEquals(input, redacted)
    }

    @Test
    fun redactSensitive_caseInsensitive() {
        val input = "ACCESS_TOKEN=UPPERCASE"
        val redacted = CrashGuard.redactSensitive(input)
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun truncateToMaxLength_truncatesLongString() {
        val longString = "a".repeat(70000)
        val truncated = CrashGuard.truncateToMaxLength(longString)
        assertTrue(truncated.length <= CrashGuard.MAX_REPORT_SIZE + 20) // +20 for "[TRUNCATED]"
        assertTrue(truncated.endsWith("[TRUNCATED]"))
    }

    @Test
    fun truncateToMaxLength_keepsShortString() {
        val short = "short string"
        assertEquals(short, CrashGuard.truncateToMaxLength(short))
    }

    // Note: These tests use package-private functions from CrashGuard for testing.
    // In a real scenario, these would be internal or we'd test via public API.
    // The redaction logic is tested here; Android-specific parts need instrumented tests.
}