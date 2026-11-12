package dev.timlock.jarvis

import android.content.Context
import android.os.Build
import dev.timlock.jarvis.core.secure.LogRedactWriter
import dev.timlock.jarvis.core.secure.Redactor
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** §4.5/§6.7 redacted log export: own-process logcat + diag + about, zipped into the app's external `export` dir. */
object LogExporter {
    private const val KEEP = 5

    data class Result(val ok: Boolean, val path: String? = null, val bytes: Long = 0, val lines: Int = 0, val error: String? = null) {
        val name: String? get() = path?.let { File(it).name }
    }

    suspend fun export(context: Context, diag: JSONObject): Result = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir("export") ?: return@withContext Result(false, error = "external storage unavailable")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val zip = File(dir, "jarvis-logs-$stamp.zip")
            var lines = 0
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                val w = OutputStreamWriter(zos, Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("logcat.txt"))
                lines = writeLogcat(w)
                w.flush(); zos.closeEntry()
                zos.putNextEntry(ZipEntry("diag.json"))
                w.write(Redactor.redact(diag.toString(2))); w.flush(); zos.closeEntry()
                zos.putNextEntry(ZipEntry("about.txt"))
                w.write(Redactor.redact(about(context, diag))); w.flush(); zos.closeEntry()
            }
            dir.listFiles { f -> f.name.startsWith("jarvis-logs-") && f.name.endsWith(".zip") }
                ?.sortedBy { it.name }?.dropLast(KEEP)?.forEach { it.delete() }
            Result(true, zip.path, zip.length(), lines)
        } catch (t: Throwable) {
            Result(false, error = t.javaClass.simpleName)
        }
    }

    private fun writeLogcat(w: OutputStreamWriter): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=" + android.os.Process.myPid()))
            p.errorStream.close()
            val n = p.inputStream.bufferedReader().use { r -> LogRedactWriter.write(r.lineSequence(), w) }
            p.waitFor()
            n
        } catch (t: Throwable) {
            w.write("logcat unavailable: ${t.javaClass.simpleName}\n")
            1
        }
    }

    private fun about(context: Context, diag: JSONObject): String {
        val (name, code) = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName.orEmpty() to (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
        } catch (t: Throwable) { "?" to 0L }
        return "versionName=$name\nversionCode=$code\nmodel=${Build.MODEL}\nsdk=${Build.VERSION.SDK_INT}\nmode=${diag.optString("mode", "?")}\n"
    }
}
