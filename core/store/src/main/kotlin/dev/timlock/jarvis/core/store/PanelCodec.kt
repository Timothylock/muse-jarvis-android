package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.ListItem
import dev.timlock.jarvis.core.model.Metric
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * JSON (de)serialization of a persisted [PanelStack] (DESIGN §5.5). Pure Kotlin so it is JVM-testable.
 *
 * Every monotonic timestamp (monoMillis, meaningless after process death/reboot) is stored as wall-clock:
 * `wall = mono - nowMono + nowWall` on encode, and the inverse on decode. Callers pass both clocks.
 */
object PanelCodec {
    private const val VERSION = 1

    fun encode(stack: PanelStack, nowMono: Long, nowWall: Long): String {
        val w = { mono: Long -> mono - nowMono + nowWall }
        val root = buildJsonObject {
            put("v", VERSION)
            put("replacedToastAtWall", if (stack.replacedToastAtMs == 0L) 0L else w(stack.replacedToastAtMs))
            put("panels", JsonArray(stack.panels.map { encodePanel(it, w) }))
        }
        return root.toString()
    }

    /** Returns null for anything malformed or from an unknown version; never throws. */
    fun decode(text: String, nowMono: Long, nowWall: Long): PanelStack? = try {
        val m = { wall: Long -> wall - nowWall + nowMono }
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) null
        else {
            val toast = root["replacedToastAtWall"]?.jsonPrimitive?.longOrNull ?: 0L
            PanelStack(
                panels = root["panels"]!!.jsonArray.map { decodePanel(it.jsonObject, m) },
                replacedToastAtMs = if (toast == 0L) 0L else m(toast),
            )
        }
    } catch (t: Exception) {
        null
    }

    private fun encodePanel(p: Panel, w: (Long) -> Long): JsonObject = buildJsonObject {
        put("id", p.id)
        put("type", p.type.name)
        put("title", p.title)
        put("sticky", p.sticky)
        put("pinned", p.pinned)
        put("createdAt", w(p.createdAtMs))
        put("updatedAt", w(p.updatedAtMs))
        put("version", p.version)
        put("newRows", JsonArray(p.newRows.sorted().map { JsonPrimitive(it) }))
        put("expiresAt", p.expiresAtMs?.let { JsonPrimitive(w(it)) } ?: JsonNull)
        put("collapsed", p.collapsed)
        put("sim", p.sim)
        put("placement", p.placement.name)
        put("revealStart", w(p.revealStartMs))
        put("content", encodeContent(p.content, w))
    }

    private fun encodeContent(c: PanelContent, w: (Long) -> Long): JsonObject = buildJsonObject {
        when (c) {
            is PanelContent.Card -> {
                put("k", "card")
                put("body", c.body?.let { JsonPrimitive(it) } ?: JsonNull)
                put("bullets", JsonArray(c.bullets.map { JsonPrimitive(it) }))
                put("moreBullets", c.moreBullets)
            }
            is PanelContent.Stats -> {
                put("k", "stats")
                put("caption", c.caption?.let { JsonPrimitive(it) } ?: JsonNull)
                put("metrics", JsonArray(c.metrics.map { mt ->
                    buildJsonObject {
                        put("label", mt.label)
                        put("value", mt.value)
                        put("unit", mt.unit?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("unitAttached", mt.unitAttached)
                    }
                }))
            }
            is PanelContent.ListBody -> {
                put("k", "list")
                put("ordered", c.ordered)
                put("items", JsonArray(c.items.map { i ->
                    buildJsonObject {
                        put("text", i.text)
                        put("level", i.level)
                        put("done", i.done)
                    }
                }))
            }
            is PanelContent.Timer -> {
                put("k", "timer")
                put("totalSeconds", c.totalSeconds)
                put("endsAt", w(c.endsAtMs))
                put("chimed", c.chimed)
            }
        }
    }

    private fun decodePanel(o: JsonObject, m: (Long) -> Long): Panel = Panel(
        id = o.str("id")!!,
        type = PanelType.valueOf(o.str("type")!!),
        title = o.str("title")!!,
        content = decodeContent(o["content"]!!.jsonObject, m),
        sticky = o.bool("sticky"),
        pinned = o.bool("pinned"),
        createdAtMs = m(o.lng("createdAt")),
        updatedAtMs = m(o.lng("updatedAt")),
        version = o.lng("version").toInt(),
        newRows = o["newRows"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }?.toSet() ?: emptySet(),
        expiresAtMs = o["expiresAt"]?.jsonPrimitive?.longOrNull?.let(m),
        collapsed = o.bool("collapsed"),
        sim = o.bool("sim"),
        placement = PanelPlacement.valueOf(o.str("placement")!!),
        revealStartMs = m(o.lng("revealStart")),
    )

    private fun decodeContent(o: JsonObject, m: (Long) -> Long): PanelContent = when (o.str("k")) {
        "card" -> PanelContent.Card(
            body = o.str("body"),
            bullets = o["bullets"]!!.jsonArray.map { it.jsonPrimitive.content },
            moreBullets = o.lng("moreBullets").toInt(),
        )
        "stats" -> PanelContent.Stats(
            metrics = o["metrics"]!!.jsonArray.map {
                val j = it.jsonObject
                Metric(j.str("label")!!, j.str("value")!!, j.str("unit"), j.bool("unitAttached"))
            },
            caption = o.str("caption"),
        )
        "list" -> PanelContent.ListBody(
            items = o["items"]!!.jsonArray.map {
                val j = it.jsonObject
                ListItem(j.str("text")!!, j.lng("level").toInt(), j.bool("done"))
            },
            ordered = o.bool("ordered"),
        )
        "timer" -> PanelContent.Timer(
            totalSeconds = o.lng("totalSeconds").toInt(),
            endsAtMs = m(o.lng("endsAt")),
            chimed = o.bool("chimed"),
        )
        else -> error("unknown content kind")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = this[k]?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.lng(k: String): Long = this[k]!!.jsonPrimitive.longOrNull!!
}
