package io.github.shimo.smartclickocr.data

import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.ScreenMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip and corruption-resistance tests for the versioned profile store. */
class ProfileCodecTest {

    private fun sampleProfile(): AutomationProfile = AutomationProfile(
        id = "p1",
        name = "每日签到",
        actions = listOf(
            AutomationAction(
                id = "a1", type = ActionType.CLICK, delayBeforeMs = 1500, enabled = true,
                x = 120.5f, y = 340.25f, screenMeta = ScreenMeta(1080, 2400, 0)
            ),
            AutomationAction(
                id = "a2", type = ActionType.SWIPE, delayBeforeMs = 200, enabled = false,
                x = 10f, y = 20f, endX = 30f, endY = 40f, durationMs = 450
            ),
            AutomationAction(id = "a3", type = ActionType.BACK, delayBeforeMs = 0),
            AutomationAction(
                id = "a4", type = ActionType.WAIT, delayBeforeMs = 100, waitDurationMs = 2500
            ),
            AutomationAction(
                id = "a5", type = ActionType.OCR_CHECK, delayBeforeMs = 300,
                targetText = "任务完成",
                regionLeft = 11, regionTop = 22, regionRight = 333, regionBottom = 444,
                screenMeta = ScreenMeta(1080, 2400, 1)
            )
        )
    )

    @Test
    fun `round trip preserves every field and order`() {
        val profile = sampleProfile()
        val json = ProfileCodec.encodeStore(listOf(profile), profile.id)
        val decoded = ProfileCodec.decodeStore(json)

        assertEquals(1, decoded.profiles.size)
        assertEquals(profile.id, decoded.selectedProfileId)
        val restored = decoded.profiles[0]
        assertEquals(profile.name, restored.name)
        assertEquals(profile.actions.map { it.id }, restored.actions.map { it.id })
        assertEquals(profile.actions.map { it.type }, restored.actions.map { it.type })

        val click = restored.actions[0]
        assertEquals(1500L, click.delayBeforeMs)
        assertEquals(120.5f, click.x)
        assertEquals(340.25f, click.y)
        assertEquals(ScreenMeta(1080, 2400, 0), click.screenMeta)

        val swipe = restored.actions[1]
        assertEquals(200L, swipe.delayBeforeMs)
        assertEquals(false, swipe.enabled)
        assertEquals(30f, swipe.endX)
        assertEquals(40f, swipe.endY)
        assertEquals(450L, swipe.durationMs)

        val wait = restored.actions[3]
        assertEquals(2500L, wait.waitDurationMs)
        assertEquals(100L, wait.delayBeforeMs)

        val ocr = restored.actions[4]
        assertEquals("任务完成", ocr.targetText)
        assertEquals(11, ocr.regionLeft)
        assertEquals(444, ocr.regionBottom)
        assertEquals(ScreenMeta(1080, 2400, 1), ocr.screenMeta)
    }

    @Test
    fun `schemaVersion is written`() {
        val json = ProfileCodec.encodeStore(listOf(sampleProfile()), "p1")
        assertTrue("must declare schemaVersion", json.contains("\"schemaVersion\""))
        assertTrue(json.contains(ProfileCodec.SCHEMA_VERSION.toString()))
    }

    @Test
    fun `corrupt json is rejected instead of crashing`() {
        val e = runCatching { ProfileCodec.decodeStore("{ this is not json ") }.exceptionOrNull()
        assertTrue(e is ProfileCodec.ProfileFormatException)
    }

    @Test
    fun `missing schema version is rejected`() {
        val e = runCatching { ProfileCodec.decodeStore("""{"profiles":[]}""") }.exceptionOrNull()
        assertTrue(e is ProfileCodec.ProfileFormatException)
    }

    @Test
    fun `future schema version is rejected with a clear message`() {
        val e = runCatching {
            ProfileCodec.decodeStore("""{"schemaVersion":999,"profiles":[]}""")
        }.exceptionOrNull()
        assertTrue(e is ProfileCodec.ProfileFormatException)
        assertTrue(e!!.message!!.contains("999"))
    }

    @Test
    fun `negative delays and zero duration are clamped on load`() {
        val json = """
            {"schemaVersion":1,"selectedProfileId":"x","profiles":[
              {"id":"x","name":"bad","actions":[
                {"id":"a","type":"CLICK","delayBeforeMs":-500,"x":1,"y":2},
                {"id":"b","type":"SWIPE","durationMs":0,"x":0,"y":0,"endX":1,"endY":1}
              ]}
            ]}
        """.trimIndent()
        val decoded = ProfileCodec.decodeStore(json)
        val actions = decoded.profiles[0].actions
        assertEquals(0L, actions[0].delayBeforeMs)
        assertTrue("swipe duration must stay positive", actions[1].durationMs > 0)
    }

    @Test
    fun `unknown action types are skipped without losing the rest`() {
        val json = """
            {"schemaVersion":1,"profiles":[
              {"id":"x","name":"n","actions":[
                {"id":"a","type":"TELEPORT"},
                {"id":"b","type":"BACK","delayBeforeMs":10}
              ]}
            ]}
        """.trimIndent()
        val decoded = ProfileCodec.decodeStore(json)
        assertEquals(1, decoded.profiles[0].actions.size)
        assertEquals("b", decoded.profiles[0].actions[0].id)
    }

    @Test
    fun `position field defines ordering`() {
        val json = """
            {"schemaVersion":1,"profiles":[
              {"id":"x","name":"n","actions":[
                {"id":"second","type":"BACK","position":1},
                {"id":"first","type":"BACK","position":0}
              ]}
            ]}
        """.trimIndent()
        val decoded = ProfileCodec.decodeStore(json)
        assertEquals(listOf("first", "second"), decoded.profiles[0].actions.map { it.id })
    }

    @Test
    fun `empty store decodes to empty list`() {
        val decoded = ProfileCodec.decodeStore("")
        assertTrue(decoded.profiles.isEmpty())
        assertNull(decoded.selectedProfileId)
        assertNotNull(decoded)
    }

    @Test
    fun `normalized drops irrelevant parameters`() {
        val clickWithJunk = AutomationAction(
            type = ActionType.CLICK, x = 5f, y = 6f,
            targetText = "junk", regionLeft = 1, regionTop = 1, regionRight = 9, regionBottom = 9,
            endX = 3f, endY = 4f
        ).normalized()
        assertEquals("", clickWithJunk.targetText)
        assertNull(null as Int?)
        assertEquals(0, clickWithJunk.regionRight)
    }
}
