package com.mawj.editor.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mawj.editor.MawjApp
import com.mawj.editor.R
import com.mawj.editor.databinding.ActivityEditorBinding
import com.mawj.editor.engine.BuildOptions
import com.mawj.editor.engine.CompositionFactory
import com.mawj.editor.engine.MediaJobs
import com.mawj.editor.model.Aspect
import com.mawj.editor.model.AudioClip
import com.mawj.editor.model.Clip
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import com.mawj.editor.model.TextPos
import com.mawj.editor.model.Transition
import com.mawj.editor.model.newId
import com.mawj.editor.data.ProjectStore
import com.mawj.editor.sync.DriveSync
import com.mawj.editor.sync.GoogleAuth
import com.mawj.editor.sync.SyncPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity(), TimelineView.Listener {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_PICK = "pick"
    }

    private lateinit var b: ActivityEditorBinding
    private val store: ProjectStore by lazy { (application as MawjApp).store }
    private lateinit var project: Project

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private var selClip = -1
    private var selAudio = -1

    private var player: CompositionPlayer? = null
    private var previewTier = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastSeekAt = 0L
    private var exportJob: Job? = null

    private val thumbs = LruCache<String, Bitmap>(80)
    private val thumbLoading = HashSet<String>()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) importClips(uris)
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_ID)
        project = runCatching { store.load(id!!) }.getOrElse {
            Toast.makeText(this, "تعذّر فتح المشروع", Toast.LENGTH_SHORT).show(); finish(); return
        }

        b.title.text = project.name
        b.title.setOnClickListener { renameProject() }
        b.backButton.setOnClickListener { leave() }
        b.undoButton.setOnClickListener { undo() }
        b.redoButton.setOnClickListener { redo() }
        b.exportButton.setOnClickListener { showExport() }
        b.playButton.setOnClickListener { togglePlay() }
        b.timeline.listener = this
        b.timeline.thumbProvider = { key -> thumbFor(key) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        refreshAll()
        rebuildPreview()
        if (intent.getBooleanExtra(EXTRA_PICK, false) && savedInstanceState == null && project.clips.isEmpty()) {
            launchMediaPicker()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        store.save(project)
    }

    override fun onStop() {
        super.onStop()
        autoBackup()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (isFinishing && ::project.isInitialized) runCatching { store.cleanupMedia(project) }
        super.onDestroy()
    }

    private fun leave() {
        store.save(project)
        finish()
    }

    // ---------- Edit plumbing ----------

    private fun snapshot() = project.toJson().toString()

    private fun restore(json: String) {
        val p = Project.fromJson(JSONObject(json))
        project.clips.clear(); project.clips += p.clips
        project.audios.clear(); project.audios += p.audios
        project.aspect = p.aspect
        project.name = p.name
    }

    /** Applies a change with undo support, then saves and refreshes everything. */
    private inline fun edit(rebuild: Boolean = true, block: () -> Unit) {
        undoStack.addLast(snapshot())
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        block()
        commit(rebuild)
    }

    private fun commit(rebuild: Boolean = true) {
        store.save(project)
        if (selClip >= project.clips.size) selClip = -1
        if (selAudio >= project.audios.size) selAudio = -1
        refreshAll()
        if (rebuild) schedulePreview()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return toast("ما فيه شي للتراجع")
        redoStack.addLast(snapshot())
        restore(prev)
        commit()
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
        commit()
    }

    private fun refreshAll() {
        b.title.text = project.name
        b.undoButton.alpha = if (undoStack.isEmpty()) 0.35f else 1f
        b.redoButton.alpha = if (redoStack.isEmpty()) 0.35f else 1f
        b.previewHint.visibility = if (project.clips.isEmpty()) View.VISIBLE else View.GONE
        b.exportButton.isEnabled = project.clips.isNotEmpty()

        val v = project.clips.map {
            TimelineView.VBlock(0, it.timelineUs, thumbKey(it), it.type == ClipType.IMAGE, it.transition, it.text.isNotBlank())
        }
        val a = project.audios.map { TimelineView.ABlock(it.startAtUs, it.lengthUs, it.label) }
        b.timeline.setData(v, a)
        b.timeline.selectedClip = selClip
        b.timeline.selectedAudio = selAudio
        updateTime()
        buildTools()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Preview ----------

    private val previewRunnable = Runnable { rebuildPreview() }

    private fun schedulePreview() {
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 300)
    }

    private fun rebuildPreview() {
        val wasPlaying = player?.isPlaying == true
        player?.release()
        player = null
        b.playerView.player = null
        if (project.clips.isEmpty()) { updatePlayIcon(); return }

        val (w, h) = project.aspect.canvas(720)
        val composition = try {
            CompositionFactory.build(this, store, project, BuildOptions(w, h, false, previewTier < 1, previewTier < 2))
        } catch (e: Exception) {
            toast("تعذّر تجهيز المعاينة: ${e.message}")
            return
        }
        val p = try {
            CompositionPlayer.Builder(this).build()
        } catch (e: Exception) {
            toast("المعاينة غير مدعومة على هذا الجهاز")
            return
        }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon()
                handler.removeCallbacks(positionLoop)
                if (isPlaying) handler.post(positionLoop)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    updatePlayIcon()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (previewTier < 2) {
                    previewTier++
                    toast(if (previewTier == 1) "المعاينة بدون السرعة (التصدير يطبقها)" else "معاينة مبسّطة (التصدير يطبق كل التأثيرات)")
                    schedulePreview()
                } else {
                    toast("تعذّر تشغيل المعاينة: ${error.errorCodeName}")
                }
            }
        })
        b.playerView.player = p
        p.setComposition(composition)
        p.prepare()
        p.seekTo(b.timeline.positionUs / 1000)
        player = p
        if (wasPlaying) p.play()
        updatePlayIcon()
    }

    private val positionLoop = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!b.timeline.isUserScrubbing) {
                b.timeline.setPosition(p.currentPosition * 1000)
                updateTime()
            }
            if (p.isPlaying) handler.postDelayed(this, 33)
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (b.timeline.positionUs >= project.durationUs - 50_000) {
                p.seekTo(0)
                b.timeline.setPosition(0)
            }
            p.play()
        }
    }

    private fun updatePlayIcon() {
        b.playButton.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateTime() {
        b.timeText.text = fmt(b.timeline.positionUs) + " / " + fmt(project.durationUs)
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000
        val cs = (us / 10_000) % 100
        return String.format(Locale.US, "%02d:%02d.%02d", s / 60, s % 60, cs)
    }

    // ---------- Timeline callbacks ----------

    override fun onScrub(us: Long) {
        val p = player
        if (p != null) {
            if (p.isPlaying) p.pause()
            val now = System.currentTimeMillis()
            if (now - lastSeekAt > 60) { p.seekTo(us / 1000); lastSeekAt = now }
        }
        updateTime()
    }

    override fun onScrubEnd() {
        player?.seekTo(b.timeline.positionUs / 1000)
        updateTime()
    }

    override fun onClipTap(index: Int) {
        selClip = if (selClip == index) -1 else index
        selAudio = -1
        b.timeline.selectedClip = selClip
        b.timeline.selectedAudio = -1
        buildTools()
    }

    override fun onAudioTap(index: Int) {
        selAudio = if (selAudio == index) -1 else index
        selClip = -1
        b.timeline.selectedClip = -1
        b.timeline.selectedAudio = selAudio
        buildTools()
    }

    override fun onTransitionTap(index: Int) = showTransition(index)

    override fun onEmptyTap() {
        selClip = -1; selAudio = -1
        b.timeline.selectedClip = -1; b.timeline.selectedAudio = -1
        buildTools()
    }

    override fun onAudioMoved(index: Int, newStartUs: Long) {
        val a = project.audios.getOrNull(index) ?: return
        if (abs(a.startAtUs - newStartUs) < 10_000) { refreshAll(); return }
        edit { a.startAtUs = newStartUs }
        selAudio = index
        b.timeline.selectedAudio = index
        buildTools()
    }

    // ---------- Tools ----------

    private fun tool(icon: String, label: String, onClick: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = getDrawable(android.R.drawable.list_selector_background)
            isClickable = true
            setOnClickListener { onClick() }
        }
        item.addView(TextView(this).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER })
        item.addView(TextView(this).apply {
            text = label; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(0xFFDADAE5.toInt()); maxLines = 1
        })
        b.tools.addView(item, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun buildTools() {
        b.tools.removeAllViews()
        val clip = project.clips.getOrNull(selClip)
        val audio = project.audios.getOrNull(selAudio)
        when {
            clip != null -> {
                tool("✖", "إلغاء التحديد") { onEmptyTap() }
                tool("✂️", "تقسيم") { splitClip() }
                if (clip.type == ClipType.VIDEO) {
                    tool("↔️", "قص") { trimClip(clip) }
                    tool("⏩", "السرعة") { speedClip(clip) }
                    tool("🔊", "الصوت") { volumeClip(clip) }
                    tool("🎵", "استخراج الصوت") { extractAudio(clip) }
                } else {
                    tool("⏱️", "المدة") { imageDuration(clip) }
                }
                tool("🎨", "فلاتر") { filters(clip) }
                tool("🔤", "نص") { textClip(clip) }
                tool("🔄", "تدوير") { edit { clip.rotation = (clip.rotation + 90) % 360 } }
                tool("🪞", "قلب") { edit { clip.mirror = !clip.mirror } }
                tool("⛶", if (clip.fill) "احتواء" else "ملء الشاشة") { edit { clip.fill = !clip.fill } }
                if (selClip < project.clips.lastIndex) tool("✨", "انتقال") { showTransition(selClip) }
                tool("◀", "تحريك") { moveClip(-1) }
                tool("▶", "تحريك") { moveClip(1) }
                tool("📑", "تكرار") { duplicateClip() }
                tool("🗑️", "حذف") { deleteClip() }
            }
            audio != null -> {
                tool("✖", "إلغاء التحديد") { onEmptyTap() }
                tool("🔊", "الصوت") { volumeAudio(audio) }
                tool("🌗", "تلاشي") { fadeAudio(audio) }
                tool("✂️", "تقسيم") { splitAudio() }
                tool("↔️", "قص") { trimAudio(audio) }
                tool("📍", "نقل هنا") { edit { audio.startAtUs = b.timeline.positionUs } }
                tool("📑", "تكرار") { duplicateAudio(audio) }
                tool("🗑️", "حذف") { edit { project.audios.removeAt(selAudio); selAudio = -1 } }
            }
            else -> {
                tool("🎬", "إضافة مقطع") { launchMediaPicker() }
                tool("🎵", "إضافة صوت") { pickAudio.launch(arrayOf("audio/*")) }
                tool("📐", "النسبة") { aspectSheet() }
                if (project.clips.size > 1) tool("✨", "انتقال للكل") { showTransition(-1) }
                tool("✂️", "تقسيم") { splitClip() }
            }
        }
    }

    private fun launchMediaPicker() =
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    // ---------- Import ----------

    private fun busyDialog(title: String): Pair<AlertDialog, LinearProgressIndicator> {
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(12), dp(24), dp(8)); addView(bar, LinearLayout.LayoutParams(-1, -2)) }
        val d = MaterialAlertDialogBuilder(this).setTitle(title).setView(box).setCancelable(false).show()
        return d to bar
    }

    private fun importClips(uris: List<Uri>) {
        val (dialog, _) = busyDialog("جاري إضافة ${uris.size} ملف…")
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { store.importMedia(project.id, it) }.getOrNull() }
            }
            dialog.dismiss()
            if (imported.isEmpty()) return@launch toast("ما قدرنا نضيف الملفات")
            edit {
                var insertAt = if (selClip >= 0) selClip + 1 else project.clips.size
                imported.forEach { m ->
                    when (m.kind) {
                        ProjectStore.Imported.Kind.AUDIO -> project.audios += AudioClip(
                            file = m.file, label = m.label, sourceDurationUs = m.durationUs, startAtUs = b.timeline.positionUs,
                        )
                        ProjectStore.Imported.Kind.IMAGE -> project.clips.add(insertAt++, Clip(file = m.file, type = ClipType.IMAGE, sourceDurationUs = m.durationUs))
                        ProjectStore.Imported.Kind.VIDEO -> project.clips.add(insertAt++, Clip(file = m.file, type = ClipType.VIDEO, sourceDurationUs = m.durationUs))
                    }
                }
            }
            if (imported.size < uris.size) toast("بعض الملفات ما انضافت (صيغة غير مدعومة)")
        }
    }

    private fun importAudio(uri: Uri) {
        val (dialog, _) = busyDialog("جاري إضافة الصوت…")
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { runCatching { store.importMedia(project.id, uri) }.getOrNull() }
            dialog.dismiss()
            if (m == null) return@launch toast("ملف صوت غير مدعوم")
            if (project.clips.isEmpty()) return@launch toast("أضف مقطع فيديو أو صورة أول")
            edit {
                project.audios += AudioClip(
                    file = m.file, label = m.label, sourceDurationUs = m.durationUs,
                    startAtUs = b.timeline.positionUs.coerceAtMost(project.durationUs - 100_000).coerceAtLeast(0),
                )
                selAudio = project.audios.lastIndex
                selClip = -1
            }
        }
    }

    private fun extractAudio(clip: Clip) {
        if (abs(clip.speed - 1f) > 0.01f) return toast("رجّع السرعة 1x قبل استخراج الصوت")
        val src = store.mediaFile(project.id, clip.file)
        if (!MediaJobs.hasAudio(src)) return toast("هذا المقطع ما فيه صوت")
        val (dialog, _) = busyDialog("جاري استخراج الصوت…")
        val name = newId() + ".m4a"
        val out = store.mediaFile(project.id, name)
        lifecycleScope.launch {
            try {
                MediaJobs.extractAudio(this@EditorActivity, src, out) {}
                val durUs = withContext(Dispatchers.IO) {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(out.absolutePath); (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000 }
                    finally { r.release() }
                }
                dialog.dismiss()
                val idx = project.clips.indexOf(clip)
                edit {
                    val start = project.clipStartUs(idx)
                    project.audios += AudioClip(
                        file = name, label = "صوت المقطع ${idx + 1}", sourceDurationUs = durUs.coerceAtLeast(clip.trimEndUs),
                        trimStartUs = clip.trimStartUs, trimEndUs = clip.trimEndUs.coerceAtMost(durUs.coerceAtLeast(clip.trimEndUs)),
                        startAtUs = start,
                    )
                    clip.volume = 0f
                }
                toast("تم ✅ الصوت صار مسار منفصل، وانكتم صوت المقطع")
            } catch (e: Exception) {
                dialog.dismiss()
                out.delete()
                toast("تعذّر الاستخراج: ${e.message}")
            }
        }
    }

    // ---------- Clip tools ----------

    private fun splitClip() {
        val pos = b.timeline.positionUs
        val i = project.clipAt(pos)
        val c = project.clips.getOrNull(i) ?: return
        val rel = pos - project.clipStartUs(i)
        if (rel < 100_000 || c.timelineUs - rel < 100_000) return toast("حرّك المؤشر لوسط المقطع عشان تقسمه")
        edit {
            val second: Clip
            if (c.type == ClipType.IMAGE) {
                second = c.copy(id = newId(), sourceDurationUs = c.sourceDurationUs - rel, trimStartUs = 0, trimEndUs = c.sourceDurationUs - rel)
                c.sourceDurationUs = rel; c.trimStartUs = 0; c.trimEndUs = rel
            } else {
                val cut = c.trimStartUs + (rel * c.speed).toLong()
                second = c.copy(id = newId(), trimStartUs = cut)
                c.trimEndUs = cut
            }
            c.transition = Transition.NONE
            project.clips.add(i + 1, second)
            selClip = i + 1
        }
    }

    private fun trimClip(c: Clip) {
        val sheet = Sheet(this, "قص المقطع")
        val dur = c.sourceDurationUs / 1_000_000f
        sheet.range("البداية والنهاية", 0f, dur, c.trimStartUs / 1e6f, c.trimEndUs / 1e6f, { secs(it) }) { s, e ->
            if (e - s < 0.1f) return@range toast("المقطع قصير جداً")
            edit { c.trimStartUs = (s * 1e6).toLong(); c.trimEndUs = (e * 1e6).toLong() }
        }
        sheet.show()
    }

    private fun imageDuration(c: Clip) {
        val sheet = Sheet(this, "مدة الصورة")
        sheet.slider("المدة", 0.5f, 15f, c.timelineUs / 1e6f, { secs(it) }, 0.1f) { v ->
            edit { val us = (v * 1e6).toLong(); c.sourceDurationUs = us; c.trimStartUs = 0; c.trimEndUs = us }
        }
        sheet.show()
    }

    private fun speedClip(c: Clip) {
        val sheet = Sheet(this, "السرعة")
        val presets = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
        lateinit var slider: com.google.android.material.slider.Slider
        sheet.chips(null, presets.map { "${it}x" }, presets.indexOfFirst { abs(it - c.speed) < 0.01f }) { i ->
            slider.value = presets[i]
            edit { c.speed = presets[i] }
        }
        slider = sheet.slider("سرعة مخصصة", 0.25f, 4f, c.speed, { String.format(Locale.US, "%.2fx", it) }, 0.05f) { v ->
            edit { c.speed = v }
        }
        sheet.text("الصوت يتسرّع مع الفيديو. مدة المقطع الحالية: ${secs(c.timelineUs / 1e6f)}")
        sheet.show()
    }

    private fun volumeClip(c: Clip) {
        val sheet = Sheet(this, "صوت المقطع")
        lateinit var s: com.google.android.material.slider.Slider
        s = sheet.slider("مستوى الصوت", 0f, 2f, c.volume, { "${(it * 100).toInt()}%" }, 0.05f) { v -> edit { c.volume = v } }
        sheet.button(if (c.volume == 0f) "إلغاء الكتم" else "كتم الصوت 🔇", outlined = true) {
            val v = if (c.volume == 0f) 1f else 0f
            s.value = v
            edit { c.volume = v }
            sheet.dismiss()
        }
        sheet.button("تطبيق المستوى على كل المقاطع", outlined = true) {
            edit { project.clips.forEach { it.volume = c.volume } }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun filters(c: Clip) {
        val sheet = Sheet(this, "فلاتر وتعديل الألوان")
        val pct: (Float) -> String = { "${(it * 100).toInt()}" }
        val presets = listOf("بدون" to floatArrayOf(0f, 0f, 0f), "حيوي" to floatArrayOf(0.03f, 0.15f, 0.35f),
            "سينمائي" to floatArrayOf(-0.05f, 0.25f, -0.15f), "أبيض وأسود" to floatArrayOf(0f, 0.15f, -1f),
            "ناعم" to floatArrayOf(0.08f, -0.15f, -0.1f), "درامي" to floatArrayOf(-0.1f, 0.4f, 0.1f))
        val br = arrayOfNulls<com.google.android.material.slider.Slider>(3)
        sheet.chips("جاهزة", presets.map { it.first }, -1) { i ->
            val v = presets[i].second
            br[0]?.value = v[0]; br[1]?.value = v[1]; br[2]?.value = v[2]
            edit { c.brightness = v[0]; c.contrast = v[1]; c.saturation = v[2] }
        }
        br[0] = sheet.slider("السطوع", -0.5f, 0.5f, c.brightness, pct, 0.01f) { v -> edit { c.brightness = v } }
        br[1] = sheet.slider("التباين", -0.5f, 0.6f, c.contrast, pct, 0.01f) { v -> edit { c.contrast = v } }
        br[2] = sheet.slider("التشبّع", -1f, 1f, c.saturation, pct, 0.01f) { v -> edit { c.saturation = v } }
        sheet.button("تطبيق على كل المقاطع", outlined = true) {
            edit { project.clips.forEach { it.brightness = c.brightness; it.contrast = c.contrast; it.saturation = c.saturation } }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun textClip(c: Clip) {
        val sheet = Sheet(this, "نص على المقطع")
        val input = sheet.input("اكتب النص", c.text)
        var pos = c.textPos
        var color = c.textColor
        var scale = c.textScale
        sheet.chips("المكان", TextPos.entries.map { it.label }, pos.ordinal) { pos = TextPos.entries[it] }
        sheet.slider("الحجم", 0.4f, 3f, scale, { "${(it * 100).toInt()}%" }, 0.05f) { scale = it }
        sheet.text("اللون")
        val palette = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF2D55.toInt(), 0xFF25F4EE.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        sheet.colors(color, palette) { color = it; toast("تم اختيار اللون") }
        sheet.button("حفظ النص") {
            edit {
                c.text = input.text?.toString().orEmpty()
                c.textPos = pos; c.textColor = color; c.textScale = scale
            }
            sheet.dismiss()
        }
        if (c.text.isNotEmpty()) sheet.button("حذف النص", outlined = true) { edit { c.text = "" }; sheet.dismiss() }
        sheet.show()
    }

    private fun moveClip(dir: Int) {
        val i = selClip
        val j = i + dir
        if (i < 0 || j < 0 || j >= project.clips.size) return
        edit {
            val c = project.clips.removeAt(i)
            project.clips.add(j, c)
            selClip = j
        }
    }

    private fun duplicateClip() {
        val c = project.clips.getOrNull(selClip) ?: return
        edit { project.clips.add(selClip + 1, c.copy(id = newId())); selClip += 1 }
    }

    private fun deleteClip() {
        if (selClip < 0) return
        edit { project.clips.removeAt(selClip); selClip = -1 }
    }

    private fun aspectSheet() {
        val sheet = Sheet(this, "نسبة الأبعاد")
        sheet.chips(null, Aspect.entries.map { it.label + if (it == Aspect.R9_16) " (تيك توك)" else "" }, project.aspect.ordinal) { i ->
            edit { project.aspect = Aspect.entries[i] }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun showTransition(index: Int) {
        val all = index < 0
        val clip = if (all) project.clips.firstOrNull() else project.clips.getOrNull(index)
        if (clip == null) return
        val sheet = Sheet(this, if (all) "انتقال لكل المقاطع" else "الانتقال بين المقطع ${index + 1} و ${index + 2}")
        var kind = clip.transition
        var durUs = clip.transitionUs
        sheet.chips("النوع", Transition.entries.map { it.label }, kind.ordinal) { kind = Transition.entries[it] }
        sheet.slider("المدة", 0.2f, 2f, durUs / 1e6f, { secs(it) }, 0.1f) { durUs = (it * 1e6).toLong() }
        sheet.button("تطبيق") {
            edit {
                val targets = if (all) project.clips.dropLast(1) else listOf(clip)
                targets.forEach { it.transition = kind; it.transitionUs = durUs }
            }
            sheet.dismiss()
            // Jump just before the cut so the user sees it.
            if (!all) {
                val cut = project.clipStartUs(index + 1)
                val pos = (cut - durUs).coerceAtLeast(0)
                b.timeline.setPosition(pos); player?.seekTo(pos / 1000); updateTime()
            }
        }
        if (!all) sheet.button("تطبيق على كل المقاطع", outlined = true) {
            edit { project.clips.dropLast(1).forEach { it.transition = kind; it.transitionUs = durUs } }
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Audio tools ----------

    private fun volumeAudio(a: AudioClip) {
        val sheet = Sheet(this, "مستوى الصوت")
        sheet.slider("مستوى «${a.label}»", 0f, 2f, a.volume, { "${(it * 100).toInt()}%" }, 0.05f) { v -> edit { a.volume = v } }
        sheet.text("صوت المقاطع الأصلي تتحكم فيه من تحديد المقطع ← الصوت")
        sheet.show()
    }

    private fun fadeAudio(a: AudioClip) {
        val sheet = Sheet(this, "تلاشي الصوت")
        val max = (a.lengthUs / 2e6f).coerceAtMost(10f).coerceAtLeast(0.2f)
        sheet.slider("ظهور تدريجي", 0f, max, (a.fadeInUs / 1e6f).coerceAtMost(max), { secs(it) }, 0f) { v -> edit { a.fadeInUs = (v * 1e6).toLong() } }
        sheet.slider("اختفاء تدريجي", 0f, max, (a.fadeOutUs / 1e6f).coerceAtMost(max), { secs(it) }, 0f) { v -> edit { a.fadeOutUs = (v * 1e6).toLong() } }
        sheet.show()
    }

    private fun trimAudio(a: AudioClip) {
        val sheet = Sheet(this, "قص الصوت")
        sheet.range("الجزء المستخدم", 0f, a.sourceDurationUs / 1e6f, a.trimStartUs / 1e6f, a.trimEndUs / 1e6f, { secs(it) }) { s, e ->
            if (e - s < 0.1f) return@range toast("قصير جداً")
            edit { a.trimStartUs = (s * 1e6).toLong(); a.trimEndUs = (e * 1e6).toLong() }
        }
        sheet.show()
    }

    private fun splitAudio() {
        val a = project.audios.getOrNull(selAudio) ?: return
        val rel = b.timeline.positionUs - a.startAtUs
        if (rel < 100_000 || a.lengthUs - rel < 100_000) return toast("حرّك المؤشر لوسط الصوت عشان تقسمه")
        edit {
            val cut = a.trimStartUs + rel
            project.audios.add(selAudio + 1, a.copy(id = newId(), trimStartUs = cut, startAtUs = a.startAtUs + rel, fadeInUs = 0))
            a.trimEndUs = cut
            a.fadeOutUs = 0
        }
    }

    private fun duplicateAudio(a: AudioClip) {
        edit {
            project.audios += a.copy(id = newId(), startAtUs = a.startAtUs + a.lengthUs)
            selAudio = project.audios.lastIndex
        }
    }

    private fun secs(v: Float) = String.format(Locale.US, "%.1fث", v)

    // ---------- Project ----------

    private fun renameProject() {
        val sheet = Sheet(this, "اسم المشروع")
        val input = sheet.input("الاسم", project.name)
        sheet.button("حفظ") {
            val n = input.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty()) edit(rebuild = false) { project.name = n }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun autoBackup() {
        if (SyncPrefs.email(this) == null || !SyncPrefs.auto(this) || project.clips.isEmpty()) return
        val app = application as MawjApp
        val id = project.id
        app.scope.launch {
            runCatching {
                val token = GoogleAuth.silentToken(app) ?: return@launch
                DriveSync(app.store).backup(token, id) {}
                SyncPrefs.setLastSync(app, System.currentTimeMillis())
            }
        }
    }

    // ---------- Export ----------

    private fun showExport() {
        if (project.clips.isEmpty()) return
        val sheet = Sheet(this, "تصدير الفيديو")
        val res = listOf(720, 1080, 1440, 2160)
        val resLabels = listOf("720p", "1080p (تيك توك)", "2K", "4K")
        var r = 1
        var quality = 1
        sheet.chips("الدقة", resLabels, r) { r = it }
        sheet.chips("الجودة", listOf("عالية", "قصوى"), quality) { quality = it }
        sheet.text("💡 للتيك توك: 1080p + قصوى، وفعّل «السماح بالتحميلات عالية الجودة» وقت النشر.")
        sheet.button("تصدير وحفظ في الاستديو") {
            sheet.dismiss()
            export(res[r], if (quality == 0) 0.25 else 0.45)
        }
        sheet.show()
    }

    private fun export(shortSide: Int, bpp: Double) {
        player?.pause()
        player?.release(); player = null
        b.playerView.player = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bar = LinearProgressIndicator(this).apply { max = 100 }
        val label = TextView(this).apply { text = "0%"; setPadding(0, dp(8), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(-1, -2)); addView(label)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("جاري التصدير…").setView(box).setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> exportJob?.cancel() }
            .show()

        val (w, h) = project.aspect.canvas(shortSide)
        val bitrate = (w.toDouble() * h * 30 * bpp).toLong().coerceIn(6_000_000, 120_000_000).toInt()
        val out = File(cacheDir, "export.mp4").apply { delete() }
        exportJob = lifecycleScope.launch {
            try {
                val comp = CompositionFactory.build(this@EditorActivity, store, project, BuildOptions(w, h, true))
                MediaJobs.exportVideo(this@EditorActivity, comp, bitrate, out) { p ->
                    bar.setProgressCompat(p, true); label.text = "$p%"
                }
                label.text = "جاري الحفظ في الاستديو…"
                val name = "MawjEdit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val uri = withContext(Dispatchers.IO) { MediaJobs.saveToGallery(this@EditorActivity, out, name) }
                val sizeMb = out.length() / 1e6
                out.delete()
                dialog.dismiss()
                showExportDone(uri, sizeMb, w, h)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss(); out.delete(); toast("تم إلغاء التصدير")
            } catch (e: Exception) {
                dialog.dismiss(); out.delete()
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle("تعذّر التصدير")
                    .setMessage("${e.message}\n\nجرّب دقة أقل (1080p).")
                    .setPositiveButton("حسناً", null).show()
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                rebuildPreview()
            }
        }
    }

    private fun showExportDone(uri: Uri, sizeMb: Double, w: Int, h: Int) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("تم التصدير ✅")
            .setMessage(String.format(Locale.US, "انحفظ في Movies/Mawj Edit\n%d×%d • %.1f MB", w, h, sizeMb))
            .setPositiveButton("ارفع على تيك توك") { _, _ ->
                val pkg = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
                    .firstOrNull { Intent(send).setPackage(it).resolveActivity(packageManager) != null }
                if (pkg != null) startActivity(Intent(send).setPackage(pkg))
                else startActivity(Intent.createChooser(send, "مشاركة"))
            }
            .setNeutralButton("مشاركة") { _, _ -> startActivity(Intent.createChooser(send, "مشاركة")) }
            .setNegativeButton("تم", null)
            .show()
    }

    // ---------- Thumbnails ----------

    private fun thumbKey(c: Clip) = c.file + "@" + (c.trimStartUs / 100_000)

    private fun thumbFor(key: String): Bitmap? {
        thumbs.get(key)?.let { return it }
        if (!thumbLoading.add(key)) return null
        val file = key.substringBefore("@")
        val atUs = key.substringAfter("@").toLong() * 100_000
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = store.mediaFile(project.id, file)
                    val isImage = project.clips.firstOrNull { it.file == file }?.type == ClipType.IMAGE
                    if (isImage) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.absolutePath, o)
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= 160 && o.outHeight / (sample * 2) >= 160) sample *= 2
                        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    } else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(f.absolutePath)
                            r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                        } finally { r.release() }
                    }
                }.getOrNull()
            }
            thumbLoading.remove(key)
            if (bmp != null) { thumbs.put(key, bmp); b.timeline.invalidate() }
        }
        return null
    }
}
