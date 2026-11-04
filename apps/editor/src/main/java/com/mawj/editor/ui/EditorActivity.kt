package com.mawj.editor.ui

import com.mawj.editor.L

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
            if (f == null) return@launch toast(L.t("الملف مو خط صالح (استخدم TTF أو OTF)", "Not a valid font file (use TTF or OTF)"))
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
            Toast.makeText(this, L.t("تعذّر فتح المشروع", "Couldn't open the project"), Toast.LENGTH_SHORT).show(); finish(); return
        }

        b.title.setOnClickListener { renameProject() }
        b.exportButton.text = L.t("تصدير", "Export")
        b.previewHint.text = L.t("أضف مقاطع أو صور للبدء", "Add clips or photos to start")
        b.moreButton.setOnClickListener { moreMenu() }
        b.muteButton.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            b.muteButton.setImageResource(if (muted) R.drawable.i_mute else R.drawable.i_volume)
            chip(if (muted) L.t("كتم المعاينة", "Preview muted") else L.t("الصوت شغّال", "Sound on"))
        }
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
    private var muted = false
    private var category = ""
    private val splitPrefs by lazy { getSharedPreferences("editor_ui", MODE_PRIVATE) }

    /** Visible height of the editor panel (it slides over the preview). */
    private var panelV = 0f

    private fun minPanel() = dp(150).toFloat()
    private fun maxPanel() = (b.stage.height * 0.78f).coerceAtLeast(minPanel())

    /**
     * Moves the panel and scales the video with GPU transforms only — no layout pass, so the
     * video surface never resizes and the motion follows the finger smoothly.
     */
    private fun setPanel(v: Float, settle: Boolean = false) {
        if (b.stage.height == 0) return
        panelV = v.coerceIn(minPanel(), maxPanel())
        b.bottomPanel.translationY = b.stage.height - panelV
        applyPreviewTransform()
        if (settle) {
            // Shrink the timeline viewport to the visible part so every lane stays scrollable.
            val pad = (b.stage.height - panelV).toInt()
            if (b.bottomPanel.paddingBottom != pad) b.bottomPanel.setPadding(0, 0, 0, pad)
            splitPrefs.edit().putFloat("panel_v", panelV).apply()
        }
    }

    private fun contentFrame(): View? = b.playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    /** Fits the video into the part of the preview that the panel doesn't cover. */
    private fun applyPreviewTransform() {
        val content = contentFrame() ?: return
        val full = b.playerView.height.toFloat()
        val ch = content.height.toFloat()
        if (full <= 0f || ch <= 0f || fullscreen) {
            content.scaleX = 1f; content.scaleY = 1f; content.translationY = 0f
            return
        }
        val covered = (panelV - minPanel()).coerceAtLeast(0f)
        val visible = (full - covered).coerceAtLeast(full * 0.2f)
        val s = (visible / ch).coerceAtMost(1f)
        content.pivotX = content.width / 2f
        content.pivotY = 0f
        content.scaleX = s
        content.scaleY = s
        content.translationY = (visible - ch * s) / 2f - (full - ch) / 2f
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSplit() {
        // Rounded video corners that follow the scale transform.
        contentFrame()?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
            clipToOutline = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewTransform() }
        }
        b.stage.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) b.stage.post {
                setPanel(if (panelV > 0) panelV else splitPrefs.getFloat("panel_v", b.stage.height * 0.55f), settle = true)
            }
        }
        var startY = 0f
        var startV = 0f
        b.handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startV = panelV }
                android.view.MotionEvent.ACTION_MOVE -> setPanel(startV - (e.rawY - startY))
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> setPanel(panelV, settle = true)
            }
            true
        }
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean { startV = panelV; return true }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (fullscreen) b.fullControls.visibility = if (b.fullControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                else togglePlay()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { setFullscreen(!fullscreen); return true }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
                if (fullscreen || e1 == null) return false
                setPanel(startV - (e2.rawY - e1.rawY))
                return true
            }
        })
        b.playerView.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            if ((e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) && !fullscreen) {
                setPanel(panelV, settle = true)
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
        b.toolsBar.visibility = vis
        b.fullControls.visibility = if (on) View.VISIBLE else View.GONE
        (b.previewCard.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
            val m = if (on) 0 else dp(14)
            marginStart = m; marginEnd = m; topMargin = if (on) 0 else dp(4)
            bottomMargin = if (on) 0 else dp(150)
            b.previewCard.layoutParams = this
        }
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        applyPreviewTransform()
        // Redraw the current frame at the new size (matters while paused).
        b.previewCard.post { player?.let { if (!it.isPlaying) it.seekTo(it.currentPosition) } }
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
        val prev = undoStack.removeLastOrNull() ?: return toast(L.t("ما فيه شي للتراجع", "Nothing to undo"))
        redoStack.addLast(snapshot())
        restore(prev)
        commit()
        chip(L.t("تم التراجع", "Undone"))
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
        commit()
        chip(L.t("تمت الإعادة", "Redone"))
    }

    private fun refreshAll() {
        b.title.text = "${project.name}  |  ${project.aspect.label}"
        b.undoButton.alpha = if (undoStack.isEmpty()) 0.35f else 1f
        b.redoButton.alpha = if (redoStack.isEmpty()) 0.35f else 1f
        b.previewHint.visibility = if (project.clips.isEmpty()) View.VISIBLE else View.GONE
        b.exportButton.isEnabled = project.clips.isNotEmpty()
        b.exportButton.alpha = if (project.clips.isEmpty()) 0.4f else 1f

        val v = project.clips.mapIndexed { i, it ->
            TimelineView.VBlock(project.clipStartUs(i), it.timelineUs, thumbKey(it), it.type == ClipType.IMAGE, it.transition, it.text.isNotBlank())
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
        val ready = Bridges.needed(project).mapNotNull { i -> Bridges.cached(this, project, i, w, h, false)?.let { i to it } }.toMap()
        if (ready.size < Bridges.needed(project).size) renderPreviewBridges()
        val composition = try {
            CompositionFactory.build(this, store, project, BuildOptions(w, h, false, previewTier < 1, previewTier < 2, ready))
        } catch (e: Exception) {
            toast(L.t("تعذّر تجهيز المعاينة: ${e.message}", "Couldn't prepare the preview: ${e.message}"))
            return
        }
        val p = try {
            CompositionPlayer.Builder(this).build()
        } catch (e: Exception) {
            toast(L.t("المعاينة غير مدعومة على هذا الجهاز", "Preview isn't supported on this device"))
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
                    toast(if (previewTier == 1) L.t("المعاينة بدون السرعة (التصدير يطبقها)", "Preview without speed changes (export applies them)") else L.t("معاينة مبسّطة (التصدير يطبق كل التأثيرات)", "Simplified preview (export applies all effects)"))
                    schedulePreview()
                } else {
                    toast(L.t("تعذّر تشغيل المعاينة: ${error.errorCodeName}", "Couldn't play the preview: ${error.errorCodeName}"))
                }
            }
        })
        b.playerView.player = p
        p.setComposition(composition)
        if (muted) p.volume = 0f
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
                b.bridgeStatus.text = L.t("✨ جاري تجهيز الدمج ${i + 1}…", "✨ Preparing transition ${i + 1}…")
                Fonts.apply(b.bridgeStatus)
                val f = Bridges.render(this@EditorActivity, store, project, i, w, h, false, 10_000_000)
                if (f == null) {
                    toast(L.t("تعذّر تجهيز الدمج ${i + 1}: ", "Couldn't prepare transition ${i + 1}: ") + Bridges.lastError)
                    continue
                }
                rendered = true
            }
            b.bridgeStatus.visibility = View.GONE
            if (rendered) rebuildPreview()
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
        b.durationText.text = fmt(project.durationUs)
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
        selClip = -1; selAudio = -1; category = ""
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
        chip(L.t("تم القص", "Trimmed"))
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
            isClickable = true
            background = getDrawable(R.drawable.btn_flat)
            setOnClickListener { onClick() }
        }
        item.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(if (accent) Glass.PINK else 0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(6) })
        item.addView(TextView(this).apply {
            text = label; textSize = 10.5f; gravity = Gravity.CENTER
            setTextColor(if (accent) Glass.PINK else 0x99FFFFFF.toInt()); maxLines = 1
            setPadding(0, dp(6), 0, dp(4))
        })
        b.tools.addView(item, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Small feedback chip over the preview (like "Clip split"). */
    private fun chip(msg: String) {
        val v = TextView(this).apply {
            text = "✓  $msg"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.pill_dark)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            alpha = 0f
        }
        Fonts.apply(v)
        b.chips.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        if (b.chips.childCount > 3) b.chips.removeViewAt(0)
        v.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed({ v.animate().alpha(0f).setDuration(250).withEndAction { b.chips.removeView(v) }.start() }, 1800)
    }

    /** Index of the clip under the playhead (selects it), or -1. */
    private fun clipUnderPlayhead(): Int {
        val i = project.clipAt(b.timeline.positionUs)
        if (i >= 0) { selClip = i; selAudio = -1; b.timeline.selectedClip = i; b.timeline.selectedAudio = -1 }
        return i
    }

    private fun buildTools() {
        b.tools.removeAllViews()
        val clip = project.clips.getOrNull(selClip)
        val audio = project.audios.getOrNull(selAudio)
        when {
            clip != null -> {
                b.toolsTitle.text = (if (clip.type == ClipType.IMAGE) L.t("صورة ", "Image ") else L.t("مقطع ", "Clip ")) + "${selClip + 1} • " + secs(clip.timelineUs / 1e6f)
                tool(R.drawable.i_back_arrow, L.t("رجوع", "Back")) { onEmptyTap() }
                tool(R.drawable.i_split, L.t("تقسيم", "Split")) { splitClip() }
                if (clip.type == ClipType.VIDEO) {
                    tool(R.drawable.i_trim, L.t("قص", "Trim")) { trimClip(clip) }
                    tool(R.drawable.i_speed, L.t("السرعة", "Speed")) { speedClip(clip) }
                    tool(if (clip.volume == 0f) R.drawable.i_mute else R.drawable.i_volume, L.t("الصوت", "Volume")) { volumeClip(clip) }
                    tool(R.drawable.i_wave, L.t("استخراج الصوت", "Extract audio")) { extractAudio(clip) }
                } else {
                    tool(R.drawable.i_timer, L.t("المدة", "Duration")) { imageDuration(clip) }
                }
                if (selClip < project.clips.lastIndex) tool(R.drawable.i_sparkles, L.t("انتقال", "Transition"), accent = true) { showTransition(selClip) }
                tool(R.drawable.i_sliders, L.t("فلاتر", "Filters")) { filters(clip) }
                tool(R.drawable.i_text, L.t("نص", "Text")) { textClip(clip) }
                tool(R.drawable.i_rotate, L.t("تدوير", "Rotate")) { edit { clip.rotation = (clip.rotation + 90) % 360 }; chip(L.t("تم التدوير", "Rotated")) }
                tool(R.drawable.i_flip, L.t("قلب", "Flip")) { edit { clip.mirror = !clip.mirror }; chip(L.t("تم القلب", "Flipped")) }
                tool(R.drawable.i_fit, if (clip.fill) L.t("احتواء", "Fit") else L.t("ملء الشاشة", "Fill")) { edit { clip.fill = !clip.fill } }
                tool(R.drawable.i_left, L.t("تحريك", "Move")) { moveClip(-1) }
                tool(R.drawable.i_right, L.t("تحريك", "Move")) { moveClip(1) }
                tool(R.drawable.i_copy, L.t("تكرار", "Duplicate")) { duplicateClip(); chip(L.t("تم تكرار المقطع", "Clip duplicated")) }
                tool(R.drawable.i_trash, L.t("حذف", "Delete")) { deleteClip(); chip(L.t("تم حذف المقطع", "Clip deleted")) }
            }
            audio != null -> {
                b.toolsTitle.text = "♪ ${audio.label} • " + secs(audio.lengthUs / 1e6f)
                tool(R.drawable.i_back_arrow, L.t("رجوع", "Back")) { onEmptyTap() }
                tool(R.drawable.i_volume, L.t("الصوت", "Volume")) { volumeAudio(audio) }
                tool(R.drawable.i_fade, L.t("تلاشي", "Fade")) { fadeAudio(audio) }
                tool(R.drawable.i_split, L.t("تقسيم", "Split")) { splitAudio() }
                tool(R.drawable.i_trim, L.t("قص", "Trim")) { trimAudio(audio) }
                tool(R.drawable.i_pin, L.t("نقل هنا", "Move here")) { edit { audio.startAtUs = b.timeline.positionUs } }
                tool(R.drawable.i_copy, L.t("تكرار", "Duplicate")) { duplicateAudio(audio) }
                tool(R.drawable.i_trash, L.t("حذف", "Delete")) { edit { project.audios.removeAt(selAudio); selAudio = -1 }; chip(L.t("تم حذف الصوت", "Audio deleted")) }
            }
            category == "audio" -> {
                b.toolsTitle.text = L.t("الصوت", "Audio")
                tool(R.drawable.i_back_arrow, L.t("رجوع", "Back")) { category = ""; buildTools() }
                tool(R.drawable.i_music, L.t("من الجهاز", "From device"), accent = true) { pickAudio.launch(arrayOf("audio/*")) }
                tool(R.drawable.i_wave, L.t("استخراج الصوت", "Extract audio")) {
                    val i = clipUnderPlayhead(); category = ""
                    if (i >= 0) extractAudio(project.clips[i]) else buildTools()
                }
                tool(R.drawable.i_mute, L.t("كتم الكل", "Mute clips")) {
                    edit { project.clips.forEach { it.volume = 0f } }; chip(L.t("تم كتم صوت المقاطع", "Clip audio muted"))
                }
            }
            else -> {
                b.toolsTitle.text = ""
                tool(R.drawable.i_scissors, L.t("تعديل", "Edit")) {
                    if (project.clips.isEmpty()) launchMediaPicker() else { clipUnderPlayhead(); buildTools() }
                }
                tool(R.drawable.i_music, L.t("صوت", "Audio")) { category = "audio"; buildTools() }
                tool(R.drawable.i_text, L.t("نص", "Text")) {
                    val i = clipUnderPlayhead(); if (i >= 0) { buildTools(); textClip(project.clips[i]) }
                }
                tool(R.drawable.i_sparkles, L.t("انتقالات", "Transitions")) {
                    if (project.clips.size > 1) showTransition(-1) else toast(L.t("أضف مقطعين على الأقل", "Add at least two clips"))
                }
                tool(R.drawable.i_sliders, L.t("فلاتر", "Filters")) {
                    val i = clipUnderPlayhead(); if (i >= 0) { buildTools(); filters(project.clips[i]) }
                }
                tool(R.drawable.i_ratio, L.t("النسبة", "Ratio")) { aspectSheet() }
                tool(R.drawable.i_film, L.t("إضافة", "Add")) { launchMediaPicker() }
            }
        }
        b.toolsTitle.visibility = if (b.toolsTitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        Fonts.apply(b.tools)
        Fonts.apply(b.toolsTitle)
    }

    private fun moreMenu() {
        val sheet = Sheet(this, project.name)
        sheet.option(R.drawable.i_text, L.t("إعادة التسمية", "Rename"), L.t("غيّر اسم المشروع", "Change the project name"), false) { sheet.dismiss(); renameProject() }
        sheet.option(R.drawable.i_ratio, L.t("نسبة الأبعاد", "Aspect ratio"), project.aspect.label, false) { sheet.dismiss(); aspectSheet() }
        if (project.clips.size > 1) sheet.option(R.drawable.i_sparkles, L.t("انتقال لكل المقاطع", "Transition for all clips"), L.t("طبّق نفس الانتقال على الكل", "Apply one transition everywhere"), false) { sheet.dismiss(); showTransition(-1) }
        sheet.option(R.drawable.i_send, L.t("تصدير تيك توك HD", "TikTok HD export"), "1080×1920 • H.264", false) { sheet.dismiss(); showExportWith(true) }
        sheet.show()
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
        val (dialog, _) = busyDialog(L.t("جاري إضافة ${uris.size} ملف…", "Adding ${uris.size} file(s)…"))
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { store.importMedia(project.id, it) }.getOrNull() }
            }
            dialog.dismiss()
            if (imported.isEmpty()) return@launch toast(L.t("ما قدرنا نضيف الملفات", "Couldn't add the files"))
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
            if (imported.size < uris.size) toast(L.t("بعض الملفات ما انضافت (صيغة غير مدعومة)", "Some files weren't added (unsupported format)"))
        }
    }

    private fun importAudio(uri: Uri) {
        val (dialog, _) = busyDialog(L.t("جاري إضافة الصوت…", "Adding audio…"))
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { runCatching { store.importMedia(project.id, uri) }.getOrNull() }
            dialog.dismiss()
            if (m == null) return@launch toast(L.t("ملف صوت غير مدعوم", "Unsupported audio file"))
            if (project.clips.isEmpty()) return@launch toast(L.t("أضف مقطع فيديو أو صورة أول", "Add a video or image first"))
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
        if (abs(clip.speed - 1f) > 0.01f) return toast(L.t("رجّع السرعة 1x قبل استخراج الصوت", "Set speed back to 1x before extracting audio"))
        val src = store.mediaFile(project.id, clip.file)
        if (!MediaJobs.hasAudio(src)) return toast(L.t("هذا المقطع ما فيه صوت", "This clip has no audio"))
        val (dialog, _) = busyDialog(L.t("جاري استخراج الصوت…", "Extracting audio…"))
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
                        file = name, label = L.t("صوت المقطع ${idx + 1}", "Clip ${idx + 1} audio"), sourceDurationUs = durUs.coerceAtLeast(clip.trimEndUs),
                        trimStartUs = clip.trimStartUs, trimEndUs = clip.trimEndUs.coerceAtMost(durUs.coerceAtLeast(clip.trimEndUs)),
                        startAtUs = start,
                    )
                    clip.volume = 0f
                }
                toast(L.t("تم ✅ الصوت صار مسار منفصل، وانكتم صوت المقطع", "Done ✅ The audio is now a separate track and the clip is muted"))
            } catch (e: Exception) {
                dialog.dismiss()
                out.delete()
                toast(L.t("تعذّر الاستخراج: ${e.message}", "Couldn't extract: ${e.message}"))
            }
        }
    }

    // ---------- Clip tools ----------

    private fun splitClip() {
        val pos = b.timeline.positionUs
        val i = project.clipAt(pos)
        val c = project.clips.getOrNull(i) ?: return
        val rel = pos - project.clipStartUs(i)
        if (rel < 100_000 || c.timelineUs - rel < 100_000) return toast(L.t("حرّك المؤشر لوسط المقطع عشان تقسمه", "Move the playhead inside the clip to split it"))
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
        chip(L.t("تم تقسيم المقطع", "Clip split"))
    }

    private fun trimClip(c: Clip) {
        val sheet = Sheet(this, L.t("قص المقطع", "Trim clip"))
        val dur = c.sourceDurationUs / 1_000_000f
        sheet.range(L.t("البداية والنهاية", "Start and end"), 0f, dur, c.trimStartUs / 1e6f, c.trimEndUs / 1e6f, { secs(it) }) { s, e ->
            if (e - s < 0.1f) return@range toast(L.t("المقطع قصير جداً", "Clip is too short"))
            edit { c.trimStartUs = (s * 1e6).toLong(); c.trimEndUs = (e * 1e6).toLong() }
        }
        sheet.show()
    }

    private fun imageDuration(c: Clip) {
        val sheet = Sheet(this, L.t("مدة الصورة", "Image duration"))
        sheet.slider(L.t("المدة", "Duration"), 0.5f, 15f, c.timelineUs / 1e6f, { secs(it) }, 0.1f) { v ->
            edit { val us = (v * 1e6).toLong(); c.sourceDurationUs = us; c.trimStartUs = 0; c.trimEndUs = us }
        }
        sheet.show()
    }

    private fun speedClip(c: Clip) {
        val sheet = Sheet(this, L.t("السرعة", "Speed"))
        val presets = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f)
        lateinit var slider: com.google.android.material.slider.Slider
        sheet.chips(null, presets.map { "${it}x" }, presets.indexOfFirst { abs(it - c.speed) < 0.01f }) { i ->
            slider.value = presets[i]
            edit { c.speed = presets[i] }
        }
        slider = sheet.slider(L.t("سرعة مخصصة", "Custom speed"), 0.25f, 4f, c.speed, { String.format(Locale.US, "%.2fx", it) }, 0.05f) { v ->
            edit { c.speed = v }
        }
        sheet.text(L.t("الصوت يتسرّع مع الفيديو. مدة المقطع الحالية: ${secs(c.timelineUs / 1e6f)}", "Audio is sped up with the video. Current length: ${secs(c.timelineUs / 1e6f)}"))
        sheet.show()
    }

    private fun volumeClip(c: Clip) {
        val sheet = Sheet(this, L.t("صوت المقطع", "Clip audio"))
        lateinit var s: com.google.android.material.slider.Slider
        s = sheet.slider(L.t("مستوى الصوت", "Volume"), 0f, 2f, c.volume, { "${(it * 100).toInt()}%" }, 0.05f) { v -> edit { c.volume = v } }
        sheet.button(if (c.volume == 0f) L.t("إلغاء الكتم", "Unmute") else L.t("كتم الصوت 🔇", "Mute 🔇"), outlined = true) {
            val v = if (c.volume == 0f) 1f else 0f
            s.value = v
            edit { c.volume = v }
            sheet.dismiss()
        }
        sheet.button(L.t("تطبيق المستوى على كل المقاطع", "Apply volume to all clips"), outlined = true) {
            edit { project.clips.forEach { it.volume = c.volume } }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun filters(c: Clip) {
        val sheet = Sheet(this, L.t("فلاتر وتعديل الألوان", "Filters & color"))
        val pct: (Float) -> String = { "${(it * 100).toInt()}" }
        val presets = listOf(L.t("بدون", "None") to floatArrayOf(0f, 0f, 0f), L.t("حيوي", "Vivid") to floatArrayOf(0.03f, 0.15f, 0.35f),
            L.t("سينمائي", "Cinematic") to floatArrayOf(-0.05f, 0.25f, -0.15f), L.t("أبيض وأسود", "Black & white") to floatArrayOf(0f, 0.15f, -1f),
            L.t("ناعم", "Soft") to floatArrayOf(0.08f, -0.15f, -0.1f), L.t("درامي", "Dramatic") to floatArrayOf(-0.1f, 0.4f, 0.1f))
        val br = arrayOfNulls<com.google.android.material.slider.Slider>(3)
        sheet.chips(L.t("جاهزة", "Presets"), presets.map { it.first }, -1) { i ->
            val v = presets[i].second
            br[0]?.value = v[0]; br[1]?.value = v[1]; br[2]?.value = v[2]
            edit { c.brightness = v[0]; c.contrast = v[1]; c.saturation = v[2] }
        }
        br[0] = sheet.slider(L.t("السطوع", "Brightness"), -0.5f, 0.5f, c.brightness, pct, 0.01f) { v -> edit { c.brightness = v } }
        br[1] = sheet.slider(L.t("التباين", "Contrast"), -0.5f, 0.6f, c.contrast, pct, 0.01f) { v -> edit { c.contrast = v } }
        br[2] = sheet.slider(L.t("التشبّع", "Saturation"), -1f, 1f, c.saturation, pct, 0.01f) { v -> edit { c.saturation = v } }
        sheet.button(L.t("تطبيق على كل المقاطع", "Apply to all clips"), outlined = true) {
            edit { project.clips.forEach { it.brightness = c.brightness; it.contrast = c.contrast; it.saturation = c.saturation } }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun textClip(c: Clip) {
        val sheet = Sheet(this, L.t("نص على المقطع", "Text on clip"))
        val input = sheet.input(L.t("اكتب النص", "Type your text"), c.text)
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
        sheet.chips(L.t("الخط", "Font"), listOf(L.t("الافتراضي", "Default")) + fonts.map { it.name } + listOf(L.t("＋ رفع خط", "＋ Upload font")), currentFont.coerceAtLeast(0)) { i ->
            if (i == fonts.size + 1) {
                fontTargetClip = c.id
                sheet.dismiss()
                pickFont.launch(Fonts.PICK_TYPES)
            } else { fontChoice = i; previewFont() }
        }
        previewFont()
        sheet.chips(L.t("المكان", "Position"), TextPos.entries.map { it.label }, pos.ordinal) { pos = TextPos.entries[it] }
        sheet.slider(L.t("الحجم", "Size"), 0.4f, 3f, scale, { "${(it * 100).toInt()}%" }, 0.05f) { scale = it }
        sheet.text(L.t("اللون", "Color"))
        val palette = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF2D55.toInt(), 0xFF25F4EE.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        sheet.colors(color, palette) { color = it; toast(L.t("تم اختيار اللون", "Color selected")) }
        sheet.button(L.t("حفظ النص", "Save text")) {
            val chosen = if (fontChoice <= 0) "" else fonts[fontChoice - 1].file
            edit {
                c.text = input.text?.toString().orEmpty()
                c.textPos = pos; c.textColor = color; c.textScale = scale
            }
            if (chosen.isEmpty()) { if (c.textFont.isNotEmpty()) edit { c.textFont = "" } }
            else if (c.textFont != "f_$chosen") setClipFont(c, chosen)
            sheet.dismiss()
        }
        if (c.text.isNotEmpty()) sheet.button(L.t("حذف النص", "Delete text"), outlined = true) { edit { c.text = "" }; sheet.dismiss() }
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
        val sheet = Sheet(this, L.t("نسبة الأبعاد", "Aspect ratio"))
        sheet.chips(null, Aspect.entries.map { it.label + if (it == Aspect.R9_16) L.t(" (تيك توك)", " (TikTok)") else "" }, project.aspect.ordinal) { i ->
            edit { project.aspect = Aspect.entries[i] }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun showTransition(index: Int) {
        val all = index < 0
        val clip = if (all) project.clips.firstOrNull() else project.clips.getOrNull(index)
        if (clip == null) return
        val sheet = Sheet(this, if (all) L.t("انتقال لكل المقاطع", "Transition for all clips") else L.t("الانتقال بين ${index + 1} و ${index + 2}", "Transition between ${index + 1} and ${index + 2}"),
            L.t("اختر النوع والمدة، والمعاينة تتحدث تلقائياً", "Pick a type and duration — the preview updates automatically"))
        var kind = clip.transition
        var durUs = clip.transitionUs
        val overlapKinds = Transition.entries.filter { it.overlap }
        val motionKinds = Transition.entries.filter { !it.overlap }
        lateinit var g1: com.google.android.material.chip.ChipGroup
        lateinit var g2: com.google.android.material.chip.ChipGroup
        g1 = sheet.chips(L.t("✦ دمج احترافي (المقطعين فوق بعض)", "✦ Pro overlap (clips blend together)"), overlapKinds.map { it.label }, overlapKinds.indexOf(kind), required = false) {
            kind = overlapKinds[it]; g2.clearCheck()
        }
        g2 = sheet.chips(L.t("حركات القطع", "Cut motions"), motionKinds.map { it.label }, motionKinds.indexOf(kind), required = false) {
            kind = motionKinds[it]; g1.clearCheck()
        }
        sheet.slider(L.t("المدة", "Duration"), 0.2f, 2f, durUs / 1e6f, { secs(it) }, 0.1f) { durUs = (it * 1e6).toLong() }
        if (!com.mawj.editor.model.Caps.overlapSupported) sheet.note(L.t("ℹ️ جهازك ما يدعم الدمج المتداخل؛ بيتحول لتلاشي ناعم.", "ℹ️ Your device can't overlap clips; a smooth fade is used instead."))
        sheet.button(L.t("تطبيق", "Apply")) {
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
        if (!all) sheet.button(L.t("تطبيق على كل المقاطع", "Apply to all clips"), outlined = true) {
            edit { project.clips.dropLast(1).forEach { it.transition = kind; it.transitionUs = durUs } }
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Audio tools ----------

    private fun volumeAudio(a: AudioClip) {
        val sheet = Sheet(this, L.t("مستوى الصوت", "Volume"))
        sheet.slider(L.t("مستوى «${a.label}»", "“${a.label}” volume"), 0f, 2f, a.volume, { "${(it * 100).toInt()}%" }, 0.05f) { v -> edit { a.volume = v } }
        sheet.text(L.t("صوت المقاطع الأصلي تتحكم فيه من تحديد المقطع ← الصوت", "Original clip audio is controlled by selecting the clip → Volume"))
        sheet.show()
    }

    private fun fadeAudio(a: AudioClip) {
        val sheet = Sheet(this, L.t("تلاشي الصوت", "Audio fades"))
        val max = (a.lengthUs / 2e6f).coerceAtMost(10f).coerceAtLeast(0.2f)
        sheet.slider(L.t("ظهور تدريجي", "Fade in"), 0f, max, (a.fadeInUs / 1e6f).coerceAtMost(max), { secs(it) }, 0f) { v -> edit { a.fadeInUs = (v * 1e6).toLong() } }
        sheet.slider(L.t("اختفاء تدريجي", "Fade out"), 0f, max, (a.fadeOutUs / 1e6f).coerceAtMost(max), { secs(it) }, 0f) { v -> edit { a.fadeOutUs = (v * 1e6).toLong() } }
        sheet.show()
    }

    private fun trimAudio(a: AudioClip) {
        val sheet = Sheet(this, L.t("قص الصوت", "Trim audio"))
        sheet.range(L.t("الجزء المستخدم", "Used part"), 0f, a.sourceDurationUs / 1e6f, a.trimStartUs / 1e6f, a.trimEndUs / 1e6f, { secs(it) }) { s, e ->
            if (e - s < 0.1f) return@range toast(L.t("قصير جداً", "Too short"))
            edit { a.trimStartUs = (s * 1e6).toLong(); a.trimEndUs = (e * 1e6).toLong() }
        }
        sheet.show()
    }

    private fun splitAudio() {
        val a = project.audios.getOrNull(selAudio) ?: return
        val rel = b.timeline.positionUs - a.startAtUs
        if (rel < 100_000 || a.lengthUs - rel < 100_000) return toast(L.t("حرّك المؤشر لوسط الصوت عشان تقسمه", "Move the playhead inside the audio to split it"))
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

    private fun secs(v: Float) = String.format(Locale.US, L.t("%.1fث", "%.1fs"), v)

    // ---------- Project ----------

    private fun renameProject() {
        val sheet = Sheet(this, L.t("اسم المشروع", "Project name"))
        val input = sheet.input(L.t("الاسم", "Name"), project.name)
        sheet.button(L.t("حفظ", "Save")) {
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
        val sheet = Sheet(this, L.t("تصدير", "Export"), L.t("اختر وين بتنشر المقطع", "Where will you post it?"))
        fun select(t: Boolean) {
            sheet.dismiss()
            showExportWith(t)
        }
        sheet.option(R.drawable.i_send, L.t("تيك توك HD  ⭐", "TikTok HD  ⭐"), L.t("1080×1920 • H.264 • معدل بت عالي • بدون فقد بعد الرفع", "1080×1920 • H.264 • high bitrate • no loss after upload"), true) { select(true) }
        sheet.option(R.drawable.i_sliders, L.t("مخصص", "Custom"), L.t("اختر الدقة (حتى 4K) والجودة والترميز", "Choose resolution (up to 4K), quality and codec"), false) { select(false) }
        sheet.show()
    }

    private fun showExportWith(tiktok: Boolean) {
        if (tiktok) {
            val sheet = Sheet(this, L.t("تيك توك HD", "TikTok HD"), L.t("إعدادات موج HD الجاهزة لأعلى جودة على تيك توك", "Mawj HD presets for the best quality on TikTok"))
            sheet.note(L.t("✅ دقة 1080p بالضبط (اللي يعرضها تيك توك)\n✅ ترميز H.264 بمعدل بت عالي جداً\n✅ تحويل HDR إلى SDR عشان الألوان ما تبهت\n✅ بعد الحفظ نوديك لتيك توك خطوة بخطوة", "✅ Exactly 1080p (what TikTok displays)\n✅ H.264 at a very high bitrate\n✅ HDR converted to SDR so colors don't wash out\n✅ After saving, we guide you to TikTok step by step"))
            if (project.aspect != Aspect.R9_16) sheet.note(L.t("⚠️ نسبة المشروع ${project.aspect.label} — الأفضل للتيك توك 9:16 (غيرها من أداة «النسبة»).", "⚠️ Project ratio is ${project.aspect.label} — 9:16 is best for TikTok (change it with “Ratio”)."))
            sheet.button(L.t("صدّر لتيك توك", "Export for TikTok")) {
                sheet.dismiss()
                export(1080, 0.5, false, true)
            }
            sheet.show()
            return
        }
        val sheet = Sheet(this, L.t("تصدير مخصص", "Custom export"))
        val res = listOf(720, 1080, 1440, 2160)
        var r = 1
        var quality = 1
        var hevc = false
        sheet.chips(L.t("الدقة", "Resolution"), listOf("720p", "1080p", "2K", "4K"), r) { r = it }
        sheet.chips(L.t("الجودة", "Quality"), listOf(L.t("عالية", "High"), L.t("قصوى", "Max"), L.t("استديو", "Studio")), quality) { quality = it }
        sheet.chips(L.t("الترميز", "Codec"), listOf(L.t("H.264 (متوافق)", "H.264 (compatible)"), L.t("H.265 (أصغر)", "H.265 (smaller)")), 0) { hevc = it == 1 }
        sheet.button(L.t("تصدير وحفظ", "Export & save")) {
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
        val label = TextView(this).apply { text = L.t("تجهيز…", "Preparing…"); setPadding(0, dp(10), 0, 0); setTextColor(0xCCFFFFFF.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(-1, -2)); addView(label)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (tiktok) L.t("تصدير تيك توك HD…", "Exporting TikTok HD…") else L.t("جاري التصدير…", "Exporting…")).setView(box).setCancelable(false)
            .setNegativeButton(L.t("إلغاء", "Cancel")) { _, _ -> exportJob?.cancel() }
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
                    label.text = L.t("تجهيز الدمج ${n + 1}/${needed.size}…", "Preparing transition ${n + 1}/${needed.size}…")
                    bar.setProgressCompat(n * 100 / needed.size.coerceAtLeast(1), true)
                    Bridges.render(this@EditorActivity, store, project, i, w, h, true, bitrate)?.let { bridges[i] = it }
                }
                if (bridges.size < needed.size) toast(L.t("بعض الانتقالات ما تجهزت: ", "Some transitions failed: ") + Bridges.lastError)
                // 2) Export the timeline.
                val comp = CompositionFactory.build(this@EditorActivity, store, project, BuildOptions(w, h, true, bridges = bridges))
                MediaJobs.exportVideo(this@EditorActivity, comp, bitrate, out, hevc) { p ->
                    bar.setProgressCompat(p, true); label.text = "$p%"
                }
                label.text = L.t("جاري الحفظ في الاستديو…", "Saving to gallery…")
                val name = (if (tiktok) "MawjTikTok_" else "MawjEdit_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val uri = withContext(Dispatchers.IO) { MediaJobs.saveToGallery(this@EditorActivity, out, name) }
                val sizeMb = out.length() / 1e6
                out.delete()
                dialog.dismiss()
                if (tiktok) showTikTokGuide(uri) else showExportDone(uri, sizeMb, w, h)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss(); out.delete(); toast(L.t("تم إلغاء التصدير", "Export cancelled"))
            } catch (e: Exception) {
                dialog.dismiss(); out.delete()
                val d = MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(L.t("تعذّر التصدير", "Export failed"))
                    .setMessage(L.t("${e.message}\n\nجرّب دقة أقل (1080p).", "${e.message}\n\nTry a lower resolution (1080p)."))
                    .setPositiveButton(L.t("حسناً", "OK"), null).show()
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
        else { toast(L.t("تيك توك غير مثبت", "TikTok isn't installed")); startActivity(Intent.createChooser(send, L.t("مشاركة", "Share"))) }
    }

    /** Step-by-step guide so TikTok keeps the HD quality. */
    private fun showTikTokGuide(uri: Uri) {
        val sheet = Sheet(this, L.t("جاهز للرفع ✅", "Ready to post ✅"), L.t("انحفظ في Movies/Mawj Edit • اتبع الخطوات عشان تيك توك ما ينزّل الجودة", "Saved to Movies/Mawj Edit • follow these steps so TikTok keeps the quality"))
        sheet.note(L.t("١. اضغط «افتح تيك توك» تحت — المقطع يفتح فيه مباشرة.", "1. Tap “Open TikTok” below — the video opens there directly."))
        sheet.note(L.t("٢. اضغط «التالي» إلى أن توصل لصفحة النشر (إضافة وصف).", "2. Tap “Next” until you reach the post screen (add description)."))
        sheet.note(L.t("٣. اضغط «المزيد من الخيارات» ← فعّل «السماح بالتحميلات عالية الجودة» (يصير أزرق).", "3. Tap “More options” → turn on “Allow high-quality uploads” (turns blue)."))
        sheet.note(L.t("٤. لا تضيف فلاتر أو تأثيرات من تيك توك، وانشر وأنت على واي فاي.", "4. Don't add TikTok filters or effects, and post on Wi-Fi."))
        sheet.note(L.t("💡 أول دقايق بعد النشر ممكن تطلع الجودة أقل — تيك توك يجهّز نسخة HD بعدها بشوي.", "💡 Quality may look lower for the first minutes — TikTok prepares the HD version shortly after."))
        sheet.button(L.t("افتح تيك توك", "Open TikTok")) { sheet.dismiss(); openTikTok(uri) }
        sheet.button(L.t("مشاركة لتطبيق ثاني", "Share to another app"), outlined = true) { startActivity(Intent.createChooser(shareIntent(uri), L.t("مشاركة", "Share"))) }
        sheet.show()
    }

    private fun showExportDone(uri: Uri, sizeMb: Double, w: Int, h: Int) {
        val sheet = Sheet(this, L.t("تم التصدير ✅", "Export complete ✅"), String.format(Locale.US, "Movies/Mawj Edit • %d×%d • %.1f MB", w, h, sizeMb))
        sheet.button(L.t("ارفع على تيك توك (بالخطوات)", "Post to TikTok (guided)")) { sheet.dismiss(); showTikTokGuide(uri) }
        sheet.button(L.t("مشاركة", "Share"), outlined = true) { startActivity(Intent.createChooser(shareIntent(uri), L.t("مشاركة", "Share"))) }
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
