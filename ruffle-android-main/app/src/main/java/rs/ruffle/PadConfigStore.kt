package rs.ruffle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Per-game storage for the gamepad overlay layout and appearance. */
object PadConfigStore {
    class Config(
        val pads: List<GamepadOverlay.Pad>,
        val opacity: Float,
        val scale: Float,
        val visible: Boolean,
    )

    private val safeChars = Regex("[^A-Za-z0-9._-]")

    private fun configFile(context: Context, gameKey: String): File {
        val dir = File(context.filesDir, "gamepads")
        if (!dir.exists()) dir.mkdirs()
        val name = safeChars.replace(gameKey, "_").take(96).ifEmpty { "default" }
        return File(dir, "$name.json")
    }

    fun load(context: Context, gameKey: String): Config? {
        val file = configFile(context, gameKey)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val padsJson = json.optJSONArray("pads") ?: return null
            val pads = ArrayList<GamepadOverlay.Pad>()
            for (i in 0 until padsJson.length()) {
                val pad = padsJson.getJSONObject(i)
                pads.add(
                    GamepadOverlay.Pad(
                        pad.getString("tag"),
                        pad.getString("label"),
                        pad.getDouble("x").toFloat(),
                        pad.getDouble("y").toFloat(),
                        pad.getInt("size"),
                    )
                )
            }
            Config(
                pads,
                json.optDouble("opacity", 1.0).toFloat(),
                json.optDouble("scale", 1.0).toFloat(),
                json.optBoolean("visible", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, gameKey: String, config: Config) {
        val pads = JSONArray()
        for (pad in config.pads) {
            pads.put(
                JSONObject()
                    .put("tag", pad.tag)
                    .put("label", pad.label)
                    .put("x", pad.x.toDouble())
                    .put("y", pad.y.toDouble())
                    .put("size", pad.sizeDp)
            )
        }
        val json = JSONObject()
            .put("pads", pads)
            .put("opacity", config.opacity.toDouble())
            .put("scale", config.scale.toDouble())
            .put("visible", config.visible)
        try {
            configFile(context, gameKey).writeText(json.toString(2))
        } catch (e: Exception) {
            // A missing config only means the defaults come back next time.
        }
    }
}
