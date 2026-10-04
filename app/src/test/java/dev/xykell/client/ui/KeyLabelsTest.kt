package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** KeyLabels (Batch 13): unbound, touch regions, named Android keycodes,
 *  letter/digit ranges, fallback — pure mapping, no Android runtime. */
class KeyLabelsTest {

    @Test
    fun unboundIsEmDash() {
        assertEquals("—", KeyLabels.label(0))
    }

    @Test
    fun touchRegionsUseOffsetFromTouchBase() {
        assertEquals("TOUCH_0", KeyLabels.label(KeyLabels.TOUCH_BASE))
        assertEquals("TOUCH_3", KeyLabels.label(-1003))
        assertEquals("TOUCH_7", KeyLabels.label(KeyLabels.TOUCH_BASE - 7))
        // Reserved negative outside the documented region format: raw form.
        assertEquals("TOUCH_-5", KeyLabels.label(-5))
    }

    @Test
    fun lettersAndDigitsMapSequentially() {
        assertEquals("A", KeyLabels.label(29))
        assertEquals("Z", KeyLabels.label(54))
        assertEquals("0", KeyLabels.label(7))
        assertEquals("9", KeyLabels.label(16))
    }

    @Test
    fun namedAndroidKeycodes() {
        assertEquals("BACK", KeyLabels.label(4))
        assertEquals("VOL_UP", KeyLabels.label(24))
        assertEquals("VOL_DOWN", KeyLabels.label(25))
        assertEquals("DEL", KeyLabels.label(67))
        assertEquals("SPACE", KeyLabels.label(62))
        assertEquals("ENTER", KeyLabels.label(66))
        assertEquals("ESC", KeyLabels.label(111))
        assertEquals("F1", KeyLabels.label(131))
        assertEquals("F12", KeyLabels.label(142))
    }

    @Test
    fun unknownCodesFallBackToKeyId() {
        assertEquals("KEY_9999", KeyLabels.label(9999))
        assertEquals("KEY_1", KeyLabels.label(1))
    }
}
