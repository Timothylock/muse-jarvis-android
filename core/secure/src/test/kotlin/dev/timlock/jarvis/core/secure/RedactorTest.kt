package dev.timlock.jarvis.core.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    private val tok = "mgst_" + "A".repeat(42) + "w"

    @Test fun masksSdkToken() {
        val out = Redactor.redact("token is $tok ok")
        assertFalse(out.contains("AAAAAAAA"))
    }

    @Test fun masksJwtAndBearer() {
        val out = Redactor.redact("Authorization: Bearer eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl")
        assertFalse(out.contains("eyJzdWIiOiIx"))
    }

    @Test fun masksKeyedJson() {
        val out = Redactor.redact("{\"access_token\": \"abcd1234efgh\", \"refresh_token\":\"zzzz9999\"}")
        assertFalse(out.contains("abcd1234efgh"))
        assertFalse(out.contains("zzzz9999"))
    }

    @Test fun leavesPlainTextAlone() {
        assertEquals("connecting to vm-1 after 2s", Redactor.redact("connecting to vm-1 after 2s"))
    }
}
