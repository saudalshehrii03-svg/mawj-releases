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
import android.widget.ImageView
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
import com.mawj.editor.engine.Bridges
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
    private var bridgeJob: Job? = null

    private val thumbs = LruCache<String, Bitmap>(80)
    private val thumbLoading = HashSet<String>()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) importClips(uris)
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }
    private var fontTargetClip: String? = null
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val clip = project.clips.firstOrNull { it.id == fontTargetClip }
        if (uri == null || clip == null) return@registerForActivityResult
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { Fonts.import(this@EditorActivity, uri) }
            if (f == null) return@launch toast("الملف مو خط صالح (استخدم TTF أو OTF)")
            setClipFont(clip, f.file)
            textClip(clip)
        }
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
        b.fullPlay.setOnClickListener { togglePlay() }
        b.fullButton.setOnClickListener { setFullscreen(true) }
        b.fullExit.setOnClickListener { setFullscreen(false) }
        b.fullSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                val us = project.durationUs * v / 1000
                player?.seekTo(us / 1000); b.timeline.setPosition(us); updateTime()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { player?.pause() }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        setupSplit()
        b.timeline.listener = this
        b.timeline.thumbProvider = { key -> thumbFor(key) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = if (fullscreen) setFullscreen(false) else leave()
        })

        refreshAll()
        Fonts.apply(b.root)
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

    // ---------- Layout: resizable preview / timeline, full screen ----------

    private var fullscreen = false
    private val splitPrefs by lazy { getSharedPreferences("editor_ui", MODE_PRIVATE) }

    private fun minPanel() = dp(170)
    private fun maxPanel() = (resources.displayMetrics.heightPixels * 0.68f).toInt()

    private fun setPanelHeight(h: Int) {
        val v = h.coerceIn(minPanel(), maxPanel())
        b.bottomPanel.layoutParams = b.bottomPanel.layoutParams.apply { height = v }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSplit() {
        setPanelHeight(splitPrefs.getInt("panel_h", dp(380)))
        // Drag the handle (or swipe vertically on the preview) to resize.
        var startY = 0f
        var startH = 0
        b.handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startH = b.bottomPanel.height }
                android.view.MotionEvent.ACTION_MOVE -> setPanelHeight(startH - (e.rawY - startY).toInt())
                android.view.MotionEvent.ACTION_UP -> splitPrefs.edit().putInt("panel_h", b.bottomPanel.height).apply()
            }
            true
        }
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean { startH = b.bottomPanel.height; return true }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (fullscreen) b.fullControls.visibility = if (b.fullControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                else togglePlay()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { setFullscreen(!fullscreen); return true }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
                if (fullscreen || e1 == null) return false
                setPanelHeight(startH - (e2.rawY - e1.rawY).toInt())
                return true
            }
        })
        b.playerView.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !fullscreen) {
                splitPrefs.edit().putInt("panel_h", b.bottomPanel.height).apply()
            }
            true
        }
    }

    private fun setFullscreen(on: Boolean) {
        if (project.clips.isEmpty()) return
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        b.topBar.visibility = vis
        b.bottomPanel.visibility = vis
        b.fullControls.visibility = if (on) View.VISIBLE else View.GONE
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        updateTime()
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
        b.exportButton.alpha = if (project.clips.isEmpty()) 0.4f else 1f

        val v = project.clips.mapIndexed { i, it ->
            TimelineView.VBlock(project.clipStartUs(i), it.timelineUs, thumbKey(it), it.type == ClipType.IMAGE, it.transition, it.text.isNotBlank())
        }
        b.subtitle.text = "${project.aspect.label} • ${project.clips.size} مقطع • ${fmt(project.durationUs).substringBefore('.')}"
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
        val ready = Bridges.needed(project).mapNotNull { i -> Bridges.cached(this, project, i, w, h, false)?.let { i to it } }.toMap()
        if (ready.size < Bridges.needed(project).size) renderPreviewBridges()
        val composition = try {
            CompositionFactory.build(this, store, project, BuildOptions(w, h, false, previewTier < 1, previewTier < 2, ready))
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

    /** Renders missing overlap transitions in the background, then refreshes the preview. */
    private fun renderPreviewBridges() {
        if (bridgeJob?.isActive == true) return
        val (w, h) = project.aspect.canvas(720)
        bridgeJob = lifecycleScope.launch {
            b.bridgeStatus.visibility = View.VISIBLE
            var rendered = false
            for (i in Bridges.needed(project)) {
                if (i >= project.clips.size - 1) break
                if (Bridges.cached(this@EditorActivity, project, i, w, h, false) != null) continue
                b.bridgeStatus.text = "✨ جاري تجهيز الدمج ${i + 1}…"
                val f = Bridges.render(this@EditorActivity, store, project, i, w, h, false, 8_000_000)
                if (f == null) {
                    if (!com.mawj.editor.model.Caps.overlapSupported) {
                        toast("جهازك ما يدعم الدمج المتداخل — استبدلناه بتلاشي ناعم")
                        refreshAll()
                    }
                    break
                }
                rendered = true
            }
            b.bridgeStatus.visibility = View.GONE
            if (rendered || !com.mawj.editor.model.Caps.overlapSupported) rebuildPreview()
        }
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
        val icon = if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        b.playButton.setImageResource(icon)
        b.fullPlay.setImageResource(icon)
    }

    private fun updateTime() {
        b.timeText.text = fmt(b.timeline.positionUs)
        b.durationText.text = "/ " + fmt(project.durationUs)
        if (fullscreen && project.durationUs > 0) b.fullSeek.progress = (b.timeline.positionUs * 1000 / project.durationUs).toInt()
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

    override fun onAddTap() = launchMediaPicker()

    override fun onClipTrim(index: Int, edge: Int, deltaUs: Long) {
        val c = project.clips.getOrNull(index) ?: return
        edit {
            if (c.type == ClipType.IMAGE) {
                val dur = (c.timelineUs + if (edge == 0) -deltaUs else deltaUs).coerceIn(300_000, 60_000_000)
                c.sourceDurationUs = dur; c.trimStartUs = 0; c.trimEndUs = dur
            } else if (edge == 0) {
                c.trimStartUs = (c.trimStartUs + (deltaUs * c.speed).toLong()).coerceIn(0, c.trimEndUs - 200_000)
            } else {
                c.trimEndUs = (c.trimEndUs + (deltaUs * c.speed).toLong()).coerceIn(c.trimStartUs + 200_000, c.sourceDurationUs)
            }
        }
    }

    override fun onAudioTrim(index: Int, edge: Int, deltaUs: Long) {
        val a = project.audios.getOrNull(index) ?: return
        edit {
            if (edge == 0) {
                val d = deltaUs.coerceIn(-minOf(a.trimStartUs, a.startAtUs), a.lengthUs - 200_000)
                a.trimStartUs += d; a.startAtUs += d
            } else {
                a.trimEndUs = (a.trimEndUs + deltaUs).coerceIn(a.trimStartUs + 200_000, a.sourceDurationUs)
            }
        }
    }

    // ---------- Tools ----------

    private fun tool(icon: Int, label: String, accent: Boolean = false, onClick: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }
        item.background = getDrawable(R.drawable.btn_flat)
        item.addView(ImageView(this).apply {
            setImageResource(icon)
            if (accent) imageTintList = android.content.res.ColorStateList.valueOf(Glass.PINK)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(6) })
        item.addView(TextView(this).apply {
            text = label; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(if (accent) Glass.PINK else 0xD9FFFFFF.toInt()); maxLines = 1
            setPadding(0, dp(6), 0, dp(4))
        })
        b.tools.addView(item, LinearLayout.LayoutParams(dp(66), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun buildTools() {
        b.tools.removeAllViews()
        val clip = project.clips.getOrNull(selClip)
        val audio = project.audios.getOrNull(selAudio)
        when {
            clip != null -> {
                b.toolsTitle.text = (if (clip.type == ClipType.IMAGE) "صورة " else "مقطع ") + "${selClip + 1} • " + secs(clip.timelineUs / 1e6f)
                tool(R.drawable.i_close, "إغلاق") { onEmptyTap() }
                tool(R.drawable.i_split, "تقسيم") { splitClip() }
                if (clip.type == ClipType.VIDEO) {
                    tool(R.drawable.i_trim, "قص") { trimClip(clip) }
                    tool(R.drawable.i_speed, "السرعة") { speedClip(clip) }
                    tool(if (clip.volume == 0f) R.drawable.i_mute else R.drawable.i_volume, "الصوت") { volumeClip(clip) }
                    tool(R.drawable.i_wave, "استخراج الصوت") { extractAudio(clip) }
                } else {
                    tool(R.drawable.i_timer, "المدة") { imageDuration(clip) }
                }
                if (selClip < project.clips.lastIndex) tool(R.drawable.i_sparkles, "انتقال", accent = true) { showTransition(selClip) }
                tool(R.drawable.i_sliders, "فلاتر") { filters(clip) }
                tool(R.drawable.i_text, "نص") { textClip(clip) }
                tool(R.drawable.i_rotate, "تدوير") { edit { clip.rotation = (clip.rotation + 90) % 360 } }
                tool(R.drawable.i_flip, "قلب") { edit { clip.mirror = !clip.mirror } }
                tool(R.drawable.i_fit, if (clip.fill) "احتواء" else "ملء الشاشة") { edit { clip.fill = !clip.fill } }
                tool(R.drawable.i_left, "تحريك") { moveClip(-1) }
                tool(R.drawable.i_right, "تحريك") { moveClip(1) }
                tool(R.drawable.i_copy, "تكرار") { duplicateClip() }
                tool(R.drawable.i_trash, "حذف") { deleteClip() }
            }
            audio != null -> {
                b.toolsTitle.text = "♪ ${audio.label} • " + secs(audio.lengthUs / 1e6f)
                tool(R.drawable.i_close, "إغلاق") { onEmptyTap() }
                tool(R.drawable.i_volume, "الصوت") { volumeAudio(audio) }
                tool(R.drawable.i_fade, "تلاشي") { fadeAudio(audio) }
                tool(R.drawable.i_split, "تقسيم") { splitAudio() }
                tool(R.drawable.i_trim, "قص") { trimAudio(audio) }
                tool(R.drawable.i_pin, "نقل هنا") { edit { audio.startAtUs = b.timeline.positionUs } }
                tool(R.drawable.i_copy, "تكرار") { duplicateAudio(audio) }
                tool(R.drawable.i_trash, "حذف") { edit { project.audios.removeAt(selAudio); selAudio = -1 } }
            }
            else -> {
                b.toolsTitle.text = "اضغط مقطع لتعديله • اسحب الحواف للقص • اضغط المعاينة مرتين لملء الشاشة"
                tool(R.drawable.i_film, "إضافة مقطع", accent = true) { launchMediaPicker() }
                tool(R.drawable.i_music, "إضافة صوت") { pickAudio.launch(arrayOf("audio/*")) }
                if (project.clips.size > 1) tool(R.drawable.i_sparkles, "انتقال للكل") { showTransition(-1) }
                tool(R.drawable.i_split, "تقسيم") { splitClip() }
                tool(R.drawable.i_ratio, "النسبة") { aspectSheet() }
                tool(R.drawable.i_send, "تيك توك HD") { showExport() }
            }
        }
        Fonts.apply(b.tools)
        Fonts.apply(b.toolsTitle)
    }

    private fun launchMediaPicker() =
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    // ---------- Import ----------

    private fun busyDialog(title: String): Pair<AlertDialog, LinearProgressIndicator> {
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(12), dp(24), dp(8)); addView(bar, LinearLayout.LayoutParams(-1, -2)) }
        val d = MaterialAlertDialogBuilder(this).setTitle(title).setView(box).setCancelable(false).show()
        Glass.style(d)
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
        val fonts = Fonts.list(this)
        val currentFont = fonts.indexOfFirst { "f_" + it.file == c.textFont }.let { if (c.textFont.isEmpty()) 0 else it + 1 }
        var fontChoice = currentFont
        fun previewFont() {
            input.typeface = if (fontChoice <= 0) android.graphics.Typeface.DEFAULT_BOLD
            else Fonts.load(File(Fonts.dir(this), fonts[fontChoice - 1].file))
            input.tag = "keepFont"
        }
        sheet.chips("الخط", listOf("الافتراضي") + fonts.map { it.name } + listOf("＋ رفع خط"), currentFont.coerceAtLeast(0)) { i ->
            if (i == fonts.size + 1) {
                fontTargetClip = c.id
                sheet.dismiss()
                pickFont.launch(Fonts.PICK_TYPES)
            } else { fontChoice = i; previewFont() }
        }
        previewFont()
        sheet.chips("المكان", TextPos.entries.map { it.label }, pos.ordinal) { pos = TextPos.entries[it] }
        sheet.slider("الحجم", 0.4f, 3f, scale, { "${(it * 100).toInt()}%" }, 0.05f) { scale = it }
        sheet.text("اللون")
        val palette = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF2D55.toInt(), 0xFF25F4EE.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        sheet.colors(color, palette) { color = it; toast("تم اختيار اللون") }
        sheet.button("حفظ النص") {
            val chosen = if (fontChoice <= 0) "" else fonts[fontChoice - 1].file
            edit {
                c.text = input.text?.toString().orEmpty()
                c.textPos = pos; c.textColor = color; c.textScale = scale
            }
            if (chosen.isEmpty()) { if (c.textFont.isNotEmpty()) edit { c.textFont = "" } }
            else if (c.textFont != "f_$chosen") setClipFont(c, chosen)
            sheet.dismiss()
        }
        if (c.text.isNotEmpty()) sheet.button("حذف النص", outlined = true) { edit { c.text = "" }; sheet.dismiss() }
        sheet.show()
    }

    /** Copies an app font into the project (so it travels with backups) and uses it for the clip's text. */
    private fun setClipFont(c: Clip, appFontFile: String) {
        val name = "f_$appFontFile"
        val dest = store.mediaFile(project.id, name)
        if (!dest.exists()) File(Fonts.dir(this), appFontFile).copyTo(dest, overwrite = true)
        edit { c.textFont = name }
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
        val sheet = Sheet(this, if (all) "انتقال لكل المقاطع" else "الانتقال بين ${index + 1} و ${index + 2}",
            "اختر النوع والمدة، والمعاينة تتحدث تلقائياً")
        var kind = clip.transition
        var durUs = clip.transitionUs
        val overlapKinds = Transition.entries.filter { it.overlap }
        val motionKinds = Transition.entries.filter { !it.overlap }
        lateinit var g1: com.google.android.material.chip.ChipGroup
        lateinit var g2: com.google.android.material.chip.ChipGroup
        g1 = sheet.chips("✦ دمج احترافي (المقطعين فوق بعض)", overlapKinds.map { it.label }, overlapKinds.indexOf(kind), required = false) {
            kind = overlapKinds[it]; g2.clearCheck()
        }
        g2 = sheet.chips("حركات القطع", motionKinds.map { it.label }, motionKinds.indexOf(kind), required = false) {
            kind = motionKinds[it]; g1.clearCheck()
        }
        sheet.slider("المدة", 0.2f, 2f, durUs / 1e6f, { secs(it) }, 0.1f) { durUs = (it * 1e6).toLong() }
        if (!com.mawj.editor.model.Caps.overlapSupported) sheet.note("ℹ️ جهازك ما يدعم الدمج المتداخل؛ بيتحول لتلاشي ناعم.")
        sheet.button("تطبيق") {
            edit {
                val targets = if (all) project.clips.dropLast(1) else listOf(clip)
                targets.forEach { it.transition = kind; it.transitionUs = durUs }
            }
            sheet.dismiss()
            if (!all && index + 1 < project.clips.size) {
                val cut = project.clipStartUs(index + 1)
                val pos = (cut - 300_000).coerceAtLeast(0)
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
        val sheet = Sheet(this, "تصدير", "اختر وين بتنشر المقطع")
        fun select(t: Boolean) {
            sheet.dismiss()
            showExportWith(t)
        }
        sheet.option(R.drawable.i_send, "تيك توك HD  ⭐", "1080×1920 • H.264 • معدل بت عالي • بدون فقد بعد الرفع", true) { select(true) }
        sheet.option(R.drawable.i_sliders, "مخصص", "اختر الدقة (حتى 4K) والجودة والترميز", false) { select(false) }
        sheet.show()
    }

    private fun showExportWith(tiktok: Boolean) {
        if (tiktok) {
            val sheet = Sheet(this, "تيك توك HD", "إعدادات موج HD الجاهزة لأعلى جودة على تيك توك")
            sheet.note("✅ دقة 1080p بالضبط (اللي يعرضها تيك توك)\n✅ ترميز H.264 بمعدل بت عالي جداً\n✅ تحويل HDR إلى SDR عشان الألوان ما تبهت\n✅ بعد الحفظ نوديك لتيك توك خطوة بخطوة")
            if (project.aspect != Aspect.R9_16) sheet.note("⚠️ نسبة المشروع ${project.aspect.label} — الأفضل للتيك توك 9:16 (غيرها من أداة «النسبة»).")
            sheet.button("صدّر لتيك توك") {
                sheet.dismiss()
                export(1080, 0.5, false, true)
            }
            sheet.show()
            return
        }
        val sheet = Sheet(this, "تصدير مخصص")
        val res = listOf(720, 1080, 1440, 2160)
        var r = 1
        var quality = 1
        var hevc = false
        sheet.chips("الدقة", listOf("720p", "1080p", "2K", "4K"), r) { r = it }
        sheet.chips("الجودة", listOf("عالية", "قصوى", "استديو"), quality) { quality = it }
        sheet.chips("الترميز", listOf("H.264 (متوافق)", "H.265 (أصغر)"), 0) { hevc = it == 1 }
        sheet.button("تصدير وحفظ") {
            sheet.dismiss()
            export(res[r], listOf(0.25, 0.4, 0.6)[quality], hevc, false)
        }
        sheet.show()
    }

    private fun export(shortSide: Int, bpp: Double, hevc: Boolean, tiktok: Boolean) {
        player?.pause()
        player?.release(); player = null
        b.playerView.player = null
        bridgeJob?.cancel()
        b.bridgeStatus.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bar = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = dp(4); trackThickness = dp(8) }
        val label = TextView(this).apply { text = "تجهيز…"; setPadding(0, dp(10), 0, 0); setTextColor(0xCCFFFFFF.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(-1, -2)); addView(label)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (tiktok) "تصدير تيك توك HD…" else "جاري التصدير…").setView(box).setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> exportJob?.cancel() }
            .show()
        Glass.style(dialog)

        val (w, h) = project.aspect.canvas(shortSide)
        val bitrate = (w.toDouble() * h * 30 * bpp).toLong().coerceIn(8_000_000, 150_000_000).toInt()
        val out = File(cacheDir, "export.mp4").apply { delete() }
        exportJob = lifecycleScope.launch {
            try {
                // 1) Render overlapping transitions at full quality.
                val needed = Bridges.needed(project)
                val bridges = HashMap<Int, File>()
                needed.forEachIndexed { n, i ->
                    label.text = "تجهيز الدمج ${n + 1}/${needed.size}…"
                    bar.setProgressCompat(n * 100 / needed.size.coerceAtLeast(1), true)
                    Bridges.render(this@EditorActivity, store, project, i, w, h, true, bitrate)?.let { bridges[i] = it }
                }
                // 2) Export the timeline.
                val comp = CompositionFactory.build(this@EditorActivity, store, project, BuildOptions(w, h, true, bridges = bridges))
                MediaJobs.exportVideo(this@EditorActivity, comp, bitrate, out, hevc) { p ->
                    bar.setProgressCompat(p, true); label.text = "$p%"
                }
                label.text = "جاري الحفظ في الاستديو…"
                val name = (if (tiktok) "MawjTikTok_" else "MawjEdit_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val uri = withContext(Dispatchers.IO) { MediaJobs.saveToGallery(this@EditorActivity, out, name) }
                val sizeMb = out.length() / 1e6
                out.delete()
                dialog.dismiss()
                if (tiktok) showTikTokGuide(uri) else showExportDone(uri, sizeMb, w, h)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss(); out.delete(); toast("تم إلغاء التصدير")
            } catch (e: Exception) {
                dialog.dismiss(); out.delete()
                val d = MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle("تعذّر التصدير")
                    .setMessage("${e.message}\n\nجرّب دقة أقل (1080p).")
                    .setPositiveButton("حسناً", null).show()
                Glass.style(d)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                rebuildPreview()
            }
        }
    }

    private fun shareIntent(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun openTikTok(uri: Uri) {
        val send = shareIntent(uri)
        val pkg = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            .firstOrNull { Intent(send).setPackage(it).resolveActivity(packageManager) != null }
        if (pkg != null) startActivity(Intent(send).setPackage(pkg))
        else { toast("تيك توك غير مثبت"); startActivity(Intent.createChooser(send, "مشاركة")) }
    }

    /** Step-by-step guide so TikTok keeps the HD quality. */
    private fun showTikTokGuide(uri: Uri) {
        val sheet = Sheet(this, "جاهز للرفع ✅", "انحفظ في Movies/Mawj Edit • اتبع الخطوات عشان تيك توك ما ينزّل الجودة")
        sheet.note("١. اضغط «افتح تيك توك» تحت — المقطع يفتح فيه مباشرة.")
        sheet.note("٢. اضغط «التالي» إلى أن توصل لصفحة النشر (إضافة وصف).")
        sheet.note("٣. اضغط «المزيد من الخيارات» ← فعّل «السماح بالتحميلات عالية الجودة» (يصير أزرق).")
        sheet.note("٤. لا تضيف فلاتر أو تأثيرات من تيك توك، وانشر وأنت على واي فاي.")
        sheet.note("💡 أول دقايق بعد النشر ممكن تطلع الجودة أقل — تيك توك يجهّز نسخة HD بعدها بشوي.")
        sheet.button("افتح تيك توك") { sheet.dismiss(); openTikTok(uri) }
        sheet.button("مشاركة لتطبيق ثاني", outlined = true) { startActivity(Intent.createChooser(shareIntent(uri), "مشاركة")) }
        sheet.show()
    }

    private fun showExportDone(uri: Uri, sizeMb: Double, w: Int, h: Int) {
        val sheet = Sheet(this, "تم التصدير ✅", String.format(Locale.US, "Movies/Mawj Edit • %d×%d • %.1f MB", w, h, sizeMb))
        sheet.button("ارفع على تيك توك (بالخطوات)") { sheet.dismiss(); showTikTokGuide(uri) }
        sheet.button("مشاركة", outlined = true) { startActivity(Intent.createChooser(shareIntent(uri), "مشاركة")) }
        sheet.show()
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
