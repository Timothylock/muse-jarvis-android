package dev.timlock.jarvis.core.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FileCredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val logs = mutableListOf<String>()
    private fun store(dir: File = File(tmp.root, "secure"), k: () -> SecretKey = { key }) =
        FileCredentialStore(dir, k) { logs += it }

    @Test fun roundTripAndDelete() {
        val s = store()
        assertNull(s.read("pairing.json"))
        s.write("pairing.json", "{\"a\":1}".toByteArray())
        assertArrayEquals("{\"a\":1}".toByteArray(), s.read("pairing.json"))
        assertTrue(s.exists("pairing.json"))
        s.delete("pairing.json")
        assertNull(s.read("pairing.json"))
        assertFalse(s.exists("pairing.json"))
    }

    @Test fun fileFormatIsVersionIvCiphertext() {
        val s = store()
        val plain = "mgst_secret_value".toByteArray()
        s.write("sdk_token", plain)
        val raw = File(tmp.root, "secure/sdk_token.enc").readBytes()
        assertEquals(1, raw[0].toInt())
        assertEquals(1 + 12 + plain.size + 16, raw.size)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("mgst_secret_value"))
    }

    @Test fun ivIsRandomPerWrite() {
        val s = store()
        s.write("x", "same".toByteArray())
        val a = File(tmp.root, "secure/x.enc").readBytes()
        s.write("x", "same".toByteArray())
        val b = File(tmp.root, "secure/x.enc").readBytes()
        assertFalse(a.copyOfRange(1, 13).contentEquals(b.copyOfRange(1, 13)))
    }

    private fun assertUnreadable(s: FileCredentialStore, name: String) {
        try {
            s.read(name)
            fail("expected CredentialUnreadableException")
        } catch (e: CredentialUnreadableException) {
            assertEquals(name, e.name)
        }
    }

    @Test fun absentFileReadsNull() {
        val s = store()
        assertNull(s.read("pairing.json"))
        assertFalse(s.exists("pairing.json"))
    }

    @Test fun wrongKeyOrTamperedFileThrowsAndLogsOnlyNameAndClass() {
        store().write("pairing.json", "secret-payload".toByteArray())
        assertUnreadable(store { newKey() }, "pairing.json")
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertTrue(it.contains("pairing.json"))
            assertFalse(it.contains("secret-payload"))
        }
        val f = File(tmp.root, "secure/pairing.json.enc")
        val raw = f.readBytes()
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        f.writeBytes(raw)
        assertUnreadable(store(), "pairing.json")
        assertTrue(store().exists("pairing.json")) // present-but-corrupt still counts as stored
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertUnreadable(store(), "pairing.json")
        raw[0] = 9
        f.writeBytes(raw)
        assertUnreadable(store(), "pairing.json")
    }

    @Test fun fileCannotBeSwappedToAnotherName() {
        val s = store()
        s.write("identity.json", "one".toByteArray())
        File(tmp.root, "secure/identity.json.enc").copyTo(File(tmp.root, "secure/pairing.json.enc"))
        assertUnreadable(s, "pairing.json")
    }

    @Test fun failedWriteKeepsOldValueAndLeavesNoTmp() {
        var broken = false
        val s = store { if (broken) throw IllegalStateException("key gone") else key }
        s.write("pairing.json", "old".toByteArray())
        broken = true
        try { s.write("pairing.json", "new".toByteArray()); fail("expected") } catch (e: IllegalStateException) { }
        broken = false
        assertArrayEquals("old".toByteArray(), s.read("pairing.json"))
        assertEquals(listOf("pairing.json.enc"), File(tmp.root, "secure").list()!!.toList())
    }

    @Test fun overwriteReplaces() {
        val s = store()
        s.write("a", "1".toByteArray()); s.write("a", "22".toByteArray())
        assertArrayEquals("22".toByteArray(), s.read("a"))
        assertEquals(listOf("a.enc"), File(tmp.root, "secure").list()!!.toList())
    }

    @Test fun invalidNamesRejected() {
        val s = store()
        for (n in listOf("", "../x", "a/b", "a b", "..", ".", "x".repeat(65), "é")) {
            try { s.write(n, byteArrayOf(1)); fail("accepted '$n'") } catch (e: IllegalArgumentException) { }
            try { s.read(n); fail("accepted '$n'") } catch (e: IllegalArgumentException) { }
        }
        s.write("x".repeat(64), byteArrayOf(1))
        s.write("A-z-0.9", byteArrayOf(1))
    }

    @Test fun concurrentWritesAndReadsStayConsistent() {
        val s = store()
        s.write("c", "0".repeat(100).toByteArray())
        val pool = Executors.newFixedThreadPool(8)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        repeat(200) { i ->
            pool.execute {
                try {
                    if (i % 2 == 0) s.write("c", i.toString().repeat(50).toByteArray())
                    else {
                        val v = s.read("c") ?: throw AssertionError("null read")
                        if (v.isEmpty()) throw AssertionError("empty")
                    }
                } catch (t: Throwable) { errors.incrementAndGet() }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }
}
