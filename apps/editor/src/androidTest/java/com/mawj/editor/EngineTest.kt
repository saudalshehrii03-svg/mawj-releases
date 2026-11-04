package com.mawj.editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mawj.editor.engine.Bridges
import com.mawj.editor.engine.BuildOptions
import com.mawj.editor.engine.CompositionFactory
import com.mawj.editor.engine.MediaJobs
import com.mawj.editor.engine.Silence
import com.mawj.editor.model.Clip
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Transition
import com.mawj.editor.ui.EditorActivity
import com.mawj.editor.ui.TimelineView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** End-to-end checks of the real engine on an emulator: sample media, preview playback, transitions, export. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EngineTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "MawjTest"

    /** Creates a 3 s 640x360 MP4 (video from a picture + silent audio track). */
    private fun sampleVideo(name: String, color: Int): File = runBlocking {
        val dir = File(ctx.cacheDir, "test").apply { mkdirs() }
        val png = File(dir, "$name.png")
        val bm = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(bm).apply {
            drawColor(color)
            drawCircle(320f, 180f, 120f, Paint().apply { setColor(Color.WHITE) })
        }
        png.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = File(dir, "$name.mp4").apply { delete() }
        withContext(Dispatchers.Main) {
            val img = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(png)).setImageDurationMs(3000).build())
                .setDurationUs(3_000_000).setFrameRate(30).build()
            val silence = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(Silence.file(ctx, 3_000_000)))).setDurationUs(3_000_000).build()
            val comp = Composition.Builder(listOf(EditedMediaItemSequence(listOf(img)), EditedMediaItemSequence(listOf(silence)))).build()
            MediaJobs.exportVideo(ctx, comp, 2_000_000, out) {}
        }
        assertTrue("sample video not created", out.length() > 0)
        out
    }

    private fun makeProject(transition: Transition): String {
        val app = ctx.applicationContext as MawjApp
        val store = app.store
        val p = store.create("test")
        listOf("a" to Color.rgb(200, 40, 60), "b" to Color.rgb(30, 90, 200)).forEach { (n, c) ->
            val src = sampleVideo(n, c)
            val name = "$n.mp4"
            src.copyTo(store.mediaFile(p.id, name), overwrite = true)
            p.clips += Clip(file = name, type = ClipType.VIDEO, sourceDurationUs = 3_000_000, srcW = 640, srcH = 360)
        }
        p.clips[0].transition = transition
        p.clips[0].transitionUs = 800_000
        store.save(p)
        return p.id
    }

    @Test
    fun previewPlays() {
        val id = makeProject(Transition.NONE)
        val intent = Intent(ctx, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, id)
        ActivityScenario.launch<EditorActivity>(intent).use { sc ->
            Thread.sleep(6000)
            var before = 0L
            sc.onActivity { a ->
                before = a.findViewById<TimelineView>(R.id.timeline).positionUs
                a.findViewById<android.view.View>(R.id.playButton).performClick()
            }
            Thread.sleep(2500)
            var after = 0L
            sc.onActivity { a -> after = a.findViewById<TimelineView>(R.id.timeline).positionUs }
            Log.i(tag, "preview position before=$before after=$after")
            assertTrue("preview did not advance (before=$before after=$after)", after - before > 500_000)
        }
    }

    @Test
    fun seekThenPlayContinuesFromPlayhead() {
        val id = makeProject(Transition.NONE)
        val intent = Intent(ctx, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, id)
        ActivityScenario.launch<EditorActivity>(intent).use { sc ->
            Thread.sleep(6000)
            sc.onActivity { a ->
                val tl = a.findViewById<TimelineView>(R.id.timeline)
                tl.setPosition(4_000_000)
                (a as TimelineView.Listener).onScrub(4_000_000)
                a.onScrubEnd()
            }
            Thread.sleep(800)
            sc.onActivity { a -> a.findViewById<android.view.View>(R.id.playButton).performClick() }
            Thread.sleep(1500)
            var pos = 0L
            sc.onActivity { a -> pos = a.findViewById<TimelineView>(R.id.timeline).positionUs }
            Log.i(tag, "after seek+play position=$pos")
            assertTrue("playback did not continue from 4s (pos=$pos)", pos > 4_200_000)
        }
    }

    @Test
    fun overlapBridgeRenders() {
        val id = makeProject(Transition.DISSOLVE)
        val store = (ctx.applicationContext as MawjApp).store
        val p = store.load(id)
        val f = runBlocking(Dispatchers.Main) { Bridges.render(ctx, store, p, 0, 540, 960, false, 4_000_000) }
        Log.i(tag, "bridge=${f?.length()} err=${Bridges.lastError}")
        assertTrue("bridge failed: ${Bridges.lastError}", f != null && f.length() > 0)
    }

    @Test
    fun exportWorks() {
        val id = makeProject(Transition.DISSOLVE)
        val store = (ctx.applicationContext as MawjApp).store
        val p = store.load(id)
        val out = File(ctx.cacheDir, "test_export.mp4").apply { delete() }
        val err = runCatching {
            runBlocking(Dispatchers.Main) {
                val bridges = HashMap<Int, File>()
                Bridges.render(ctx, store, p, 0, 720, 1280, true, 6_000_000)?.let { bridges[0] = it }
                val comp = CompositionFactory.build(ctx, store, p, BuildOptions(720, 1280, true, bridges = bridges, sharpen = 0.2f))
                MediaJobs.exportVideo(ctx, comp, 6_000_000, out) {}
            }
        }.exceptionOrNull()
        Log.i(tag, "export size=${out.length()} err=$err")
        assertTrue("export failed: ${err?.let { generateSequence(it) { c -> c.cause }.mapNotNull { c -> c.message }.joinToString(" <- ") }}", err == null && out.length() > 0)
    }
}
