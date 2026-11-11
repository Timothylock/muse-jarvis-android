package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.ListItem
import dev.timlock.jarvis.core.model.Metric
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.util.Locale

/** Outcome of validating one display command (DESIGN §5.3). */
sealed interface Validation {
    data class Show(val panel: Panel, val truncated: Boolean, val ignored: List<String>) : Validation
    /** [id] null = clear everything. */
    data class Clear(val id: String?) : Validation
    data class Reject(val error: String) : Validation
}

/** Deterministic, pure app-side validation + normalization of display.* commands (§5.2, §5.3). */
object DisplayValidator {
    private val ID_RE = Regex("[A-Za-z0-9._-]{1,40}")
    private val ATTACHED_UNITS = setOf("%", "°", "°C", "°F")
    private val WS = Regex("[ \\t\\u00A0\\u2000-\\u200A]+")

    private class Rejected(val msg: String) : Exception(msg, null, false, false)
    private class Acc { var truncated = false }

    fun validate(name: String, paramsJson: String, now: Long, idGen: (PanelType) -> String): Validation {
        val known = KNOWN[name] ?: return Validation.Reject("unknown command")
        return try {
            val obj = parseObject(paramsJson)
            val ignored = obj.keys.filter { it !in known }.sorted()
            val acc = Acc()
            when (name) {
                "display.clear" -> Validation.Clear(optId(obj))
                "display.card" -> show(card(obj, acc, now, idGen), acc, ignored)
                "display.stats" -> show(stats(obj, acc, now, idGen), acc, ignored)
                "display.list" -> show(list(obj, acc, now, idGen), acc, ignored)
                else -> show(timer(obj, acc, now, idGen), acc, ignored)
            }
        } catch (r: Rejected) {
            Validation.Reject(r.msg)
        }
    }

    /** `{"id":..,"shown":true,"truncated":bool}` plus `"ignored":[..]` when non-empty (§5.1). */
    fun showPayload(id: String, truncated: Boolean, ignored: List<String>): String = buildString {
        append("{\"id\":").append(quote(id)).append(",\"shown\":true,\"truncated\":").append(truncated)
        if (ignored.isNotEmpty()) append(",\"ignored\":[").append(ignored.joinToString(",") { quote(it) }).append(']')
        append('}')
    }

    fun clearPayload(id: String?, cleared: Int): String =
        "{\"id\":" + (if (id == null) "null" else quote(id)) + ",\"cleared\":" + cleared + "}"

    private fun quote(s: String) = JsonPrimitive(s).toString()

    private val KNOWN = mapOf(
        "display.card" to setOf("title", "body", "bullets", "id", "sticky"),
        "display.stats" to setOf("title", "metrics", "caption", "id", "sticky"),
        "display.list" to setOf("title", "items", "ordered", "id", "sticky"),
        "display.timer" to setOf("label", "seconds", "id"),
        "display.clear" to setOf("id"),
    )

    private fun show(p: Panel, acc: Acc, ignored: List<String>) = Validation.Show(p, acc.truncated, ignored)

    private fun parseObject(json: String): JsonObject {
        if (json.isBlank()) return JsonObject(emptyMap())
        val el = try { Json.parseToJsonElement(json) } catch (e: Exception) { throw Rejected("params: invalid JSON") }
        return el as? JsonObject ?: throw Rejected("params: must be an object")
    }

    // ---- commands -----------------------------------------------------------------------

