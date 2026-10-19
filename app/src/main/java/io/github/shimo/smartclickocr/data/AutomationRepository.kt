package io.github.shimo.smartclickocr.data

import android.content.Context
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import java.io.File
import java.util.UUID

/**
 * Persistence for all profiles, kept as one versioned JSON document in the app's
 * private files directory. Nothing ever leaves the device.
 *
 * Writes are atomic (temp file + rename) so a crash mid-save cannot corrupt data.
 */
class AutomationRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, "profiles.json")

    data class Snapshot(
        val profiles: List<AutomationProfile>,
        val selectedProfileId: String?,
        val floatingX: Int = -1,
        val floatingY: Int = -1
    )

    /** Reads everything, returning an empty snapshot for a fresh install. */
    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), null)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return Snapshot(emptyList(), null)
        return try {
            val decoded = ProfileCodec.decodeStore(text)
            Snapshot(decoded.profiles, decoded.selectedProfileId, decoded.floatingX, decoded.floatingY)
        } catch (e: ProfileCodec.ProfileFormatException) {
            // Corrupt store: keep a copy for the user and start fresh rather than crash.
            runCatching { file.copyTo(File(context.filesDir, "profiles.corrupt.json"), overwrite = true) }
            Snapshot(emptyList(), null)
        }
    }

    fun save(snapshot: Snapshot) {
        val json = ProfileCodec.encodeStore(
            snapshot.profiles, snapshot.selectedProfileId, snapshot.floatingX, snapshot.floatingY
        )
        val tmp = File(context.filesDir, "profiles.json.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** Creates the first-run example profile so the screen is never empty. */
    fun createDefaultSnapshot(): Snapshot {
        val profile = AutomationProfile(
            id = UUID.randomUUID().toString(),
            name = "示例流程",
            actions = listOf(
                AutomationAction(
                    type = ActionType.WAIT,
                    delayBeforeMs = 1000L,
                    waitDurationMs = 2000L
                )
            )
        )
        return Snapshot(listOf(profile), profile.id)
    }
}
