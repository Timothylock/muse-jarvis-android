package dev.timlock.jarvis.core.secure

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactWriterTest {
    @Test fun redactsTokensAndJwt() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk"
        val sw = StringWriter()
        val n = LogRedactWriter.write(sequenceOf("I tag: tok mgst_abcdefghijklmnop and $jwt end"), sw)
        assertEquals(1, n)
        assertFalse(sw.toString().contains("abcdefghijklmnop"))
        assertFalse(sw.toString().contains("eyJhbGci"))
    }

    @Test fun keepsLastLinesOnly() {
        val sw = StringWriter()
        val n = LogRedactWriter.write((1..100).asSequence().map { "line $it" }, sw, maxLines = 10)
        assertEquals(10, n)
        val out = sw.toString().lines().filter { it.isNotEmpty() }
        assertEquals("line 91", out.first())
        assertEquals("line 100", out.last())
    }

    @Test fun respectsCharCap() {
        val sw = StringWriter()
        LogRedactWriter.write((1..100).asSequence().map { "x".repeat(99) }, sw, maxChars = 1000)
        assertTrue(sw.toString().length <= 1000)
    }
}