    private fun card(o: JsonObject, acc: Acc, now: Long, idGen: (PanelType) -> String): Panel {
        val title = reqString(o, "title", 60, acc)
        val bullets = ArrayList<String>()
        var body: String? = null
        optStringRaw(o, "body")?.let { raw ->
            val plain = ArrayList<String>()
            for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val t = line.trim()
                if (t.startsWith("- ") || t.startsWith("* ")) {
                    val b = clean(t.substring(2))
                    if (b.isNotEmpty()) bullets += trunc(b, 120, acc)
                } else plain += line
            }
            body = trunc(clean(plain.joinToString("\n"), keepNewlines = true), 600, acc).ifEmpty { null }
        }
        o["bullets"]?.let { el ->
            val arr = el as? JsonArray ?: throw Rejected("bullets: must be an array")
            val items = limitArray(arr, 8, "bullets", acc)
            val explicit = items.mapNotNull { e ->
                val p = e as? JsonPrimitive
                if (p == null || !p.isString) throw Rejected("bullets: items must be strings")
                clean(p.content).takeIf { it.isNotEmpty() }?.let { trunc(it, 120, acc) }
            }
            bullets.addAll(0, explicit)
        }
        if (bullets.size > 8) {
            acc.truncated = true
            while (bullets.size > 8) bullets.removeAt(bullets.size - 1)
        }
        val shown = bullets.take(6)
        val content = PanelContent.Card(body, shown, bullets.size - shown.size)
        return panel(PanelType.CARD, o, title, content, optBool(o, "sticky"), now, idGen)
    }

    private fun stats(o: JsonObject, acc: Acc, now: Long, idGen: (PanelType) -> String): Panel {
        val title = reqString(o, "title", 40, acc)
        val arr = o["metrics"]?.let { it as? JsonArray ?: throw Rejected("metrics: must be an array") }
            ?: throw Rejected("metrics: required")
        if (arr.isEmpty()) throw Rejected("metrics: need 1-6 items")
        val metrics = limitArray(arr, 6, "metrics", acc).mapIndexed { i, e ->
            val m = e as? JsonObject ?: throw Rejected("metrics[$i]: must be an object")
            val label = reqString(m, "label", 16, acc, "metrics[$i].label")
            val value = metricValue(m["value"], i, acc)
            val unit = optString(m, "unit", 6, acc, "metrics[$i].unit")
            Metric(label, value, unit, unit != null && unit in ATTACHED_UNITS)
        }
        val caption = optString(o, "caption", 80, acc)
        return panel(PanelType.STATS, o, title, PanelContent.Stats(metrics, caption), optBool(o, "sticky"), now, idGen)
    }

    private fun metricValue(el: JsonElement?, i: Int, acc: Acc): String {
        val p = el as? JsonPrimitive ?: throw Rejected("metrics[$i].value: required string or number")
        val text = if (p.isString) clean(p.content) else {
            val c = p.content
            val n = c.toLongOrNull()?.let { fmt().format(it) } ?: c.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { fmt().format(it) }
            n ?: throw Rejected("metrics[$i].value: must be string or number")
        }
        if (text.isEmpty()) throw Rejected("metrics[$i].value: required")
        return trunc(text, 10, acc)
    }

    private fun fmt(): NumberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
        isGroupingUsed = true
    }

    private fun list(o: JsonObject, acc: Acc, now: Long, idGen: (PanelType) -> String): Panel {
        val title = reqString(o, "title", 60, acc)
        val arr = o["items"]?.let { it as? JsonArray ?: throw Rejected("items: must be an array") }
            ?: throw Rejected("items: required")
        if (arr.isEmpty()) throw Rejected("items: need 1-30 items")
        val items = limitArray(arr, 30, "items", acc).mapIndexed { i, e ->
            when {
                e is JsonPrimitive && e.isString -> ListItem(textOf(e.content, i, acc))
                e is JsonObject -> {
                    val text = textOf(reqRaw(e, "text", "items[$i].text"), i, acc)
                    val level = (e["level"] as? JsonPrimitive)?.let { l ->
                        l.content.toIntOrNull() ?: l.content.toDoubleOrNull()?.toInt()
                    } ?: 0
                    ListItem(text, level.coerceIn(0, 2), optBool(e, "done"))
                }
                else -> throw Rejected("items[$i]: must be string or {text}")
            }
        }
        val ordered = optBool(o, "ordered")
        return panel(PanelType.LIST, o, title, PanelContent.ListBody(items, ordered), optBool(o, "sticky"), now, idGen)
    }

    private fun textOf(raw: String, i: Int, acc: Acc): String {
        val t = clean(raw)
        if (t.isEmpty()) throw Rejected("items[$i].text: required")
        return trunc(t, 100, acc)
    }

    private fun timer(o: JsonObject, acc: Acc, now: Long, idGen: (PanelType) -> String): Panel {
        val label = reqString(o, "label", 40, acc)
        val bad = Rejected("seconds: must be integer 1-86400")
        val p = o["seconds"] as? JsonPrimitive ?: throw bad
        if (p.isString) throw bad
        val secs = p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.takeIf { it == Math.floor(it) && it.isFinite() }?.toLong() ?: throw bad
        if (secs !in 1..86400) throw bad
        val content = PanelContent.Timer(secs.toInt(), now + secs * 1000)
        return panel(PanelType.TIMER, o, label, content, false, now, idGen)
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun panel(type: PanelType, o: JsonObject, title: String, content: PanelContent, sticky: Boolean, now: Long, idGen: (PanelType) -> String): Panel {
        val id = optId(o) ?: idGen(type)
        return Panel(id = id, type = type, title = title, content = content, sticky = sticky, createdAtMs = now, updatedAtMs = now)
    }

    private fun optId(o: JsonObject): String? {
        val el = o["id"] ?: return null
        val p = el as? JsonPrimitive
        if (p == null || !p.isString || !ID_RE.matches(p.content)) throw Rejected("id: invalid")
        return p.content
    }

    private fun optBool(o: JsonObject, key: String): Boolean {
        val el = o[key] ?: return false
        val p = el as? JsonPrimitive
        if (p == null || p.isString) throw Rejected("$key: must be boolean")
        return when (p.content) { "true" -> true; "false" -> false; else -> throw Rejected("$key: must be boolean") }
    }

    private fun reqRaw(o: JsonObject, key: String, label: String = key): String {
        val p = o[key] as? JsonPrimitive
        if (p == null || !p.isString) throw Rejected("$label: required")
        return p.content
    }

    private fun optStringRaw(o: JsonObject, key: String, label: String = key): String? {
        val el = o[key] ?: return null
        val p = el as? JsonPrimitive
        if (p == null || !p.isString) throw Rejected("$label: must be string")
        return p.content
    }

    private fun reqString(o: JsonObject, key: String, max: Int, acc: Acc, label: String = key): String {
        val t = clean(reqRaw(o, key, label))
        if (t.isEmpty()) throw Rejected("$label: required")
        return trunc(t, max, acc)
    }

    private fun optString(o: JsonObject, key: String, max: Int, acc: Acc, label: String = key): String? {
        val raw = optStringRaw(o, key, label) ?: return null
        return clean(raw).takeIf { it.isNotEmpty() }?.let { trunc(it, max, acc) }
    }

    /** Arrays over [max]: within floor(1.5x) -> truncate + flag, else reject (§5.3). */
    private fun limitArray(arr: JsonArray, max: Int, name: String, acc: Acc): List<JsonElement> {
        if (arr.size <= max) return arr
        if (arr.size > max * 3 / 2) throw Rejected("$name: max $max items")
        acc.truncated = true
        return arr.subList(0, max)
    }

    private fun trunc(s: String, max: Int, acc: Acc): String {
        if (s.length <= max) return s
        acc.truncated = true
        return s.substring(0, max - 1) + "…"
    }

    /** Strip markdown marks + control chars, collapse whitespace; [keepNewlines] keeps line breaks (single blank max). */
    internal fun clean(raw: String, keepNewlines: Boolean = false): String {
        val stripped = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace("**", "").replace("#", "").replace("`", "")
        val lines = stripped.split('\n').map { line ->
            val sb = StringBuilder(line.length)
            for (ch in line) {
                if (ch == '\t') sb.append(' ') else if (!ch.isISOControl()) sb.append(ch)
            }
            WS.replace(sb.toString(), " ").trim()
        }
        if (!keepNewlines) return lines.filter { it.isNotEmpty() }.joinToString(" ")
        val out = ArrayList<String>()
        for (l in lines) {
            if (l.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += l
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }
}
