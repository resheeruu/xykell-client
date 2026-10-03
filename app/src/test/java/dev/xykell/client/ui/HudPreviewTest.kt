package dev.xykell.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host JVM tests for structural HUD preview (Batch 8). Pure logic. */
class HudPreviewTest {

    private val layout = """{"name":"Default","elements":[
        {"type":"Fps","x":16.0,"y":48.0,"scale":1.0,"visible":true,"align":"left"},
        {"type":"Coordinates","x":16.0,"y":80.0,"scale":1.0,"visible":false,"align":"left"}
    ]}"""

    @Test
    fun previewListsRows() {
        val lines = HudPreview.preview(layout)
        assertEquals(2, lines.size)
        assertEquals("Fps @(16,48) x1.0", lines[0].text)
        assertTrue(lines[0].visible)
    }

    @Test
    fun renderTextHidesInvisible() {
        val text = HudPreview.renderText(HudPreview.preview(layout))
        assertTrue(text.contains("Fps"))
        assertTrue(!text.contains("Coordinates"))
    }

    @Test
    fun malformedLayoutYieldsEmpty() {
        assertTrue(HudPreview.preview("{broken").isEmpty())
        assertTrue(HudPreview.preview("{}").isEmpty())
        assertEquals("(all elements hidden)\n", HudPreview.renderText(emptyList()))
    }
}
