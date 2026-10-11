package dev.xykell.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for onboarding state and launch-mode policy.
 *
 * Two properties carry the weight: the launch mode is a closed set, and a
 * document we cannot fully understand is refused whole rather than half-applied.
 */
class OnboardingTest {

    @Test
    fun `a fresh install is a first run with no mode chosen`() {
        val o = Onboarding()
        assertTrue(o.firstRun)
        assertFalse(o.introductionSeen)
        assertNull(o.launchMode)
        assertFalse(o.canOfferLaunch)
    }

    @Test
    fun `completing onboarding records the chosen mode`() {
        val o = Onboarding()
        o.completeOnboarding(Onboarding.LaunchMode.MANUAL)
        assertFalse(o.firstRun)
        assertTrue(o.introductionSeen)
        assertEquals(Onboarding.LaunchMode.MANUAL, o.launchMode)
        assertTrue(o.canOfferLaunch)
    }

    @Test
    fun `a launch action is never offered before onboarding completes`() {
        // Offering one would be acting on a decision the user has not made.
        val o = Onboarding()
        assertFalse(o.canOfferLaunch)
        o.setLaunchMode("watch")
        assertFalse(o.canOfferLaunch)
    }

    @Test
    fun `an unknown mode id is refused and changes nothing`() {
        val o = Onboarding()
        o.setLaunchMode("manual")
        assertFalse(o.setLaunchMode("definitely-not-a-mode"))
        assertEquals(Onboarding.LaunchMode.MANUAL, o.launchMode)
    }

    @Test
    fun `every advertised id round-trips`() {
        val o = Onboarding()
        for (mode in Onboarding.LaunchMode.values()) {
            assertTrue(o.setLaunchMode(mode.id))
            assertEquals(mode, o.launchMode)
        }
        assertEquals(Onboarding.LaunchMode.values().size, Onboarding.LaunchMode.ids.size)
    }

    @Test
    fun `reset returns to a fresh install`() {
        val o = Onboarding()
        o.completeOnboarding(Onboarding.LaunchMode.WATCH)
        o.reset()
        assertTrue(o.firstRun)
        assertFalse(o.introductionSeen)
        assertNull(o.launchMode)
    }

    @Test
    fun `state survives a serialize round trip`() {
        val o = Onboarding()
        o.completeOnboarding(Onboarding.LaunchMode.MANUAL)
        val json = o.serialize()

        val restored = Onboarding()
        assertTrue(restored.deserialize(json))
        assertEquals(o.firstRun, restored.firstRun)
        assertEquals(o.introductionSeen, restored.introductionSeen)
        assertEquals(o.launchMode, restored.launchMode)
    }

    @Test
    fun `an unset mode survives a round trip as unset`() {
        val o = Onboarding()
        o.introductionSeen = true
        val restored = Onboarding()
        assertTrue(restored.deserialize(o.serialize()))
        assertNull(restored.launchMode)
    }

    @Test
    fun `malformed json is refused whole, not half-applied`() {
        val o = Onboarding()
        o.completeOnboarding(Onboarding.LaunchMode.MANUAL)
        assertFalse(o.deserialize("not json"))
        // Refused means refused: the previous state stands.
        assertEquals(Onboarding.LaunchMode.MANUAL, o.launchMode)
    }

    @Test
    fun `an unknown mode inside a document refuses the whole document`() {
        val o = Onboarding()
        assertFalse(
            o.deserialize("""{"schemaVersion":1,"launchMode":"teleport"}"""),
        )
        assertNull(o.launchMode)
    }

    @Test
    fun `a newer schema is refused rather than downgraded`() {
        val o = Onboarding()
        val future = """{"schemaVersion":${Onboarding.SCHEMA_VERSION + 1},"launchMode":"manual"}"""
        assertFalse(o.deserialize(future))
    }

    @Test
    fun `an empty document leaves the default state`() {
        val o = Onboarding()
        assertTrue(o.deserialize(""))
        assertTrue(o.firstRun)
    }

    @Test
    fun `an explicit null mode is read as unset, not as an error`() {
        val o = Onboarding()
        assertTrue(o.deserialize("""{"schemaVersion":1,"launchMode":null}"""))
        assertNull(o.launchMode)
    }

    @Test
    fun `mode ids are unique`() {
        assertEquals(
            Onboarding.LaunchMode.values().size,
            Onboarding.LaunchMode.ids.toSet().size,
        )
    }
}