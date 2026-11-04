package com.mawj.editor.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ClipType { VIDEO, IMAGE }

enum class Aspect(val w: Int, val h: Int, val label: String) {
    R9_16(9, 16, "9:16"),
    R16_9(16, 9, "16:9"),
    R1_1(1, 1, "1:1"),
    R4_5(4, 5, "4:5"),
    R3_4(3, 4, "3:4");

    /** Canvas size whose short side equals [shortSide] (even numbers). */
    fun canvas(shortSide: Int): Pair<Int, Int> {
        val s = minOf(w, h).toFloat()
        val cw = (shortSide * w / s).toInt() / 2 * 2
        val ch = (shortSide * h / s).toInt() / 2 * 2
        return cw to ch
    }
}

/** Runtime capabilities learned on the device (overlapping transitions need multi-track compositing). */
object Caps {
    @Volatile var overlapSupported = true
}

enum class Transition(val label: String, val overlap: Boolean = false) {
    NONE("بدون"),
    DISSOLVE("دمج ناعم", true),
    DISSOLVE_ZOOM("دمج بتقريب", true),
    SLIDE_OVER_LEFT("انزلاق ←", true),
    SLIDE_OVER_UP("انزلاق ↑", true),
    FADE("تلاشي أسود"),
    FLASH("وميض"),
    ZOOM_IN("تقريب"),
    ZOOM_OUT("تبعيد"),
    SLIDE_LEFT("دفع ←"),
    SLIDE_RIGHT("دفع →"),
    SLIDE_UP("دفع ↑"),
    SLIDE_DOWN("دفع ↓"),
    SPIN("دوران"),
    GLITCH("جليتش"),
    SHAKE("اهتزاز"),
}

enum class TextPos(val label: String, val y: Float) {
    TOP("أعلى", 0.7f), CENTER("وسط", 0f), BOTTOM("أسفل", -0.7f)
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 12)

data class Clip(
    var id: String = newId(),
    var file: String,
    var type: ClipType,
    var sourceDurationUs: Long,
    var trimStartUs: Long = 0,
    var trimEndUs: Long = sourceDurationUs,
    var speed: Float = 1f,
    var volume: Float = 1f,
    var transition: Transition = Transition.NONE,
    var transitionUs: Long = 600_000,
    var brightness: Float = 0f,
    var contrast: Float = 0f,
    var saturation: Float = 0f,
    var rotation: Int = 0,
    var mirror: Boolean = false,
    var fill: Boolean = false,
    var text: String = "",
    var textPos: TextPos = TextPos.BOTTOM,
    var textColor: Int = 0xFFFFFFFF.toInt(),
    var textScale: Float = 1f,
    /** Font file (inside the project's media folder) for the on-video text; empty = default. */
    var textFont: String = "",
) {
    /** Duration of this clip on the output timeline. */
    val timelineUs: Long get() = ((trimEndUs - trimStartUs) / speed).toLong().coerceAtLeast(1)

    fun toJson() = JSONObject().apply {
        put("id", id); put("file", file); put("type", type.name)
        put("src", sourceDurationUs); put("ts", trimStartUs); put("te", trimEndUs)
        put("speed", speed.toDouble()); put("vol", volume.toDouble())
        put("tr", transition.name); put("trUs", transitionUs)
        put("br", brightness.toDouble()); put("co", contrast.toDouble()); put("sa", saturation.toDouble())
        put("rot", rotation); put("mir", mirror); put("fill", fill)
        put("text", text); put("tpos", textPos.name); put("tcol", textColor); put("tsc", textScale.toDouble()); put("tfont", textFont)
    }

    companion object {
        fun fromJson(o: JSONObject): Clip {
            val src = o.getLong("src")
            return Clip(
                id = o.optString("id", newId()),
                file = o.getString("file"),
                type = runCatching { ClipType.valueOf(o.getString("type")) }.getOrDefault(ClipType.VIDEO),
                sourceDurationUs = src,
                trimStartUs = o.optLong("ts", 0),
                trimEndUs = o.optLong("te", src),
                speed = o.optDouble("speed", 1.0).toFloat(),
                volume = o.optDouble("vol", 1.0).toFloat(),
                transition = runCatching { Transition.valueOf(o.optString("tr")) }.getOrDefault(Transition.NONE),
                transitionUs = o.optLong("trUs", 600_000),
                brightness = o.optDouble("br", 0.0).toFloat(),
                contrast = o.optDouble("co", 0.0).toFloat(),
                saturation = o.optDouble("sa", 0.0).toFloat(),
                rotation = o.optInt("rot", 0),
                mirror = o.optBoolean("mir", false),
                fill = o.optBoolean("fill", false),
                text = o.optString("text", ""),
                textPos = runCatching { TextPos.valueOf(o.optString("tpos")) }.getOrDefault(TextPos.BOTTOM),
                textColor = o.optInt("tcol", 0xFFFFFFFF.toInt()),
                textScale = o.optDouble("tsc", 1.0).toFloat(),
                textFont = o.optString("tfont", ""),
            )
        }
    }
}

