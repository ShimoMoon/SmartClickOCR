package io.github.shimo.smartclickocr.data

import io.github.shimo.smartclickocr.model.AutomationAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * One named set of actions, e.g. "每日签到".
 */
data class AutomationProfile(
    val id: String,
    val name: String,
    val actions: List<AutomationAction> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Versioned JSON (de)serialisation for profiles.
 *
 * The whole store is a single JSON document with a `schemaVersion`, so future
 * format changes can be migrated explicitly. Import validates everything and
 * throws [ProfileFormatException] instead of letting corrupt data reach the UI.
 */
object ProfileCodec {

    /** Bump when the on-disk shape changes, and add a migration path. */
    const val SCHEMA_VERSION = 1

    class ProfileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------- serialise

    fun encodeStore(
        profiles: List<AutomationProfile>,
        selectedProfileId: String?,
        floatingX: Int = -1,
        floatingY: Int = -1
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("selectedProfileId", selectedProfileId ?: JSONObject.NULL)
        root.put("floatingX", floatingX)
        root.put("floatingY", floatingY)
        val arr = JSONArray()
        profiles.forEach { arr.put(encodeProfile(it)) }
        root.put("profiles", arr)
        return root.toString(2)
    }

    private fun encodeProfile(profile: AutomationProfile): JSONObject {
        val o = JSONObject()
        o.put("id", profile.id)
        o.put("name", profile.name)
        o.put("updatedAt", profile.updatedAt)
        val arr = JSONArray()
        profile.actions.forEachIndexed { index, action ->
            arr.put(encodeAction(action, index))
        }
        o.put("actions", arr)
        return o
    }

    private fun encodeAction(action: AutomationAction, position: Int): JSONObject {
        val o = JSONObject()
        o.put("position", position)
        o.put("id", action.id)
        o.put("type", action.type.name)
        o.put("delayBeforeMs", action.delayBeforeMs)
        o.put("enabled", action.enabled)
        when (action.type) {
            io.github.shimo.smartclickocr.model.ActionType.CLICK -> {
                o.put("x", action.x.toDouble())
                o.put("y", action.y.toDouble())
            }
            io.github.shimo.smartclickocr.model.ActionType.SWIPE -> {
                o.put("x", action.x.toDouble())
                o.put("y", action.y.toDouble())
                o.put("endX", action.endX.toDouble())
                o.put("endY", action.endY.toDouble())
                o.put("durationMs", action.durationMs)
            }
            io.github.shimo.smartclickocr.model.ActionType.WAIT -> {
                o.put("waitDurationMs", action.waitDurationMs)
            }
            io.github.shimo.smartclickocr.model.ActionType.OCR_CHECK,
            io.github.shimo.smartclickocr.model.ActionType.STOP_IF -> {
                o.put("targetText", action.targetText)
                o.put("regionLeft", action.regionLeft)
                o.put("regionTop", action.regionTop)
                o.put("regionRight", action.regionRight)
                o.put("regionBottom", action.regionBottom)
            }
            io.github.shimo.smartclickocr.model.ActionType.BACK -> Unit
        }
        if (action.screenMeta.isValid) {
            val s = JSONObject()
            s.put("widthPx", action.screenMeta.widthPx)
            s.put("heightPx", action.screenMeta.heightPx)
            s.put("rotation", action.screenMeta.rotation)
            o.put("screen", s)
        }
        return o
    }

    // --------------------------------------------------------------- parse

    class DecodedStore(
        val profiles: List<AutomationProfile>,
        val selectedProfileId: String?,
        val floatingX: Int,
        val floatingY: Int
    )

    fun decodeStore(text: String): DecodedStore {
        if (text.isBlank()) return DecodedStore(emptyList(), null, -1, -1)
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ProfileFormatException("配置 JSON 格式损坏", e)
        }
        val version = root.optInt("schemaVersion", 0)
        if (version <= 0) throw ProfileFormatException("缺少 schemaVersion，无法识别配置")
        if (version > SCHEMA_VERSION) {
            throw ProfileFormatException("配置版本 $version 高于当前支持的 $SCHEMA_VERSION，请升级应用")
        }
        val arr = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = ArrayList<AutomationProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            decodeProfile(o)?.let { profiles.add(it) }
        }
        val selected = if (root.isNull("selectedProfileId")) null else root.optString("selectedProfileId").ifBlank { null }
        return DecodedStore(
            profiles = profiles,
            selectedProfileId = selected,
            floatingX = root.optInt("floatingX", -1),
            floatingY = root.optInt("floatingY", -1)
        )
    }

    private fun decodeProfile(o: JSONObject): AutomationProfile? {
        val id = o.optString("id").ifBlank { return null }
        val name = o.optString("name").ifBlank { "未命名方案" }
        val arr = o.optJSONArray("actions") ?: JSONArray()
        // Keep the stored order explicit via `position`.
        val ordered = (0 until arr.length())
            .mapNotNull { idx -> arr.optJSONObject(idx)?.let { it to it.optInt("position", idx) } }
            .sortedBy { it.second }
            .mapNotNull { (obj, _) -> decodeAction(obj) }
        return AutomationProfile(
            id = id,
            name = name,
            actions = ordered,
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }

    private fun decodeAction(o: JSONObject): AutomationAction? {
        val type = io.github.shimo.smartclickocr.model.ActionType.fromName(o.optString("type")) ?: return null
        val meta = o.optJSONObject("screen")?.let {
            io.github.shimo.smartclickocr.model.ScreenMeta(
                widthPx = it.optInt("widthPx", 0),
                heightPx = it.optInt("heightPx", 0),
                rotation = it.optInt("rotation", 0)
            )
        } ?: io.github.shimo.smartclickocr.model.ScreenMeta.EMPTY
        return AutomationAction(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            type = type,
            delayBeforeMs = o.optLong("delayBeforeMs", 1000L).coerceAtLeast(0L),
            enabled = o.optBoolean("enabled", true),
            x = o.optDouble("x", -1.0).toFloat(),
            y = o.optDouble("y", -1.0).toFloat(),
            endX = o.optDouble("endX", -1.0).toFloat(),
            endY = o.optDouble("endY", -1.0).toFloat(),
            durationMs = o.optLong("durationMs", 300L).coerceAtLeast(1L),
            waitDurationMs = o.optLong("waitDurationMs", 1000L).coerceAtLeast(0L),
            targetText = o.optString("targetText", ""),
            regionLeft = o.optInt("regionLeft", 0),
            regionTop = o.optInt("regionTop", 0),
            regionRight = o.optInt("regionRight", 0),
            regionBottom = o.optInt("regionBottom", 0),
            screenMeta = meta
        )
    }
}
