package com.mawj.editor.engine

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.model.Clip
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import com.mawj.editor.model.Transition
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

class BuildOptions(
    val canvasW: Int,
    val canvasH: Int,
    val forExport: Boolean,
    val enableSpeed: Boolean = true,
    val enableEffects: Boolean = true,
)

@OptIn(UnstableApi::class)
object CompositionFactory {

    /** Length of the transition half that plays at the boundary between [a] and [b]. */
    fun halfTransitionUs(a: Clip, b: Clip): Long {
        if (a.transition == Transition.NONE) return 0
        val max = min(a.timelineUs, b.timelineUs) / 2
        return min(a.transitionUs / 2, max)
    }

    fun build(context: Context, store: ProjectStore, project: Project, opt: BuildOptions): Composition {
        require(project.clips.isNotEmpty()) { "المشروع فاضي" }
        val clips = project.clips
        val items = ArrayList<EditedMediaItem>()
        var t = 0L
        clips.forEachIndexed { i, c ->
            val startUs = t
            val durUs = c.timelineUs
            t += durUs
            val uri = Uri.fromFile(store.mediaFile(project.id, c.file))

            val audioFx = ArrayList<AudioProcessor>()
            val videoFx = ArrayList<Effect>()

            val mediaItem = if (c.type == ClipType.IMAGE) {
                MediaItem.Builder().setUri(uri).setImageDurationMs(durUs / 1000).build()
            } else {
                MediaItem.Builder().setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(c.trimStartUs / 1000)
                            .setEndPositionMs(c.trimEndUs / 1000)
                            .build()
                    ).build()
            }

            if (c.type == ClipType.VIDEO && opt.enableSpeed && abs(c.speed - 1f) > 0.01f) {
                val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(c.speed))
                audioFx += pair.first
                videoFx += pair.second
            }
            if (c.type == ClipType.VIDEO) audioFx += GainAudioProcessor(c.volume)

            if (opt.enableEffects && (c.rotation != 0 || c.mirror)) {
                videoFx += ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(-c.rotation.toFloat())
                    .setScale(if (c.mirror) -1f else 1f, 1f)
                    .build()
            }
            videoFx += Presentation.createForWidthAndHeight(
                opt.canvasW, opt.canvasH,
                if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
            )
            if (opt.enableEffects) {
                if (abs(c.brightness) > 0.01f) videoFx += Brightness(c.brightness.coerceIn(-1f, 1f))
                if (abs(c.contrast) > 0.01f) videoFx += Contrast(c.contrast.coerceIn(-1f, 1f))
                if (abs(c.saturation) > 0.01f) {
                    videoFx += HslAdjustment.Builder().adjustSaturation(c.saturation.coerceIn(-1f, 1f) * 100f).build()
                }
                if (c.text.isNotBlank()) videoFx += textEffect(c, opt)

                val prev = clips.getOrNull(i - 1)
                val next = clips.getOrNull(i + 1)
                val plan = TransitionPlan(
                    head = prev?.transition ?: Transition.NONE,
                    headUs = if (prev != null) halfTransitionUs(prev, c) else 0,
                    tail = if (next != null) c.transition else Transition.NONE,
                    tailUs = if (next != null) halfTransitionUs(c, next) else 0,
                    clock = ClipClock(startUs, durUs, opt.forExport),
                )
                if (plan.active) {
                    videoFx += TransitionEffects.matrix(plan)
                    videoFx += TransitionEffects.color(plan)
                }
            }

            val b = EditedMediaItem.Builder(mediaItem).setEffects(Effects(audioFx, videoFx))
            if (c.type == ClipType.IMAGE) {
                b.setDurationUs(durUs).setFrameRate(30)
            } else {
                b.setDurationUs(c.sourceDurationUs)
            }
            items += b.build()
        }
        val totalUs = t

        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence(items)

        for (a in project.audios) {
            val start = a.startAtUs.coerceIn(0, totalUs)
            val len = min(a.lengthUs, totalUs - start)
            if (len < 100_000) continue
            val seq = ArrayList<EditedMediaItem>()
            if (start > 20_000) {
                seq += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(Silence.file(context, start))))
                    .setDurationUs(start)
                    .build()
            }
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(store.mediaFile(project.id, a.file)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(a.trimStartUs / 1000)
                        .setEndPositionMs((a.trimStartUs + len) / 1000)
                        .build()
                ).build()
            seq += EditedMediaItem.Builder(item)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf<AudioProcessor>(GainAudioProcessor(a.volume, a.fadeInUs, a.fadeOutUs, len)), emptyList()))
                .setDurationUs(a.sourceDurationUs)
                .build()
            sequences += EditedMediaItemSequence(seq)
        }

        val builder = Composition.Builder(sequences)
        if (opt.forExport) {
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            builder.experimentalSetForceAudioTrack(true)
        }
        return builder.build()
    }

    private fun textEffect(c: Clip, opt: BuildOptions): Effect {
        val px = (64f * c.textScale * opt.canvasW / 1080f).toInt().coerceAtLeast(8)
        val s = SpannableString(c.text)
        val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(ForegroundColorSpan(c.textColor), 0, s.length, flags)
        s.setSpan(AbsoluteSizeSpan(px), 0, s.length, flags)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, flags)
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, c.textPos.y)
            .build()
        return OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(s, settings)))
    }
}

@OptIn(UnstableApi::class)
class ConstantSpeed(private val speed: Float) : androidx.media3.common.audio.SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = androidx.media3.common.C.TIME_UNSET
}

/** Generates silent WAV files used to position audio tracks on the timeline. */
object Silence {
    private const val RATE = 22050

    fun file(context: Context, durationUs: Long): File {
        val ms = durationUs / 1000
        val dir = File(context.cacheDir, "silence").apply { mkdirs() }
        val f = File(dir, "s_$ms.wav")
        if (f.exists()) return f
        val samples = (RATE.toLong() * ms / 1000).toInt()
        val dataBytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val tmp = File(dir, "s_$ms.tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.write(header.array())
            raf.setLength(44L + dataBytes) // zero filled = silence
        }
        tmp.renameTo(f)
        return f
    }
}
