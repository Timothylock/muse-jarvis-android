package dev.timlock.jarvis.ui.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTailTest {
    @Test fun shortTextIsUnchanged() {
        assertEquals("hello world", userCaptionTail("hello world"))
        val exact = "x".repeat(USER_TAIL_CHARS)
        assertEquals(exact, userCaptionTail(exact))
    }

    @Test fun longTextShowsTheTailWithLeadingEllipsis() {
        val words = (1..80).joinToString(" ") { "word$it" }
        val out = userCaptionTail(words)
        assertTrue(out.startsWith("…"))
        assertTrue(out.length <= USER_TAIL_CHARS)
        assertTrue(out.endsWith("word80")) // the newest words stay visible
        assertTrue(out.drop(1).startsWith("word")) // cut at a word boundary
    }

    @Test fun noSpaceNearTheCutStillTruncates() {
        val out = userCaptionTail("a".repeat(400))
        assertEquals(USER_TAIL_CHARS, out.length)
        assertTrue(out.startsWith("…"))
    }
}
