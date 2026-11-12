package dev.timlock.jarvis.core.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.timlock.jarvis.core.secure.PairingImport.Result

class PairingImportTest {
    private val mac = "02:ab:cd:3f:a2:c1"
    private val token = "mgst_" + "A".repeat(42) + "w"
    private val secretAccess = "ACCESS-SECRET-VALUE"

    private fun bundle(
        identity: String? = "{\"mac\":\"$mac\"}",
        pairing: String? = "{\"access_token\":\"$secretAccess\",\"refresh_token\":\"REFRESH-SECRET\",\"access_token_saved_at\":1700000000.5}",
        sdk: String? = "\"$token\"",
    ): ByteArray = buildString {
        append("{")
        val parts = mutableListOf<String>()
        identity?.let { parts += "\"identity\":$it" }
        pairing?.let { parts += "\"pairing\":$it" }
        sdk?.let { parts += "\"sdk_token\":$it" }
        append(parts.joinToString(","))
        append("}")
    }.toByteArray()

    @Test fun validBundleStoresAllThree() {
        val s = InMemoryCredentialStore()
        val r = PairingImport.apply(s, bundle())
        assertEquals(Result.Ok("homelink-3fa2c1"), r)
        assertTrue(String(s.read(CredentialNames.IDENTITY)!!).contains(mac))
        assertTrue(String(s.read(CredentialNames.PAIRING)!!).contains(secretAccess))
        assertArrayEquals(token.toByteArray(), s.read(CredentialNames.SDK_TOKEN))
    }

    @Test fun tokenOptionalAndKeepsExisting() {
        val s = InMemoryCredentialStore()
        s.write(CredentialNames.SDK_TOKEN, "old".toByteArray())
        assertTrue(PairingImport.apply(s, bundle(sdk = null)) is Result.Ok)
        assertArrayEquals("old".toByteArray(), s.read(CredentialNames.SDK_TOKEN))
    }

    @Test fun timestampOptional() {
        val p = "{\"access_token\":\"a\",\"refresh_token\":\"b\"}"
        assertTrue(PairingImport.apply(InMemoryCredentialStore(), bundle(pairing = p)) is Result.Ok)
    }

    @Test fun badJson() {
        for (b in listOf("", "nope", "[]", "{", "\"x\"")) {
            val s = InMemoryCredentialStore()
            assertTrue(b, PairingImport.apply(s, b.toByteArray()) is Result.Invalid)
            assertNull(s.read(CredentialNames.PAIRING))
        }
    }

    @Test fun badMac() {
        for (m in listOf("\"02:AB:cd:3f:a2:c1\"", "\"02:ab:cd:3f:a2\"", "\"zz:ab:cd:3f:a2:c1\"", "123", "null", "\"\"")) {
            val s = InMemoryCredentialStore()
            val r = PairingImport.apply(s, bundle(identity = "{\"mac\":$m}"))
            assertTrue(m, r is Result.Invalid)
            assertNull(s.read(CredentialNames.IDENTITY))
        }
        assertTrue(PairingImport.apply(InMemoryCredentialStore(), bundle(identity = null)) is Result.Invalid)
        assertTrue(PairingImport.apply(InMemoryCredentialStore(), bundle(identity = "{}")) is Result.Invalid)
    }

    @Test fun missingOrBlankTokens() {
        val cases = listOf(
            null,
            "{}",
            "{\"access_token\":\"a\"}",
            "{\"refresh_token\":\"b\"}",
            "{\"access_token\":\"  \",\"refresh_token\":\"b\"}",
            "{\"access_token\":\"a\",\"refresh_token\":\"\"}",
            "{\"access_token\":1,\"refresh_token\":\"b\"}",
            "[]",
        )
        for (p in cases) {
            val s = InMemoryCredentialStore()
            assertTrue(p.toString(), PairingImport.apply(s, bundle(pairing = p)) is Result.Invalid)
            assertNull(s.read(CredentialNames.IDENTITY))
        }
    }

    @Test fun badTimestamp() {
        for (t in listOf("-1", "\"1700000000\"", "true", "null", "[]", "{}", "1e999")) {
            val p = "{\"access_token\":\"a\",\"refresh_token\":\"b\",\"access_token_saved_at\":$t}"
            val s = InMemoryCredentialStore()
            assertTrue(t, PairingImport.apply(s, bundle(pairing = p)) is Result.Invalid)
            assertNull(s.read(CredentialNames.PAIRING))
        }
        val zero = "{\"access_token\":\"a\",\"refresh_token\":\"b\",\"access_token_saved_at\":0}"
        assertTrue(PairingImport.apply(InMemoryCredentialStore(), bundle(pairing = zero)) is Result.Ok)
    }

    @Test fun badSdkToken() {
        for (t in listOf("\"mgst_short\"", "\"abc\"", "123", "true", "\"mgst_" + "A".repeat(42) + "z\"", "\"\"")) {
            val s = InMemoryCredentialStore()
            assertTrue(t, PairingImport.apply(s, bundle(sdk = t)) is Result.Invalid)
            assertNull(s.read(CredentialNames.PAIRING))
        }
    }

    @Test fun identityConflictThenOverwrite() {
        val s = InMemoryCredentialStore()
        s.write(CredentialNames.IDENTITY, "{\"mac\":\"02:00:00:11:22:33\"}".toByteArray())
        val r = PairingImport.apply(s, bundle())
        assertEquals(Result.IdentityConflict("homelink-112233", "homelink-3fa2c1"), r)
        assertNull(s.read(CredentialNames.PAIRING))
        assertTrue(String(s.read(CredentialNames.IDENTITY)!!).contains("02:00:00:11:22:33"))

        assertEquals(Result.Ok("homelink-3fa2c1"), PairingImport.apply(s, bundle(), overwriteIdentity = true))
        assertTrue(String(s.read(CredentialNames.IDENTITY)!!).contains(mac))
        assertTrue(s.exists(CredentialNames.PAIRING))
    }

    @Test fun sameIdentityIsNotAConflict() {
        val s = InMemoryCredentialStore()
        s.write(CredentialNames.IDENTITY, "{\"mac\":\"$mac\"}".toByteArray())
        assertTrue(PairingImport.apply(s, bundle()) is Result.Ok)
    }

    @Test fun corruptStoredIdentityIsReplaced() {
        val s = InMemoryCredentialStore()
        s.write(CredentialNames.IDENTITY, "garbage".toByteArray())
        assertTrue(PairingImport.apply(s, bundle()) is Result.Ok)
    }

    @Test fun reasonsNeverContainValues() {
        val bad = listOf(
            bundle(identity = "{\"mac\":\"SECRETMAC\"}"),
            bundle(pairing = "{\"access_token\":\"$secretAccess\",\"refresh_token\":\"r\",\"access_token_saved_at\":-5}"),
            bundle(sdk = "\"mgst_LEAKME\""),
            "{\"secret\": nope $secretAccess".toByteArray(),
        )
        for (b in bad) {
            val r = PairingImport.apply(InMemoryCredentialStore(), b) as Result.Invalid
            for (v in listOf("SECRETMAC", secretAccess, "LEAKME", "mgst_")) assertFalse(r.reason.contains(v))
        }
    }

    @Test fun nodeIdForMac() {
        assertEquals("homelink-3fa2c1", PairingImport.nodeIdForMac("02:ab:cd:3f:a2:c1"))
    }
}
