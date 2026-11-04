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

enum class Transition(private val ar: String, private val en: String, val overlap: Boolean = false) {
    NONE("بدون", "None"),
    DISSOLVE("دمج ناعم", "Dissolve", true),
    DISSOLVE_ZOOM("دمج بتقريب", "Zoom dissolve", true),
    SLIDE_OVER_LEFT("انزلاق ←", "Slide ←", true),
    SLIDE_OVER_UP("انزلاق ↑", "Slide ↑", true),
    FADE("تلاشي أسود", "Fade to black"),
    FLASH("وميض", "Flash"),
    ZOOM_IN("تقريب", "Zoom in"),
    ZOOM_OUT("تبعيد", "Zoom out"),
    SLIDE_LEFT("دفع ←", "Push ←"),
    SLIDE_RIGHT("دفع →", "Push →"),
    SLIDE_UP("دفع ↑", "Push ↑"),
    SLIDE_DOWN("دفع ↓", "Push ↓"),
    SPIN("دوران", "Spin"),
    GLITCH("جليتش", "Glitch"),
    SHAKE("اهتزاز", "Shake");

    val label: String get() = com.mawj.editor.L.t(ar, en)
}

enum class TextPos(private val ar: String, private val en: String, val y: Float) {
    TOP("أعلى", "Top", 0.7f), CENTER("وسط", "Center", 0f), BOTTOM("أسفل", "Bottom", -0.7f);

    val label: String get() = com.mawj.editor.L.t(ar, en)
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
    /** Color grade preset id (see ColorGrade) and its strength 0..1. */
    var grade: String = "",
    var gradeAmount: Float = 1f,
    var temperature: Float = 0f,
    var tint: Float = 0f,
    /** Detail / sharpening 0..1. */
    var sharpen: Float = 0f,
    /** Animations: in, out and loop preset ids ("" = none). */
    var animIn: String = "",
    var animOut: String = "",
    var animLoop: String = "",
    /** Smart reframe focus point 0..1 (-1 = centered). Used when the clip fills the frame. */
    var focusX: Float = -1f,
    var focusY: Float = -1f,
    /** Display size of the source (after its own rotation metadata). */
    var srcW: Int = 0,
    var srcH: Int = 0,
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
        put("grade", grade); put("gamt", gradeAmount.toDouble()); put("temp", temperature.toDouble()); put("tint", tint.toDouble())
        put("sharp", sharpen.toDouble()); put("ain", animIn); put("aout", animOut); put("aloop", animLoop)
        put("fx", focusX.toDouble()); put("fy", focusY.toDouble()); put("sw", srcW); put("sh", srcH)
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
                grade = o.optString("grade", ""),
                gradeAmount = o.optDouble("gamt", 1.0).toFloat(),
                temperature = o.optDouble("temp", 0.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(),
                sharpen = o.optDouble("sharp", 0.0).toFloat(),
                animIn = o.optString("ain", ""),
                animOut = o.optString("aout", ""),
                animLoop = o.optString("aloop", ""),
                focusX = o.optDouble("fx", -1.0).toFloat(),
                focusY = o.optDouble("fy", -1.0).toFloat(),
                srcW = o.optInt("sw", 0),
                srcH = o.optInt("sh", 0),
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
    /** Detected beats, in source time (µs). */
    var beats: List<Long> = emptyList(),
) {
    val lengthUs: Long get() = (trimEndUs - trimStartUs).coerceAtLeast(1)

    fun toJson() = JSONObject().apply {
        put("id", id); put("file", file); put("label", label); put("src", sourceDurationUs)
        put("ts", trimStartUs); put("te", trimEndUs); put("at", startAtUs)
        put("vol", volume.toDouble()); put("fi", fadeInUs); put("fo", fadeOutUs)
        put("beats", JSONArray().apply { beats.forEach { put(it) } })
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
                beats = o.optJSONArray("beats")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
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
