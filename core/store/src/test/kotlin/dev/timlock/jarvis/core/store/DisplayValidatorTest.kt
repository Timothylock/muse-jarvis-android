package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class DisplayValidatorTest {
    private var saved: Locale = Locale.getDefault()
    @Before fun locale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restore() { Locale.setDefault(saved) }

    private fun v(name: String, json: String) =
        DisplayValidator.validate(name, json, 1000L) { t -> "${t.name.lowercase()}-gen" }

    private fun show(name: String, json: String) = v(name, json) as Validation.Show
    private fun reject(name: String, json: String) = (v(name, json) as Validation.Reject).error
    private fun card(json: String) = show("display.card", json).panel.content as PanelContent.Card
    private fun stats(json: String) = show("display.stats", json).panel.content as PanelContent.Stats
    private fun list(json: String) = show("display.list", json).panel.content as PanelContent.ListBody
    private fun metrics(n: Int) = (1..n).joinToString(",") { """{"label":"L$it","value":$it}""" }

    @Test fun unknownCommand() = assertEquals("unknown command", reject("display.nope", "{}"))

    @Test fun badJson() {
        assertEquals("params: invalid JSON", reject("display.card", "{oops"))
        assertEquals("params: must be an object", reject("display.card", "[1]"))
    }

    @Test fun missingTitleRejected() {
        assertEquals("title: required", reject("display.card", """{"body":"x"}"""))
        assertEquals("title: required", reject("display.card", """{"title":"   "}"""))
        assertEquals("title: required", reject("display.stats", """{"metrics":[{"label":"a","value":1}]}"""))
        assertEquals("label: required", reject("display.timer", """{"seconds":5}"""))
    }

    @Test fun cardBasic() {
        val s = show("display.card", """{"title":"  Hello   world ","body":"Body","id":"my.id-1","sticky":true}""")
        assertEquals("Hello world", s.panel.title)
        assertEquals("my.id-1", s.panel.id)
        assertEquals(PanelType.CARD, s.panel.type)
        assertTrue(s.panel.sticky)
        assertFalse(s.truncated)
        assertTrue(s.ignored.isEmpty())
    }

    @Test fun idGeneratedWhenOmitted() = assertEquals("card-gen", show("display.card", """{"title":"t"}""").panel.id)

    @Test fun badIdRejected() {
        assertEquals("id: invalid", reject("display.card", """{"title":"t","id":"has space"}"""))
        assertEquals("id: invalid", reject("display.card", """{"title":"t","id":"${"a".repeat(41)}"}"""))
        assertEquals("id: invalid", reject("display.card", """{"title":"t","id":5}"""))
        assertEquals("id: invalid", reject("display.clear", """{"id":"a/b"}"""))
        show("display.card", """{"title":"t","id":"${"a".repeat(40)}"}""")
    }

    @Test fun unknownParamsIgnoredSorted() {
        val s = show("display.card", """{"title":"t","zeta":1,"alpha":2}""")
        assertEquals(listOf("alpha", "zeta"), s.ignored)
        assertEquals("""{"id":"card-gen","shown":true,"truncated":false,"ignored":["alpha","zeta"]}""",
            DisplayValidator.showPayload("card-gen", false, s.ignored))
        assertEquals("""{"id":"x","shown":true,"truncated":true}""", DisplayValidator.showPayload("x", true, emptyList()))
    }

    @Test fun timerSettingStickyIsIgnored() {
        val s = show("display.timer", """{"label":"Tea","seconds":10,"sticky":true}""")
        assertEquals(listOf("sticky"), s.ignored)
        assertFalse(s.panel.sticky)
    }

    @Test fun longTitleTruncatedWithEllipsis() {
        val s = show("display.card", """{"title":"${"x".repeat(100)}"}""")
        assertEquals(60, s.panel.title.length)
        assertTrue(s.panel.title.endsWith("…"))
        assertTrue(s.truncated)
    }

    @Test fun longLabelTruncated() {
        val s = show("display.stats", """{"title":"t","metrics":[{"label":"${"l".repeat(200)}","value":1}]}""")
        val m = (s.panel.content as PanelContent.Stats).metrics[0]
        assertEquals(16, m.label.length)
        assertTrue(s.truncated)
    }

    @Test fun markdownStrippedAndControlChars() {
        val s = show("display.card", "{\"title\":\"**Bold** # `code`\",\"body\":\"## Head\\u0007er  **x**\"}")
        assertEquals("Bold code", s.panel.title)
        assertEquals("Header x", (s.panel.content as PanelContent.Card).body)
    }

    @Test fun bodyBulletsExtracted() {
        val c = card("""{"title":"t","body":"Intro line\n- one\n* **two**\nOutro","bullets":["zero"]}""")
        assertEquals("Intro line\nOutro", c.body)
        assertEquals(listOf("zero", "one", "two"), c.bullets)
        assertEquals(0, c.moreBullets)
    }

    @Test fun bodyOnlyBulletsGivesNullBody() = assertNull(card("""{"title":"t","body":"- a\n- b"}""").body)

    @Test fun bodyKeepsNewlinesAndTruncates() {
        val c = card("""{"title":"t","body":"a\n\n\nb"}""")
        assertEquals("a\n\nb", c.body)
        val s = show("display.card", """{"title":"t","body":"${"b".repeat(700)}"}""")
        assertEquals(600, (s.panel.content as PanelContent.Card).body!!.length)
        assertTrue(s.truncated)
    }

    @Test fun bulletsShownSixRestMore() {
        val c = card("""{"title":"t","bullets":["1","2","3","4","5","6","7","8"]}""")
        assertEquals(6, c.bullets.size)
        assertEquals(2, c.moreBullets)
    }

    @Test fun bulletArrayLimits() {
        val twelve = (1..12).joinToString(",") { "\"b$it\"" }
        val s = show("display.card", """{"title":"t","bullets":[$twelve]}""")
        assertTrue(s.truncated)
        assertEquals(8, (s.panel.content as PanelContent.Card).let { it.bullets.size + it.moreBullets })
        val thirteen = (1..13).joinToString(",") { "\"b$it\"" }
        assertEquals("bullets: max 8 items", reject("display.card", """{"title":"t","bullets":[$thirteen]}"""))
    }

    @Test fun longBulletTruncated() {
        val s = show("display.card", """{"title":"t","bullets":["${"q".repeat(130)}"]}""")
        assertEquals(120, (s.panel.content as PanelContent.Card).bullets[0].length)
        assertTrue(s.truncated)
    }

    @Test fun sevenMetricsTruncatedTenRejected() {
        val s = show("display.stats", """{"title":"t","metrics":[${metrics(7)}]}""")
        assertTrue(s.truncated)
        assertEquals(6, (s.panel.content as PanelContent.Stats).metrics.size)
        assertEquals("metrics: max 6 items", reject("display.stats", """{"title":"t","metrics":[${metrics(10)}]}"""))
        assertTrue(show("display.stats", """{"title":"t","metrics":[${metrics(9)}]}""").truncated)
    }

    @Test fun metricsRequiredAndShape() {
        assertEquals("metrics: required", reject("display.stats", """{"title":"t"}"""))
        assertEquals("metrics: need 1-6 items", reject("display.stats", """{"title":"t","metrics":[]}"""))
        assertEquals("metrics[0].label: required", reject("display.stats", """{"title":"t","metrics":[{"value":1}]}"""))
        assertEquals("metrics[0].value: required string or number", reject("display.stats", """{"title":"t","metrics":[{"label":"a"}]}"""))
        assertEquals("metrics[0]: must be an object", reject("display.stats", """{"title":"t","metrics":["x"]}"""))
    }

    @Test fun numberFormatting() {
        val m = stats("""{"title":"t","metrics":[
            {"label":"a","value":12},{"label":"b","value":3.14159},{"label":"c","value":2.50},
            {"label":"d","value":1234567},{"label":"e","value":"14 SW"},{"label":"f","value":-0.5}]}""").metrics
        assertEquals(listOf("12", "3.14", "2.5", "1,234,567", "14 SW", "-0.5"), m.map { it.value })
    }

    @Test fun valueTooLongTruncated() {
        val s = show("display.stats", """{"title":"t","metrics":[{"label":"a","value":"12345678901234"}]}""")
        assertEquals(10, (s.panel.content as PanelContent.Stats).metrics[0].value.length)
        assertTrue(s.truncated)
    }

    @Test fun unitAttachment() {
        val m = stats("""{"title":"t","caption":"src","metrics":[
            {"label":"a","value":1,"unit":"%"},{"label":"b","value":1,"unit":"°"},{"label":"c","value":1,"unit":"°C"},
            {"label":"d","value":1,"unit":"°F"},{"label":"e","value":1,"unit":"km/h"},{"label":"f","value":1}]}""").metrics
        assertEquals(listOf(true, true, true, true, false, false), m.map { it.unitAttached })
        assertNull(m[5].unit)
    }

    @Test fun listItemsStringsAndObjects() {
        val l = list("""{"title":"t","ordered":true,"items":["a",{"text":"b","level":5,"done":true},{"text":"c","level":-3}]}""")
        assertTrue(l.ordered)
        assertEquals(listOf(0, 2, 0), l.items.map { it.level })
        assertEquals(listOf(false, true, false), l.items.map { it.done })
    }

    @Test fun listLimits() {
        fun items(n: Int) = (1..n).joinToString(",") { "\"i$it\"" }
        assertEquals(30, list("""{"title":"t","items":[${items(30)}]}""").items.size)
        val s = show("display.list", """{"title":"t","items":[${items(45)}]}""")
        assertTrue(s.truncated)
        assertEquals(30, (s.panel.content as PanelContent.ListBody).items.size)
        assertEquals("items: max 30 items", reject("display.list", """{"title":"t","items":[${items(46)}]}"""))
        assertEquals("items: required", reject("display.list", """{"title":"t"}"""))
        assertEquals("items[0].text: required", reject("display.list", """{"title":"t","items":[{"level":1}]}"""))
    }

    @Test fun listItemTruncated() {
        val s = show("display.list", """{"title":"t","items":["${"z".repeat(150)}"]}""")
        assertEquals(100, (s.panel.content as PanelContent.ListBody).items[0].text.length)
        assertTrue(s.truncated)
    }

    @Test fun timerValidation() {
        val s = show("display.timer", """{"label":"Tea","seconds":90}""")
        val t = s.panel.content as PanelContent.Timer
        assertEquals(90, t.totalSeconds)
        assertEquals(1000L + 90_000L, t.endsAtMs)
        assertEquals("Tea", s.panel.title)
        for (bad in listOf("0", "86401", "1.5", "\"5\"", "true")) {
            assertEquals("seconds: must be integer 1-86400", reject("display.timer", """{"label":"x","seconds":$bad}"""))
        }
        assertEquals("seconds: must be integer 1-86400", reject("display.timer", """{"label":"x"}"""))
        show("display.timer", """{"label":"x","seconds":86400}""")
        show("display.timer", """{"label":"x","seconds":60.0}""")
    }

    @Test fun clear() {
        assertEquals(Validation.Clear(null), v("display.clear", "{}"))
        assertEquals(Validation.Clear("abc"), v("display.clear", """{"id":"abc"}"""))
        assertEquals("""{"id":"abc","cleared":1}""", DisplayValidator.clearPayload("abc", 1))
    }

    @Test fun wrongTypes() {
        assertEquals("title: required", reject("display.card", """{"title":5}"""))
        assertEquals("body: must be string", reject("display.card", """{"title":"t","body":5}"""))
        assertEquals("sticky: must be boolean", reject("display.card", """{"title":"t","sticky":"yes"}"""))
        assertEquals("bullets: must be an array", reject("display.card", """{"title":"t","bullets":"a"}"""))
        assertEquals("bullets: items must be strings", reject("display.card", """{"title":"t","bullets":[1]}"""))
    }
}