data class AudioClip(
    var id: String = newId(),
    var file: String,
    var label: String,
    var sourceDurationUs: Long,
    var trimStartUs: Long = 0,
    var trimEndUs: Long = sourceDurationUs,
    var startAtUs: Long = 0,
    var volume: Float = 1f,
    var fadeInUs: Long = 0,
    var fadeOutUs: Long = 0,
) {
    val lengthUs: Long get() = (trimEndUs - trimStartUs).coerceAtLeast(1)

    fun toJson() = JSONObject().apply {
        put("id", id); put("file", file); put("label", label); put("src", sourceDurationUs)
        put("ts", trimStartUs); put("te", trimEndUs); put("at", startAtUs)
        put("vol", volume.toDouble()); put("fi", fadeInUs); put("fo", fadeOutUs)
    }

    companion object {
        fun fromJson(o: JSONObject): AudioClip {
            val src = o.getLong("src")
            return AudioClip(
                id = o.optString("id", newId()),
                file = o.getString("file"),
                label = o.optString("label", "صوت"),
                sourceDurationUs = src,
                trimStartUs = o.optLong("ts", 0),
                trimEndUs = o.optLong("te", src),
                startAtUs = o.optLong("at", 0),
                volume = o.optDouble("vol", 1.0).toFloat(),
                fadeInUs = o.optLong("fi", 0),
                fadeOutUs = o.optLong("fo", 0),
            )
        }
    }
}

data class Project(
    var id: String = newId(),
    var name: String,
    var aspect: Aspect = Aspect.R9_16,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val clips: MutableList<Clip> = mutableListOf(),
    val audios: MutableList<AudioClip> = mutableListOf(),
) {
    /** How long clip [k] and clip [k + 1] play on top of each other (overlapping transitions). */
    fun overlapUs(k: Int): Long {
        val a = clips.getOrNull(k) ?: return 0
        val b = clips.getOrNull(k + 1) ?: return 0
        if (!a.transition.overlap || !Caps.overlapSupported) return 0
        val cap = (minOf(a.timelineUs, b.timelineUs) - 200_000) / 2
        val v = minOf(a.transitionUs, cap)
        return if (v < 100_000) 0 else v
    }

    val durationUs: Long get() = clips.indices.sumOf { clips[it].timelineUs - overlapUs(it) }

    fun clipStartUs(index: Int): Long = (0 until index).sumOf { clips[it].timelineUs - overlapUs(it) }

    /** Index of the clip under [timeUs] (the later clip wins inside an overlap), or -1. */
    fun clipAt(timeUs: Long): Int {
        if (clips.isEmpty()) return -1
        var found = 0
        var t = 0L
        clips.forEachIndexed { i, c ->
            if (timeUs >= t) found = i
            t += c.timelineUs - overlapUs(i)
        }
        return found
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", id); put("name", name); put("aspect", aspect.name)
        put("created", createdAt); put("updated", updatedAt)
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        put("audios", JSONArray().apply { audios.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Project {
            val p = Project(
                id = o.getString("id"),
                name = o.optString("name", "مشروع"),
                aspect = runCatching { Aspect.valueOf(o.optString("aspect")) }.getOrDefault(Aspect.R9_16),
                createdAt = o.optLong("created"),
                updatedAt = o.optLong("updated"),
            )
            o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) p.clips += Clip.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("audios")?.let { a -> for (i in 0 until a.length()) p.audios += AudioClip.fromJson(a.getJSONObject(i)) }
            return p
        }
    }
}
