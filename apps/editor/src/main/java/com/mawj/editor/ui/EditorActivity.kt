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
import com.mawj.editor.engine.Proxies
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
import kotlinx.coroutines.asCoroutineDispatcher
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
    /** One decoder at a time for thumbnails, so they never compete with playback. */
    private val thumbDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
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
                seekPlayer(us); b.timeline.setPosition(us); updateTime()
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
    private var fsProgress = 0f
    private var fsAnim: android.animation.ValueAnimator? = null
    private var muted = false
    private var seekPending = false
    private var playFromUs = 0L
    private var playStartedAt = 0L
    private val previewShort get() = splitPrefs.getInt("preview_q", 540)
    private var category = ""
    private val splitPrefs by lazy { getSharedPreferences("editor_ui", MODE_PRIVATE) }

    private var detector: android.view.GestureDetector? = null

    // Two-finger framing of the selected clip on the preview
    private var pinching = false
    private var pinchScale = 1f
    private var pinchFx = 0f; private var pinchFy = 0f
    private var pinchDx = 0f; private var pinchDy = 0f

    private fun commitPinch() {
        if (!pinching) return
        pinching = false
        val c = project.clips.getOrNull(selClip)
        val content = contentFrame()
        if (c != null && content != null && content.width > 0) {
            val w = content.width * content.scaleX; val h = content.height * content.scaleY
            edit {
                c.zoom = (c.zoom * pinchScale).coerceIn(0.3f, 5f)
                c.offX = (c.offX + pinchDx / w).coerceIn(-1.5f, 1.5f)
                c.offY = (c.offY + pinchDy / h).coerceIn(-1.5f, 1.5f)
            }
            chip(L.t("الإطار: ", "Frame: ") + "${(c.zoom * 100).toInt()}%")
        }
        pinchScale = 1f; pinchDx = 0f; pinchDy = 0f
        applyPreviewTransform()
    }

    // Hold to play at 2x (full screen)
    private var fastForward = false
    private var ffJob: Job? = null

    private fun startFastForward() {
        val p = player ?: return
        fastForward = true
        b.speedBadge.visibility = View.VISIBLE
        b.speedBadge.alpha = 0f
        b.speedBadge.animate().alpha(1f).setDuration(120).start()
        performHaptic()
        if (p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            p.setPlaybackSpeed(2f)
            if (!p.isPlaying) p.play()
        } else {
            // Fallback: play and keep skipping ahead so time advances at 2x.
            if (!p.isPlaying) p.play()
            ffJob = lifecycleScope.launch {
                while (fastForward) {
                    kotlinx.coroutines.delay(250)
                    val pp = player ?: break
                    val next = pp.currentPosition + 250
                    if (next * 1000 >= project.durationUs) break
                    pp.seekTo(next)
                }
            }
        }
    }

    private fun stopFastForward() {
        if (!fastForward) return
        fastForward = false
        ffJob?.cancel(); ffJob = null
        player?.let { if (it.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) it.setPlaybackSpeed(1f) }
        b.speedBadge.animate().alpha(0f).setDuration(150).withEndAction { b.speedBadge.visibility = View.GONE }.start()
    }

    private fun performHaptic() = b.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

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

    /**
     * Places the video: fitted into the part of the preview the panel doesn't cover (normal), or filling the
     * whole screen (full screen). In between it's interpolated, so entering full screen is a smooth zoom.
     */
    private fun applyPreviewTransform() {
        val content = contentFrame() ?: return
        val full = b.playerView.height.toFloat()
        val cw = content.width.toFloat()
        val ch = content.height.toFloat()
        if (full <= 0f || ch <= 0f || cw <= 0f) return
        // Normal state
        val covered = (panelV - minPanel()).coerceAtLeast(0f)
        val visible = (full - covered).coerceAtLeast(full * 0.2f)
        val s0 = (visible / ch).coerceAtMost(1f)
        val ty0 = (visible - ch * s0) / 2f - (full - ch) / 2f
        // Full screen state (relative to the activity root)
        val pv = IntArray(2); val rt = IntArray(2)
        b.playerView.getLocationInWindow(pv); b.root.getLocationInWindow(rt)
        val absLeft = pv[0] - rt[0] + content.left
        val absTop = pv[1] - rt[1] + content.top
        val rw = b.root.width.toFloat(); val rh = b.root.height.toFloat()
        val s1 = minOf(rw / cw, rh / ch)
        val tx1 = rw / 2f - (absLeft + cw / 2f)
        val ty1 = (rh - ch * s1) / 2f - absTop
        val t = fsProgress
        content.pivotX = cw / 2f
        content.pivotY = 0f
        val sc = s0 + (s1 - s0) * t
        val ps = if (pinching) pinchScale else 1f
        content.scaleX = sc * ps; content.scaleY = sc * ps
        content.translationX = tx1 * t + (if (pinching) pinchDx else 0f)
        content.translationY = ty0 + (ty1 - ty0) * t + (if (pinching) pinchDy else 0f)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSplit() {
        // Rounded video corners that follow the scale transform.
        contentFrame()?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
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
        detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean { startV = panelV; return true }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (fullscreen) b.fullControls.animate().alpha(if (b.fullControls.alpha > 0.5f) 0f else 1f).setDuration(180).start()
                else togglePlay()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { setFullscreen(!fullscreen); return true }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
                if (fullscreen || e1 == null || e2.pointerCount > 1 || pinching) return false
                setPanel(startV - (e2.rawY - e1.rawY))
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent) {
                if (fullscreen) startFastForward()
            }
        })
        val pinch = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                if (fullscreen || selClip < 0) return false
                pinching = true; pinchScale = 1f; pinchFx = d.focusX; pinchFy = d.focusY; pinchDx = 0f; pinchDy = 0f
                return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                pinchScale = (pinchScale * d.scaleFactor).coerceIn(0.3f, 5f)
                pinchDx = d.focusX - pinchFx; pinchDy = d.focusY - pinchFy
                applyPreviewTransform()
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { commitPinch() }
        })
        val touch = View.OnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            detector?.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                stopFastForward()
                if (!fullscreen) setPanel(panelV, settle = true)
            }
            true
        }
        b.root.setOnTouchListener { v, e -> if (fullscreen) touch.onTouch(v, e) else false }
        b.playerView.setOnTouchListener(touch)
    }

    /** Smooth, GPU-only zoom into / out of full screen (no relayout, so the video never stutters). */
    private fun setFullscreen(on: Boolean) {
        if (project.clips.isEmpty() || on == fullscreen) return
        fullscreen = on
        fsAnim?.cancel()
        val bars = listOf<View>(b.topBar, b.bottomPanel, b.toolsBar)
        val light = resources.getBoolean(R.bool.light_bars)
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            b.fullControls.visibility = View.VISIBLE
            b.fsBackdrop.visibility = View.VISIBLE
            b.stage.translationZ = 8f
            ctl.isAppearanceLightStatusBars = false
            ctl.isAppearanceLightNavigationBars = false
        } else {
            bars.forEach { it.visibility = View.VISIBLE }
            ctl.isAppearanceLightStatusBars = light
            ctl.isAppearanceLightNavigationBars = light
        }
        val from = fsProgress
        val to = if (on) 1f else 0f
        fsAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = (380 * kotlin.math.abs(to - from)).toLong().coerceAtLeast(120)
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            val baseBar = col(R.color.bg)
            val baseNav = col(R.color.bar)
            val argb = android.animation.ArgbEvaluator()
            addUpdateListener {
                fsProgress = it.animatedValue as Float
                applyPreviewTransform()
                bars.forEach { v -> v.alpha = 1f - fsProgress }
                b.fullControls.alpha = fsProgress
                b.fsBackdrop.alpha = fsProgress
                window.statusBarColor = argb.evaluate(fsProgress, baseBar, android.graphics.Color.BLACK) as Int
                window.navigationBarColor = argb.evaluate(fsProgress, baseNav, android.graphics.Color.BLACK) as Int
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fsProgress >= 0.999f) bars.forEach { it.visibility = View.INVISIBLE }
                    if (fsProgress <= 0.001f) {
                        b.fullControls.visibility = View.GONE
                        b.fsBackdrop.visibility = View.GONE
                        b.stage.translationZ = 0f
                    }
                }
            })
            start()
        }
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
        val a = project.audios.map { au ->
            TimelineView.ABlock(au.startAtUs, au.timelineUs, au.label).also { blk ->
                blk.beats = au.beats.filter { it in au.trimStartUs..au.trimEndUs }.map { au.startAtUs + ((it - au.trimStartUs) / au.speed).toLong() }
            }
        }
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

        // Heavy clips (4K / HDR) get a light proxy first, so the preview is smooth and reliable.
        if (proxyJob?.isActive == true) return
        if (Proxies.missing(this, store, project).isNotEmpty()) { prepareProxies(); return }

        val (w, h) = project.aspect.canvas(previewShort)
        val ready = Bridges.needed(project).mapNotNull { i -> Bridges.cached(this, project, i, w, h, false)?.let { i to it } }.toMap()
        if (ready.size < Bridges.needed(project).size) renderPreviewBridges()
        val composition = try {
            CompositionFactory.build(this, store, project, BuildOptions(w, h, false, previewTier < 1, previewTier < 2, ready, useProxies = true))
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
                if (playbackState == Player.STATE_READY) seekPending = false
                if (playbackState == Player.STATE_ENDED) {
                    p.pause()
                    b.timeline.setPosition(project.durationUs)
                    updateTime()
                    updatePlayIcon()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekPending = false
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = errText(error)
                if (previewTier < 2) {
                    previewTier++
                    schedulePreview()
                } else {
                    toast(L.t("تعذّر تشغيل المعاينة: ", "Couldn't play the preview: ") + detail)
                }
            }
        })
        b.playerView.player = p
        p.setComposition(composition)
        if (muted) p.volume = 0f
        p.prepare()
        p.seekTo(b.timeline.positionUs / 1000)
        seekPending = true
        player = p
        if (wasPlaying) p.play()
        updatePlayIcon()
    }

    private var proxyJob: Job? = null

    private fun errText(e: Throwable): String =
        generateSequence(e) { it.cause }.mapNotNull { it.message }.distinct().take(3).joinToString(" ← ")

    /** Creates 720p proxies for heavy clips (player released meanwhile so decoders are free), then previews. */
    private fun prepareProxies() {
        if (proxyJob?.isActive == true) return
        val todo = Proxies.missing(this, store, project)
        proxyJob = lifecycleScope.launch {
            b.bridgeStatus.visibility = View.VISIBLE
            todo.forEachIndexed { n, c ->
                b.bridgeStatus.text = L.t("⚡ تجهيز معاينة سريعة ${n + 1}/${todo.size}…", "⚡ Preparing smooth preview ${n + 1}/${todo.size}…")
                Fonts.apply(b.bridgeStatus)
                Proxies.make(this@EditorActivity, store, project, c)
            }
            b.bridgeStatus.visibility = View.GONE
            previewTier = 0
            rebuildPreview()
        }
    }

    /** Renders missing overlap transitions in the background, then refreshes the preview. */
    private fun renderPreviewBridges() {
        if (bridgeJob?.isActive == true) return
        val (w, h) = project.aspect.canvas(previewShort)
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
            if (!b.timeline.isUserScrubbing && !seekPending) {
                val pos = p.currentPosition * 1000
                // Guard: if the player snapped back to the start right after "play", re-apply the seek.
                if (System.currentTimeMillis() - playStartedAt < 800 && playFromUs > 500_000 && pos < playFromUs - 400_000) {
                    seekPlayer(playFromUs)
                } else {
                    b.timeline.setPosition(pos)
                    updateTime()
                }
            }
            if (p.isPlaying) handler.postDelayed(this, 16)
        }
    }

    private fun seekPlayer(us: Long) {
        val p = player ?: return
        p.seekTo((us / 1000).coerceAtLeast(0))
        seekPending = true
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            var from = b.timeline.positionUs
            if (from >= project.durationUs - 50_000) { from = 0; b.timeline.setPosition(0) }
            // Always start exactly where the playhead is.
            if (kotlin.math.abs(p.currentPosition * 1000 - from) > 40_000) seekPlayer(from)
            playFromUs = from
            playStartedAt = System.currentTimeMillis()
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
            if (now - lastSeekAt > 50) { seekPlayer(us); lastSeekAt = now }
        }
        updateTime()
    }

    override fun onScrubEnd() {
        seekPlayer(b.timeline.positionUs)
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

    override fun onSeekTo(us: Long) {
        player?.pause()
        b.timeline.jumpTo(us)
        seekPlayer(us)
        updateTime()
    }

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
                val d = deltaUs.coerceIn(-minOf((a.trimStartUs / a.speed).toLong(), a.startAtUs), a.timelineUs - 200_000)
                a.trimStartUs += (d * a.speed).toLong(); a.startAtUs += d
            } else {
                a.trimEndUs = (a.trimEndUs + (deltaUs * a.speed).toLong()).coerceIn(a.trimStartUs + 200_000, a.sourceDurationUs)
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
            imageTintList = android.content.res.ColorStateList.valueOf(if (accent) Glass.PINK else col(R.color.icon))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(6) })
        item.addView(TextView(this).apply {
            text = label; textSize = 10.5f; gravity = Gravity.CENTER
            setTextColor(if (accent) Glass.PINK else col(R.color.text2)); maxLines = 1
            setPadding(0, dp(6), 0, dp(4))
        })
        b.tools.addView(item, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Small feedback chip over the preview (like "Clip split"). */
    private fun chip(msg: String) {
        val v = TextView(this).apply {
            text = "✓  $msg"
            textSize = 12f
            setTextColor(col(R.color.text))
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
                tool(R.drawable.i_wand, L.t("حركة", "Animate")) { animations(clip) }
                tool(R.drawable.i_text, L.t("نص", "Text")) { textClip(clip) }
                tool(R.drawable.i_sparkles, L.t("تحسين ✨", "Enhance ✨")) { autoEnhance(listOf(clip)) }
                tool(R.drawable.i_fit, L.t("تأطير ذكي", "Reframe")) { smartReframe(listOf(clip)) }
                if (clip.type == ClipType.VIDEO) tool(R.drawable.i_scissors, L.t("حذف السكتات", "Cut silences")) { removeSilences(listOf(selClip)) }
                tool(R.drawable.i_rotate, L.t("تدوير", "Rotate")) { edit { clip.rotation = (clip.rotation + 90) % 360 }; chip(L.t("تم التدوير", "Rotated")) }
                tool(R.drawable.i_flip, L.t("قلب", "Flip")) { edit { clip.mirror = !clip.mirror }; chip(L.t("تم القلب", "Flipped")) }
                tool(R.drawable.i_fit, if (clip.fill) L.t("احتواء", "Fit") else L.t("ملء الشاشة", "Fill")) { edit { clip.fill = !clip.fill } }
                tool(R.drawable.i_ratio, L.t("الإطار والخلفية", "Frame & canvas"), accent = true) { frameSheet(clip) }
                if (clip.type == ClipType.VIDEO) tool(R.drawable.i_timer, L.t("تجميد إطار", "Freeze frame")) { freezeFrame() }
                tool(R.drawable.i_copy, L.t("نسخ التعديلات", "Copy edits")) { copyEdits(clip) }
                if (copiedEdits != null) tool(R.drawable.i_check, L.t("لصق التعديلات", "Paste edits")) { pasteEdits(listOf(clip)) }
                tool(R.drawable.i_left, L.t("تحريك", "Move")) { moveClip(-1) }
                tool(R.drawable.i_right, L.t("تحريك", "Move")) { moveClip(1) }
                tool(R.drawable.i_copy, L.t("تكرار", "Duplicate")) { duplicateClip(); chip(L.t("تم تكرار المقطع", "Clip duplicated")) }
                tool(R.drawable.i_trash, L.t("حذف", "Delete")) { deleteClip(); chip(L.t("تم حذف المقطع", "Clip deleted")) }
            }
            audio != null -> {
                b.toolsTitle.text = "♪ ${audio.label} • " + secs(audio.timelineUs / 1e6f)
                tool(R.drawable.i_back_arrow, L.t("رجوع", "Back")) { onEmptyTap() }
                tool(R.drawable.i_volume, L.t("الصوت", "Volume")) { volumeAudio(audio) }
                tool(R.drawable.i_fade, L.t("تلاشي", "Fade")) { fadeAudio(audio) }
                tool(R.drawable.i_split, L.t("تقسيم", "Split")) { splitAudio() }
                tool(R.drawable.i_trim, L.t("قص", "Trim")) { trimAudio(audio) }
                tool(R.drawable.i_pin, L.t("نقل هنا", "Move here")) { edit { audio.startAtUs = b.timeline.positionUs } }
                tool(R.drawable.i_music, L.t("مزامنة الإيقاع", "Beat sync"), accent = true) { beatSync() }
                tool(R.drawable.i_copy, L.t("تكرار", "Duplicate")) { duplicateAudio(audio) }
                tool(R.drawable.i_trash, L.t("حذف", "Delete")) { edit { project.audios.removeAt(selAudio); selAudio = -1 }; chip(L.t("تم حذف الصوت", "Audio deleted")) }
            }
            category == "audio" -> {
                b.toolsTitle.text = L.t("الصوت", "Audio")
                tool(R.drawable.i_back_arrow, L.t("رجوع", "Back")) { category = ""; buildTools() }
                tool(R.drawable.i_music, L.t("من الجهاز", "From device"), accent = true) { pickAudio.launch(arrayOf("audio/*")) }
                tool(R.drawable.i_volume, L.t("تسجيل صوت", "Voiceover")) { startVoiceover() }
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
                tool(R.drawable.i_wand, L.t("ذكاء ✨", "AI ✨"), accent = true) {
                    if (project.clips.isEmpty()) launchMediaPicker() else aiSheet()
                }
                tool(R.drawable.i_sliders, L.t("فلاتر", "Filters")) {
                    val i = clipUnderPlayhead(); if (i >= 0) { buildTools(); filters(project.clips[i]) }
                }
                tool(R.drawable.i_layers, L.t("حركة", "Animate")) {
                    val i = clipUnderPlayhead(); if (i >= 0) { buildTools(); animations(project.clips[i]) }
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
        sheet.option(R.drawable.i_export, L.t("حفظ الإطار الحالي كصورة", "Save current frame as photo"), L.t("يحفظ في الاستديو بجودة المعاينة", "Saved to gallery at preview quality"), false) { sheet.dismiss(); saveFrame() }
        if (copiedEdits != null && project.clips.isNotEmpty()) sheet.option(R.drawable.i_check, L.t("لصق التعديلات على كل المقاطع", "Paste edits to all clips"), "", false) {
            sheet.dismiss(); pasteEdits(project.clips.toList())
        }
        val qs = listOf(540, 720, 1080)
        sheet.chips(L.t("جودة المعاينة (الأقل = أنعم)", "Preview quality (lower = smoother)"),
            listOf(L.t("سلسة 540p", "Smooth 540p"), "720p", "1080p"), qs.indexOf(previewShort).coerceAtLeast(0)) { i ->
            splitPrefs.edit().putInt("preview_q", qs[i]).apply()
            rebuildPreview()
            chip(L.t("جودة المعاينة: ", "Preview: ") + qs[i] + "p")
        }
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
                        ProjectStore.Imported.Kind.IMAGE -> project.clips.add(insertAt++, Clip(file = m.file, type = ClipType.IMAGE, sourceDurationUs = m.durationUs, srcW = m.w, srcH = m.h))
                        ProjectStore.Imported.Kind.VIDEO -> project.clips.add(insertAt++, Clip(file = m.file, type = ClipType.VIDEO, sourceDurationUs = m.durationUs, srcW = m.w, srcH = m.h))
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
                second = c.copy(fx = c.fx.copy(), id = newId(), sourceDurationUs = c.sourceDurationUs - rel, trimStartUs = 0, trimEndUs = c.sourceDurationUs - rel)
                c.sourceDurationUs = rel; c.trimStartUs = 0; c.trimEndUs = rel
            } else {
                val cut = c.trimStartUs + (rel * c.speed).toLong()
                second = c.copy(fx = c.fx.copy(), id = newId(), trimStartUs = cut)
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

    private fun volumeClip(c: Clip): Unit = audioPanel(
        title = L.t("صوت المقطع", "Clip audio"), fx = c.fx,
        volume = { c.volume }, setVolume = { v -> edit { c.volume = v } },
        speed = null, setSpeed = null, lengthUs = c.timelineUs,
        fadeIn = { c.fx.fadeInUs }, fadeOut = { c.fx.fadeOutUs },
        setFades = { i, o -> edit { c.fx.fadeInUs = i; c.fx.fadeOutUs = o } },
        applyAll = { edit { project.clips.forEach { it.volume = c.volume; it.fx = c.fx.copy() } } },
        reopen = { volumeClip(c) },
    )

    /**
     * Pro audio panel: volume, tempo, pitch (heavier ↔ softer), voice changer, 3-band EQ with presets,
     * noise reduction, voice isolation, leveler, echo, reverb and fades.
     */
    private fun audioPanel(
        title: String,
        fx: com.mawj.editor.model.AudioFx,
        volume: () -> Float, setVolume: (Float) -> Unit,
        speed: (() -> Float)?, setSpeed: ((Float) -> Unit)?,
        lengthUs: Long,
        fadeIn: () -> Long, fadeOut: () -> Long, setFades: (Long, Long) -> Unit,
        applyAll: (() -> Unit)?,
        reopen: () -> Unit,
    ) {
        val sheet = Sheet(this, title, L.t("تحكم كامل بالصوت — يتطبق في المعاينة والتصدير", "Full audio control — applied in preview and export"))
        val pct: (Float) -> String = { "${(it * 100).toInt()}%" }
        val db: (Float) -> String = { (if (it > 0) "+" else "") + String.format(Locale.US, "%.1f dB", it) }

        sheet.slider(L.t("مستوى الصوت", "Volume"), 0f, 2f, volume(), pct, 0.05f) { setVolume(it) }
        if (speed != null && setSpeed != null) {
            sheet.slider(L.t("السرعة (النغمة ثابتة)", "Speed (pitch kept)"), 0.5f, 2f, speed(), { String.format(Locale.US, "%.2fx", it) }, 0.05f) { setSpeed(it) }
        }
        sheet.slider(L.t("طبقة الصوت  (أثقل ← → أنعم)", "Pitch  (deeper ← → higher)"), -12f, 12f, fx.pitch,
            { (if (it > 0) "+" else "") + String.format(Locale.US, "%.1f", it) + L.t(" نغمة", " st") }, 0.5f) { v -> edit { fx.pitch = v } }

        val voices = listOf("" to L.t("بدون", "None"), "deep" to L.t("عميق", "Deep"), "chipmunk" to L.t("سنجاب", "Chipmunk"),
            "robot" to L.t("روبوت", "Robot"), "radio" to L.t("راديو", "Radio"), "phone" to L.t("هاتف", "Phone"),
            "megaphone" to L.t("مكبّر صوت", "Megaphone"), "cave" to L.t("كهف", "Cave"), "echo" to L.t("صدى", "Echo"),
            "underwater" to L.t("تحت الماء", "Underwater"), "monster" to L.t("وحش", "Monster"))
        sheet.chips(L.t("مغيّر الصوت", "Voice changer"), voices.map { it.second }, voices.indexOfFirst { it.first == fx.voice }.coerceAtLeast(0)) { i ->
            val id = voices[i].first
            edit {
                fx.voice = id
                when (id) { "deep" -> fx.pitch = -5f; "chipmunk" -> fx.pitch = 7f; "monster" -> fx.pitch = -9f; "" -> {} else -> {} }
            }
            sheet.dismiss(); reopen()
        }

        val eqs = listOf(
            Triple(L.t("مسطّح", "Flat"), floatArrayOf(0f, 0f, 0f), ""),
            Triple(L.t("تعزيز الصوت البشري", "Voice boost"), floatArrayOf(-3f, 4f, 3f), ""),
            Triple(L.t("تعزيز الجهير", "Bass boost"), floatArrayOf(7f, 0f, -1f), ""),
            Triple(L.t("بودكاست", "Podcast"), floatArrayOf(2f, 3f, -2f), ""),
            Triple(L.t("لامع", "Bright"), floatArrayOf(-1f, 1f, 6f), ""),
            Triple(L.t("دافئ", "Warm"), floatArrayOf(4f, 1f, -4f), ""),
        )
        sheet.chips(L.t("المعادل (EQ)", "Equalizer (EQ)"), eqs.map { it.first }, eqs.indexOfFirst { it.second[0] == fx.eqLow && it.second[1] == fx.eqMid && it.second[2] == fx.eqHigh }) { i ->
            val v = eqs[i].second
            edit { fx.eqLow = v[0]; fx.eqMid = v[1]; fx.eqHigh = v[2] }
            sheet.dismiss(); reopen()
        }
        sheet.slider(L.t("الجهير — الثقل", "Bass — weight"), -12f, 12f, fx.eqLow, db, 0.5f) { v -> edit { fx.eqLow = v } }
        sheet.slider(L.t("الوسط — الوضوح", "Mids — presence"), -12f, 12f, fx.eqMid, db, 0.5f) { v -> edit { fx.eqMid = v } }
        sheet.slider(L.t("الحدّة — النعومة واللمعان", "Treble — softness / brightness"), -12f, 12f, fx.eqHigh, db, 0.5f) { v -> edit { fx.eqHigh = v } }

        sheet.text(L.t("تنظيف الصوت", "Cleanup"))
        sheet.slider(L.t("تقليل التشويش", "Noise reduction"), 0f, 1f, fx.denoise, pct, 0.05f) { v -> edit { fx.denoise = v } }
        sheet.toggle(L.t("عزل الصوت البشري (يخفف الخلفية)", "Voice isolation (reduces background)"), fx.isolate) { v -> edit { fx.isolate = v } }
        sheet.slider(L.t("توحيد المستوى (ضاغط)", "Leveler (compressor)"), 0f, 1f, fx.compress, pct, 0.05f) { v -> edit { fx.compress = v } }

        sheet.text(L.t("المساحة", "Space"))
        sheet.slider(L.t("صدى", "Echo"), 0f, 1f, fx.echo, pct, 0.05f) { v -> edit { fx.echo = v } }
        sheet.slider(L.t("ريفيرب (قاعة)", "Reverb (room)"), 0f, 1f, fx.reverb, pct, 0.05f) { v -> edit { fx.reverb = v } }

        val maxF = (lengthUs / 2e6f).coerceIn(0.2f, 10f)
        sheet.slider(L.t("ظهور تدريجي", "Fade in"), 0f, maxF, (fadeIn() / 1e6f).coerceAtMost(maxF), { secs(it) }, 0f) { v -> setFades((v * 1e6).toLong(), fadeOut()) }
        sheet.slider(L.t("اختفاء تدريجي", "Fade out"), 0f, maxF, (fadeOut() / 1e6f).coerceAtMost(maxF), { secs(it) }, 0f) { v -> setFades(fadeIn(), (v * 1e6).toLong()) }

        if (applyAll != null) sheet.button(L.t("تطبيق على كل المقاطع", "Apply to all clips"), outlined = true) {
            applyAll(); sheet.dismiss(); chip(L.t("تم تطبيق الصوت على الكل", "Audio applied to all clips"))
        }
        sheet.button(L.t("إعادة ضبط الصوت", "Reset audio"), outlined = true) {
            edit {
                fx.pitch = 0f; fx.voice = ""; fx.eqLow = 0f; fx.eqMid = 0f; fx.eqHigh = 0f; fx.denoise = 0f
                fx.isolate = false; fx.compress = 0f; fx.echo = 0f; fx.reverb = 0f
            }
            sheet.dismiss(); reopen()
        }
        sheet.note(L.t("💡 لصوت كلام نظيف: تقليل التشويش 50% + عزل الصوت + توحيد المستوى 40% + «تعزيز الصوت البشري».",
            "💡 For clean speech: Noise reduction 50% + Voice isolation + Leveler 40% + “Voice boost”."))
        sheet.show()
    }

    private fun filters(c: Clip) {
        val sheet = Sheet(this, L.t("فلاتر وتلوين", "Filters & color"), L.t("تلوين سينمائي احترافي — يطبق في المعاينة والتصدير", "Pro color grading — applied in preview and export"))
        val pct: (Float) -> String = { "${(it * 100).toInt()}" }
        val grades = com.mawj.editor.engine.ColorGrade.presets
        val base = thumbs.get(thumbKey(c))
        sheet.thumbs(L.t("الفلتر", "Look"), listOf(L.t("بدون", "None") to gradePreview(base, "")) + grades.map { it.label to gradePreview(base, it.id) },
            grades.indexOfFirst { it.id == c.grade } + 1) { i ->
            edit { c.grade = if (i == 0) "" else grades[i - 1].id }
        }
        sheet.slider(L.t("قوة الفلتر", "Look strength"), 0f, 1f, c.gradeAmount, pct, 0.05f) { v -> edit { c.gradeAmount = v } }
        sheet.slider(L.t("الحرارة", "Temperature"), -1f, 1f, c.temperature, pct, 0.05f) { v -> edit { c.temperature = v } }
        sheet.slider(L.t("الصبغة", "Tint"), -1f, 1f, c.tint, pct, 0.05f) { v -> edit { c.tint = v } }
        sheet.slider(L.t("السطوع", "Brightness"), -0.5f, 0.5f, c.brightness, pct, 0.01f) { v -> edit { c.brightness = v } }
        sheet.slider(L.t("التباين", "Contrast"), -0.5f, 0.6f, c.contrast, pct, 0.01f) { v -> edit { c.contrast = v } }
        sheet.slider(L.t("التشبّع", "Saturation"), -1f, 1f, c.saturation, pct, 0.01f) { v -> edit { c.saturation = v } }
        sheet.slider(L.t("الحدّة والتفاصيل", "Detail / sharpen"), 0f, 1f, c.sharpen, pct, 0.05f) { v -> edit { c.sharpen = v } }
        sheet.button(L.t("✨ تحسين تلقائي بالذكاء", "✨ Smart auto-enhance")) { sheet.dismiss(); autoEnhance(listOf(c)) }
        sheet.button(L.t("تطبيق على كل المقاطع", "Apply to all clips"), outlined = true) {
            edit {
                project.clips.forEach {
                    it.grade = c.grade; it.gradeAmount = c.gradeAmount; it.temperature = c.temperature; it.tint = c.tint
                    it.brightness = c.brightness; it.contrast = c.contrast; it.saturation = c.saturation; it.sharpen = c.sharpen
                }
            }
            sheet.dismiss(); chip(L.t("تم تطبيق التلوين على الكل", "Grade applied to all clips"))
        }
        sheet.button(L.t("إعادة ضبط", "Reset"), outlined = true) {
            edit { c.grade = ""; c.gradeAmount = 1f; c.temperature = 0f; c.tint = 0f; c.brightness = 0f; c.contrast = 0f; c.saturation = 0f; c.sharpen = 0f }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun animations(c: Clip) {
        val sheet = Sheet(this, L.t("حركة المقطع", "Clip animation"), L.t("دخول • خروج • حركة مستمرة", "In • Out • Loop"))
        val anims = com.mawj.editor.engine.ClipAnimations
        val none = L.t("بدون", "None")
        val base = Previews.square(thumbs.get(thumbKey(c)))
        fun tile(kind: String, id: String): () -> View = { AnimTile(this, 1800) { cv, t, sz -> Previews.animation(cv, t, sz, base, kind, id) } }
        sheet.tiles(L.t("دخول", "In"), listOf(none to tile("in", "")) + anims.inAnims.map { it.label to tile("in", it.id) },
            anims.inAnims.indexOfFirst { it.id == c.animIn } + 1) { i -> edit { c.animIn = if (i == 0) "" else anims.inAnims[i - 1].id } }
        sheet.tiles(L.t("خروج", "Out"), listOf(none to tile("out", "")) + anims.outAnims.map { it.label to tile("out", it.id) },
            anims.outAnims.indexOfFirst { it.id == c.animOut } + 1) { i -> edit { c.animOut = if (i == 0) "" else anims.outAnims[i - 1].id } }
        sheet.tiles(L.t("حركة مستمرة", "Loop"), listOf(none to tile("loop", "")) + anims.loopAnims.map { it.label to tile("loop", it.id) },
            anims.loopAnims.indexOfFirst { it.id == c.animLoop } + 1) { i -> edit { c.animLoop = if (i == 0) "" else anims.loopAnims[i - 1].id } }
        sheet.note(L.t("💡 «كين بيرنز» و«تقريب بطيء» تعطي الصور الثابتة حياة، و«نبض» ممتاز مع الأغاني.", "💡 Ken Burns and Slow zoom bring photos to life; Pulse works great with music."))
        sheet.show()
    }

    // ---------- AI tools (on-device) ----------

    private fun aiSheet() {
        val sheet = Sheet(this, L.t("أدوات الذكاء ✨", "AI tools ✨"), L.t("تشتغل على جوالك بدون نت", "Runs on your phone, offline"))
        sheet.option(R.drawable.i_wand, L.t("تحسين تلقائي للألوان", "Auto-enhance colors"), L.t("يحلل الإضاءة والألوان ويضبطها لكل مقطع", "Analyzes light & color and fixes every clip"), false) {
            sheet.dismiss(); autoEnhance(project.clips.toList())
        }
        sheet.option(R.drawable.i_scissors, L.t("حذف السكتات", "Remove silences"), L.t("يقص الفراغات الصامتة تلقائياً (ممتاز لمقاطع الكلام)", "Cuts silent pauses automatically (great for talking videos)"), false) {
            sheet.dismiss(); removeSilences(project.clips.indices.filter { project.clips[it].type == ClipType.VIDEO })
        }
        sheet.option(R.drawable.i_music, L.t("مزامنة على الإيقاع", "Beat sync"), L.t("يكشف إيقاع الأغنية ويقص المقاطع عليه", "Detects the song's beats and cuts clips to them"), false) {
            sheet.dismiss(); beatSync()
        }
        sheet.option(R.drawable.i_fit, L.t("إعادة تأطير ذكي", "Smart reframe"), L.t("يتبع الوجه ويملأ الإطار بأي نسبة (16:9 ← 9:16)", "Follows faces to fill any ratio (16:9 → 9:16)"), false) {
            sheet.dismiss(); smartReframe(project.clips.toList())
        }
        sheet.option(R.drawable.i_sparkles, L.t("تكبير 4K مع تحسين التفاصيل", "4K upscale + detail"), L.t("من التصدير ← مخصص ← 4K + «تحسين التفاصيل»", "Export → Custom → 4K + “Detail enhance”"), false) {
            sheet.dismiss(); showExportWith(false)
        }
        sheet.show()
    }

    private fun clipRange(c: Clip) = c.trimStartUs to c.trimEndUs

    private fun autoEnhance(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val (dialog, _) = busyDialog(L.t("جاري تحليل الألوان…", "Analyzing colors…"))
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                clips.map { c ->
                    val (a, z) = clipRange(c)
                    runCatching { com.mawj.editor.engine.Analysis.autoEnhance(store.mediaFile(project.id, c.file), c.type == ClipType.IMAGE, a, z) }.getOrNull()
                }
            }
            dialog.dismiss()
            edit {
                clips.forEachIndexed { k, c -> results[k]?.let { r -> c.brightness = r[0]; c.contrast = r[1]; c.saturation = r[2] } }
            }
            chip(L.t("تم التحسين التلقائي ✨", "Auto-enhanced ✨"))
        }
    }

    private fun removeSilences(indices: List<Int>) {
        if (indices.isEmpty()) return toast(L.t("ما فيه مقاطع فيديو", "No video clips"))
        val (dialog, _) = busyDialog(L.t("جاري البحث عن السكتات…", "Finding silences…"))
        lifecycleScope.launch {
            val segs = withContext(Dispatchers.IO) {
                indices.associateWith { i ->
                    val c = project.clips[i]
                    runCatching {
                        com.mawj.editor.engine.Analysis.keepSegments(store.mediaFile(project.id, c.file), c.trimStartUs, c.trimEndUs)
                    }.getOrNull()
                }
            }
            dialog.dismiss()
            var removedUs = 0L
            edit {
                for (i in indices.sortedDescending()) {
                    val c = project.clips[i]
                    val keep = segs[i] ?: continue
                    if (keep.isEmpty() || (keep.size == 1 && keep[0].first <= c.trimStartUs + 50_000 && keep[0].last >= c.trimEndUs - 50_000)) continue
                    removedUs += (c.trimEndUs - c.trimStartUs) - keep.sumOf { it.last - it.first }
                    val parts = keep.mapIndexed { k, r ->
                        c.copy(fx = c.fx.copy(), id = newId(), trimStartUs = r.first, trimEndUs = r.last,
                            transition = if (k == keep.lastIndex) c.transition else Transition.NONE)
                    }
                    project.clips.removeAt(i)
                    project.clips.addAll(i, parts)
                }
                selClip = -1
            }
            chip(if (removedUs > 0) L.t("تم حذف ${"%.1f".format(Locale.US, removedUs / 1e6)}ث من السكتات", "Removed ${"%.1f".format(Locale.US, removedUs / 1e6)}s of silence")
                 else L.t("ما لقينا سكتات طويلة", "No long silences found"))
        }
    }

    private fun beatSync() {
        val a = project.audios.getOrNull(selAudio) ?: project.audios.firstOrNull()
            ?: return toast(L.t("أضف أغنية أول من «صوت»", "Add a song first from “Audio”"))
        if (project.clips.isEmpty()) return
        val (dialog, _) = busyDialog(L.t("جاري كشف الإيقاع…", "Detecting beats…"))
        lifecycleScope.launch {
            val beats = withContext(Dispatchers.IO) {
                runCatching { com.mawj.editor.engine.Analysis.beats(store.mediaFile(project.id, a.file), a.trimStartUs, a.trimEndUs) }.getOrDefault(emptyList())
            }
            dialog.dismiss()
            if (beats.size < 2) return@launch toast(L.t("ما قدرنا نكشف إيقاع واضح", "Couldn't find a clear beat"))
            // Beat times on the timeline, keeping cuts at least 0.4 s apart.
            val times = ArrayList<Long>()
            for (bt in beats) {
                val t = a.startAtUs + ((bt - a.trimStartUs) / a.speed).toLong()
                if (t > 300_000 && (times.isEmpty() || t - times.last() >= 400_000)) times += t
            }
            edit {
                a.beats = beats
                var start = 0L
                project.clips.forEachIndexed { i, c ->
                    val cut = times.firstOrNull { it > start + 300_000 } ?: return@forEachIndexed
                    val want = cut - start
                    if (c.type == ClipType.IMAGE) {
                        c.sourceDurationUs = want; c.trimStartUs = 0; c.trimEndUs = want
                    } else {
                        val maxLen = ((c.sourceDurationUs - c.trimStartUs) / c.speed).toLong()
                        val len = minOf(want, maxLen)
                        c.trimEndUs = c.trimStartUs + (len * c.speed).toLong()
                    }
                    start = project.clipStartUs(i) + project.clips[i].timelineUs - project.overlapUs(i)
                }
            }
            chip(L.t("تمت المزامنة على ${times.size} ضربة 🎵", "Synced to ${times.size} beats 🎵"))
        }
    }

    private fun smartReframe(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val (dialog, _) = busyDialog(L.t("جاري تتبع الوجوه…", "Tracking faces…"))
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                clips.map { c ->
                    val f = store.mediaFile(project.id, c.file)
                    val img = c.type == ClipType.IMAGE
                    val size = runCatching { com.mawj.editor.engine.Analysis.size(f, img) }.getOrDefault(0 to 0)
                    val focus = runCatching { com.mawj.editor.engine.Analysis.faceFocus(f, img, c.trimStartUs, c.trimEndUs) }.getOrNull()
                    Triple(size, focus, c)
                }
            }
            dialog.dismiss()
            var faces = 0
            edit {
                found.forEach { (size, focus, c) ->
                    c.srcW = size.first; c.srcH = size.second
                    c.fill = true
                    if (focus != null) { c.focusX = focus.x; c.focusY = focus.y; faces++ } else { c.focusX = 0.5f; c.focusY = 0.5f }
                }
            }
            chip(L.t("تم التأطير الذكي ($faces وجه)", "Smart reframe done ($faces with faces)"))
        }
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
        edit { project.clips.add(selClip + 1, c.copy(fx = c.fx.copy(), id = newId())); selClip += 1 }
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
        val ta = Previews.square(thumbs.get(thumbKey(clip)))
        val tbRaw = project.clips.getOrNull(if (all) 1 else index + 1)?.let { thumbs.get(thumbKey(it)) }
        val tb = Previews.square(tbRaw ?: gradePreview(thumbs.get(thumbKey(clip)), "cool"))
        fun anim(k: Transition): () -> View = { AnimTile(this, 2200) { cv, t, sz -> Previews.transition(cv, t, sz, ta, tb, k) } }
        var markMotion: (Int) -> Unit = {}
        val markOverlap = sheet.tiles(L.t("✦ دمج متداخل احترافي — المقطعين فوق بعض", "✦ Pro overlap — clips blend together"),
            overlapKinds.map { it.label to anim(it) }, overlapKinds.indexOf(kind)) {
            kind = overlapKinds[it]; markMotion(-1)
        }
        markMotion = sheet.tiles(L.t("حركات القطع", "Cut motions"),
            motionKinds.map { it.label to anim(it) }, motionKinds.indexOf(kind)) {
            kind = motionKinds[it]; markOverlap(-1)
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
                b.timeline.setPosition(pos); seekPlayer(pos); updateTime()
            }
        }
        if (!all) sheet.button(L.t("تطبيق على كل المقاطع", "Apply to all clips"), outlined = true) {
            edit { project.clips.dropLast(1).forEach { it.transition = kind; it.transitionUs = durUs } }
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Audio tools ----------

    private fun volumeAudio(a: AudioClip): Unit = audioPanel(
        title = "♪ " + a.label, fx = a.fx,
        volume = { a.volume }, setVolume = { v -> edit { a.volume = v } },
        speed = { a.speed }, setSpeed = { v -> edit { a.speed = v } },
        lengthUs = a.timelineUs,
        fadeIn = { a.fadeInUs }, fadeOut = { a.fadeOutUs },
        setFades = { i, o -> edit { a.fadeInUs = i; a.fadeOutUs = o } },
        applyAll = null,
        reopen = { volumeAudio(a) },
    )

    private fun fadeAudio(a: AudioClip) {
        val sheet = Sheet(this, L.t("تلاشي الصوت", "Audio fades"))
        val max = (a.timelineUs / 2e6f).coerceAtMost(10f).coerceAtLeast(0.2f)
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
        if (rel < 100_000 || a.timelineUs - rel < 100_000) return toast(L.t("حرّك المؤشر لوسط الصوت عشان تقسمه", "Move the playhead inside the audio to split it"))
        edit {
            val cut = a.trimStartUs + (rel * a.speed).toLong()
            project.audios.add(selAudio + 1, a.copy(id = newId(), trimStartUs = cut, startAtUs = a.startAtUs + rel, fadeInUs = 0, fx = a.fx.copy()))
            a.trimEndUs = cut
            a.fadeOutUs = 0
        }
    }

    private fun duplicateAudio(a: AudioClip) {
        edit {
            project.audios += a.copy(id = newId(), startAtUs = a.startAtUs + a.timelineUs, fx = a.fx.copy())
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

    // ---------- Frame & canvas ----------

    private fun frameSheet(c: Clip) {
        val sheet = Sheet(this, L.t("الإطار والخلفية", "Frame & canvas"), L.t("💡 تقدر تكبّر وتحرك المقطع بإصبعين على المعاينة", "💡 Pinch and drag with two fingers on the preview"))
        val pct: (Float) -> String = { "${(it * 100).toInt()}%" }
        sheet.slider(L.t("التكبير", "Zoom"), 0.3f, 3f, c.zoom, pct, 0.01f) { v -> edit { c.zoom = v } }
        sheet.slider(L.t("أفقي", "Horizontal"), -1f, 1f, c.offX, pct, 0.01f) { v -> edit { c.offX = v } }
        sheet.slider(L.t("عمودي", "Vertical"), -1f, 1f, c.offY, pct, 0.01f) { v -> edit { c.offY = v } }
        val bgs = listOf("" to L.t("أسود", "Black"), "blur" to L.t("ضبابي ✨", "Blur ✨"), "#FFFFFF" to L.t("أبيض", "White"),
            "#F26B2A" to L.t("برتقالي", "Orange"), "#1E3A8A" to L.t("أزرق", "Blue"), "#14532D" to L.t("أخضر", "Green"), "#FBCFE8" to L.t("وردي", "Pink"))
        sheet.chips(L.t("خلفية الإطار (للمساحة الفاضية)", "Canvas background (empty area)"), bgs.map { it.second }, bgs.indexOfFirst { it.first == c.canvasBg }.coerceAtLeast(0)) { i ->
            edit { c.canvasBg = bgs[i].first }
        }
        sheet.note(L.t("💡 «ضبابي» يملأ الحواف بنسخة ضبابية من نفس المقطع — ممتاز لمقاطع 16:9 على تيك توك.", "💡 “Blur” fills the bars with a blurred copy of the clip — great for 16:9 videos on TikTok."))
        sheet.button(L.t("تطبيق الخلفية على كل المقاطع", "Apply background to all clips"), outlined = true) {
            edit { project.clips.forEach { it.canvasBg = c.canvasBg } }; sheet.dismiss(); chip(L.t("تم تطبيق الخلفية", "Background applied"))
        }
        sheet.button(L.t("إعادة ضبط الإطار", "Reset frame"), outlined = true) {
            edit { c.zoom = 1f; c.offX = 0f; c.offY = 0f }; sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Freeze frame ----------

    private fun freezeFrame() {
        val pos = b.timeline.positionUs
        val i = project.clipAt(pos)
        val c = project.clips.getOrNull(i)?.takeIf { it.type == ClipType.VIDEO } ?: return
        val rel = (pos - project.clipStartUs(i)).coerceIn(0, c.timelineUs - 1)
        val srcUs = c.trimStartUs + (rel * c.speed).toLong()
        val (dialog, _) = busyDialog(L.t("جاري تجميد الإطار…", "Freezing frame…"))
        lifecycleScope.launch {
            val name = newId() + ".png"
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    val bm = try { r.setDataSource(store.mediaFile(project.id, c.file).absolutePath); r.getFrameAtTime(srcUs, MediaMetadataRetriever.OPTION_CLOSEST) } finally { r.release() }
                    bm!!.let { b2 -> store.mediaFile(project.id, name).outputStream().use { b2.compress(Bitmap.CompressFormat.PNG, 100, it) }; b2.width to b2.height }
                }.getOrNull()
            }
            dialog.dismiss()
            if (ok == null) return@launch toast(L.t("تعذّر أخذ الإطار", "Couldn't grab the frame"))
            edit {
                val img = Clip(file = name, type = ClipType.IMAGE, sourceDurationUs = 2_000_000, srcW = ok.first, srcH = ok.second,
                    rotation = c.rotation, mirror = c.mirror, fill = c.fill, brightness = c.brightness, contrast = c.contrast,
                    saturation = c.saturation, grade = c.grade, gradeAmount = c.gradeAmount, temperature = c.temperature, tint = c.tint,
                    canvasBg = c.canvasBg, zoom = c.zoom, offX = c.offX, offY = c.offY)
                if (rel < 100_000) project.clips.add(i, img)
                else if (c.timelineUs - rel < 100_000) project.clips.add(i + 1, img)
                else {
                    val cut = c.trimStartUs + (rel * c.speed).toLong()
                    val second = c.copy(fx = c.fx.copy(), id = newId(), trimStartUs = cut)
                    c.trimEndUs = cut; c.transition = Transition.NONE
                    project.clips.add(i + 1, img); project.clips.add(i + 2, second)
                }
            }
            chip(L.t("تم تجميد الإطار (2ث)", "Frame frozen (2s)"))
        }
    }

    // ---------- Copy / paste edits ----------

    private var copiedEdits: Clip? = null

    private fun copyEdits(c: Clip) {
        copiedEdits = c.copy(fx = c.fx.copy())
        buildTools()
        chip(L.t("تم نسخ التعديلات", "Edits copied"))
    }

    private fun pasteEdits(targets: List<Clip>) {
        val s = copiedEdits ?: return
        edit {
            targets.forEach { t ->
                t.grade = s.grade; t.gradeAmount = s.gradeAmount; t.temperature = s.temperature; t.tint = s.tint
                t.brightness = s.brightness; t.contrast = s.contrast; t.saturation = s.saturation; t.sharpen = s.sharpen
                t.animIn = s.animIn; t.animOut = s.animOut; t.animLoop = s.animLoop
                t.fill = s.fill; t.canvasBg = s.canvasBg; t.zoom = s.zoom; t.offX = s.offX; t.offY = s.offY
                t.textPos = s.textPos; t.textColor = s.textColor; t.textScale = s.textScale; t.textFont = s.textFont
                if (t.type == ClipType.VIDEO) { t.volume = s.volume; t.fx = s.fx.copy() }
            }
        }
        chip(L.t("تم لصق التعديلات على ${targets.size}", "Edits pasted to ${targets.size}"))
    }

    // ---------- Voiceover ----------

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoiceover() else toast(L.t("نحتاج إذن الميكروفون للتسجيل", "Microphone permission is needed"))
    }

    private fun startVoiceover() {
        if (project.clips.isEmpty()) return toast(L.t("أضف مقطع أول", "Add a clip first"))
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        val startAt = b.timeline.positionUs
        val name = newId() + ".m4a"
        val file = store.mediaFile(project.id, name)
        val rec = (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()).apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(192_000)
            setOutputFile(file.absolutePath)
        }
        val sheet = Sheet(this, L.t("🎙 تسجيل تعليق صوتي", "🎙 Voiceover"), L.t("المقطع يشتغل بدون صوت عشان تتكلم عليه بالتزامن", "The video plays muted so you can narrate in sync"))
        val timer = sheet.text("00:00")
        timer.textSize = 28f
        var recording = false
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (!recording) return
                val s = (System.currentTimeMillis() - started) / 1000
                timer.text = String.format(Locale.US, "🔴 %02d:%02d", s / 60, s % 60)
                handler.postDelayed(this, 250)
            }
        }
        fun stop(save: Boolean) {
            if (!recording) return
            recording = false
            runCatching { rec.stop() }
            rec.release()
            player?.pause(); player?.volume = if (muted) 0f else 1f
            if (!save) { file.delete(); return }
            val dur = runCatching {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(file.absolutePath); (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) * 1000 } finally { r.release() }
            }.getOrDefault(0L)
            if (dur < 300_000) { file.delete(); toast(L.t("التسجيل قصير جداً", "Recording too short")); return }
            edit {
                project.audios += AudioClip(file = name, label = L.t("تعليق صوتي", "Voiceover"), sourceDurationUs = dur, startAtUs = startAt)
                selAudio = project.audios.lastIndex; selClip = -1
            }
            chip(L.t("تمت إضافة التعليق الصوتي", "Voiceover added"))
        }
        sheet.button(L.t("⏹ إيقاف وحفظ", "⏹ Stop & save")) { stop(true); sheet.dismiss() }
        sheet.button(L.t("إلغاء", "Cancel"), outlined = true) { stop(false); sheet.dismiss() }
        sheet.dialog.setOnDismissListener { stop(true) }
        try {
            rec.prepare(); rec.start()
            recording = true
            handler.post(tick)
            player?.let { it.volume = 0f; seekPlayer(startAt); it.play() }
            sheet.show()
        } catch (e: Exception) {
            rec.release(); file.delete()
            toast(L.t("تعذّر بدء التسجيل: ", "Couldn't start recording: ") + e.message)
        }
    }

    // ---------- Save frame ----------

    private fun saveFrame() {
        val sv = b.playerView.videoSurfaceView as? android.view.SurfaceView ?: return toast(L.t("ما فيه إطار", "No frame"))
        if (sv.width <= 0) return
        val bm = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(sv, bm, { result ->
            if (result != android.view.PixelCopy.SUCCESS) { toast(L.t("تعذّر حفظ الإطار", "Couldn't save the frame")); return@request }
            lifecycleScope.launch(Dispatchers.IO) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "MawjFrame_" + System.currentTimeMillis() + ".png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Mawj Edit")
                }
                val ok = runCatching {
                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    contentResolver.openOutputStream(uri)!!.use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }.isSuccess
                withContext(Dispatchers.Main) { chip(if (ok) L.t("تم حفظ الإطار في الاستديو", "Frame saved to gallery") else L.t("تعذّر الحفظ", "Couldn't save")) }
            }
        }, handler)
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
            var fps = 0f
            sheet.chips(L.t("الفريمات", "Frame rate"), listOf(L.t("الأصلي", "Original"), "30", "60"), 0) { fps = listOf(0f, 30f, 60f)[it] }
            sheet.button(L.t("صدّر لتيك توك", "Export for TikTok")) {
                sheet.dismiss()
                export(1080, 0.5, false, true, fps, 0.2f)
            }
            sheet.show()
            return
        }
        val sheet = Sheet(this, L.t("تصدير مخصص", "Custom export"))
        val (w8, h8) = project.aspect.canvas(4320)
        val has8k = com.mawj.editor.engine.DeviceCaps.canEncode(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC, w8, h8)
        val res = listOf(720, 1080, 1440, 2160) + if (has8k) listOf(4320) else emptyList()
        val srcFpsNow = sourceFps()
        val anyHdr = project.clips.any { it.type == ClipType.VIDEO && com.mawj.editor.engine.DeviceCaps.isHdr(store.mediaFile(project.id, it.file)) }
        val hdrOk = anyHdr && com.mawj.editor.engine.DeviceCaps.hdrEncoder
        var r = 1
        var quality = 1
        var hevc = false
        sheet.chips(L.t("الدقة", "Resolution"), listOf("720p", "1080p", "2K", "4K") + if (has8k) listOf("8K") else emptyList(), r) { r = it }
        sheet.chips(L.t("الجودة", "Quality"), listOf(L.t("عالية", "High"), L.t("قصوى", "Max"), L.t("استديو", "Studio")), quality) { quality = it }
        sheet.chips(L.t("الترميز", "Codec"), listOf(L.t("H.264 (متوافق)", "H.264 (compatible)"), L.t("H.265 (أصغر)", "H.265 (smaller)")), 0) { hevc = it == 1 }
        var fps = 0f
        val fpsOpts = listOf(0f, 24f, 25f, 30f, 50f, 60f) + if (srcFpsNow >= 100f) listOf(120f) else emptyList()
        sheet.chips(L.t("الفريمات", "Frame rate") + " (" + L.t("المقطع: ", "source: ") + srcFpsNow.toInt() + ")",
            listOf(L.t("الأصلي", "Original")) + fpsOpts.drop(1).map { it.toInt().toString() }, 0) {
            fps = fpsOpts[it]
        }
        var keepHdr = false
        if (hdrOk) sheet.toggle(L.t("الحفاظ على HDR ‏(10-bit) — لشاشات HDR", "Keep HDR (10-bit) — for HDR screens"), false) { keepHdr = it }
        else if (anyHdr) sheet.note(L.t("ℹ️ مقاطعك HDR لكن ما لقينا مرمّز HDR في الجهاز، بنحولها لـ SDR.", "ℹ️ Your clips are HDR but no HDR encoder was found; exporting SDR."))
        var detail = 0f
        sheet.toggle(L.t("تحسين التفاصيل (حدّة ذكية عند التكبير)", "Detail enhance (smart sharpening for upscales)"), false) { detail = if (it) 0.35f else 0f }
        sheet.note(L.t("💡 الفريمات «الأصلي» يحافظ على فريمات التصوير (مثلاً 60). اختيار رقم أقل يخفّضها. ما نقدر نزيد فوق فريمات التصوير بدون تشويه.",
            "💡 “Original” keeps the camera frame rate (e.g. 60). A lower number reduces it. We can't go above the source rate without artifacts."))
        sheet.button(L.t("تصدير وحفظ", "Export & save")) {
            sheet.dismiss()
            if (res[r] == 4320) hevc = true
            export(res[r], listOf(0.25, 0.4, 0.6)[quality], hevc || keepHdr, false, fps, detail, keepHdr)
        }
        sheet.show()
    }

    private fun export(shortSide: Int, bpp: Double, hevc: Boolean, tiktok: Boolean, fps: Float = 0f, detail: Float = 0f, keepHdr: Boolean = false) {
        player?.pause()
        player?.release(); player = null
        b.playerView.player = null
        bridgeJob?.cancel()
        proxyJob?.cancel()
        b.bridgeStatus.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bar = LinearProgressIndicator(this).apply { max = 100; trackCornerRadius = dp(4); trackThickness = dp(8) }
        val label = TextView(this).apply { text = L.t("تجهيز…", "Preparing…"); setPadding(0, dp(10), 0, 0); setTextColor(col(R.color.text2)) }
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
        val srcFps = sourceFps()
        val outFps = if (fps > 0f) minOf(fps, srcFps) else srcFps
        val bitrate = (w.toDouble() * h * outFps.coerceIn(24f, 120f) * bpp * (if (hevc) 0.7 else 1.0)).toLong().coerceIn(8_000_000, 200_000_000).toInt()
        val out = File(cacheDir, "export.mp4").apply { delete() }
        exportJob = lifecycleScope.launch {
            try {
                // 1) Render overlapping transitions at full quality.
                // Overlap renders are 8-bit; in HDR mode boundaries use the in-timeline fallback instead.
                val needed = if (keepHdr) emptyList() else Bridges.needed(project)
                val bridges = HashMap<Int, File>()
                needed.forEachIndexed { n, i ->
                    label.text = L.t("تجهيز الدمج ${n + 1}/${needed.size}…", "Preparing transition ${n + 1}/${needed.size}…")
                    bar.setProgressCompat(n * 100 / needed.size.coerceAtLeast(1), true)
                    Bridges.render(this@EditorActivity, store, project, i, w, h, true, bitrate)?.let { bridges[i] = it }
                }
                if (bridges.size < needed.size) toast(L.t("بعض الانتقالات ما تجهزت: ", "Some transitions failed: ") + Bridges.lastError)
                // 2) Export the timeline.
                val comp = CompositionFactory.build(this@EditorActivity, store, project,
                    BuildOptions(w, h, true, bridges = bridges, maxFps = if (fps > 0f && fps < srcFps - 0.5f) fps else 0f, sharpen = detail, keepHdr = keepHdr))
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
                    .setMessage(errText(e) + L.t("\n\nجرّب دقة أقل (1080p).", "\n\nTry a lower resolution (1080p)."))
                    .setPositiveButton(L.t("حسناً", "OK"), null).show()
                Glass.style(d)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                rebuildPreview()
            }
        }
    }

    /** Highest frame rate among the project's video clips (30 if unknown). */
    private fun sourceFps(): Float {
        var best = 0f
        for (c in project.clips) {
            if (c.type != ClipType.VIDEO) continue
            val ex = android.media.MediaExtractor()
            runCatching {
                ex.setDataSource(store.mediaFile(project.id, c.file).absolutePath)
                for (t in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(t)
                    if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true && f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                        best = maxOf(best, f.getNumber(android.media.MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f)
                    }
                }
            }
            ex.release()
        }
        return if (best > 1f) best else 30f
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

    // ---------- Preview tiles for sheets ----------

    private fun squareOf(bm: Bitmap?, size: Int = 128): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        if (bm == null) { cv.drawColor(col(R.color.surface2)); return out }
        val side = minOf(bm.width, bm.height)
        val src = android.graphics.Rect((bm.width - side) / 2, (bm.height - side) / 2, (bm.width + side) / 2, (bm.height + side) / 2)
        cv.drawBitmap(bm, src, android.graphics.Rect(0, 0, size, size), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Thumbnail with a color grade applied (same math as the video effect). */
    private fun gradePreview(base: Bitmap?, id: String): Bitmap {
        val sq = squareOf(base)
        if (id.isEmpty()) return sq
        val m = com.mawj.editor.engine.ColorGrade.matrix(id, 1f, 0f, 0f)
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            m[0], m[4], m[8], 0f, m[12] * 255f,
            m[1], m[5], m[9], 0f, m[13] * 255f,
            m[2], m[6], m[10], 0f, m[14] * 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val out = Bitmap.createBitmap(sq.width, sq.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(sq, 0f, 0f, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) })
        return out
    }

    /** Mid-point frame of a transition between two thumbnails. */
    private fun transitionPreview(a: Bitmap?, bb: Bitmap?, kind: Transition): Bitmap {
        val s = 128f
        val fa = squareOf(a)
        val fb = squareOf(bb ?: a?.let { gradePreview(it, "cool") })
        val out = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(fa, 0f, 0f, p)
        when (kind) {
            Transition.NONE -> {}
            Transition.WIPE_LEFT -> { cv.save(); cv.clipRect(s / 2, 0f, s, s); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.WIPE_RIGHT -> { cv.save(); cv.clipRect(0f, 0f, s / 2, s); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.WIPE_UP -> { cv.save(); cv.clipRect(0f, s / 2, s, s); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.WIPE_DOWN -> { cv.save(); cv.clipRect(0f, 0f, s, s / 2); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.IRIS -> { cv.save(); cv.clipPath(android.graphics.Path().apply { addCircle(s / 2, s / 2, s * 0.32f, android.graphics.Path.Direction.CW) }); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.CLOCK -> { cv.save(); cv.clipPath(android.graphics.Path().apply { moveTo(s / 2, s / 2); arcTo(-s, -s, 2 * s, 2 * s, -90f, 200f, false); close() }); cv.drawBitmap(fb, 0f, 0f, p); cv.restore() }
            Transition.SLIDE_OVER_LEFT, Transition.SLIDE_LEFT, Transition.SLIDE_RIGHT, Transition.WHIP_PAN -> cv.drawBitmap(fb, s * 0.45f, 0f, p)
            Transition.SLIDE_OVER_UP, Transition.SLIDE_UP, Transition.SLIDE_DOWN -> cv.drawBitmap(fb, 0f, s * 0.45f, p)
            Transition.DIP_WHITE, Transition.FLASH -> cv.drawColor(0xB3FFFFFF.toInt())
            Transition.FADE -> cv.drawColor(0xB3000000.toInt())
            Transition.ZOOM_IN, Transition.ZOOM_BLUR, Transition.DISSOLVE_ZOOM -> {
                cv.save(); cv.scale(1.35f, 1.35f, s / 2, s / 2); p.alpha = 140; cv.drawBitmap(fb, 0f, 0f, p); p.alpha = 255; cv.restore()
            }
            Transition.ZOOM_OUT, Transition.SPIN -> {
                cv.drawColor(0xFF111111.toInt()); cv.save()
                if (kind == Transition.SPIN) cv.rotate(35f, s / 2, s / 2)
                cv.scale(0.7f, 0.7f, s / 2, s / 2); cv.drawBitmap(fa, 0f, 0f, p); cv.restore()
            }
            else -> { p.alpha = 128; cv.drawBitmap(fb, 0f, 0f, p); p.alpha = 255 }
        }
        return out
    }

    // ---------- Thumbnails ----------

    private fun thumbKey(c: Clip) = c.file + "@" + (c.trimStartUs / 100_000)

    private fun thumbFor(key: String): Bitmap? {
        thumbs.get(key)?.let { return it }
        if (!thumbLoading.add(key)) return null
        val file = key.substringBefore("@")
        val atUs = key.substringAfter("@").toLong() * 100_000
        lifecycleScope.launch {
            val bmp = withContext(thumbDispatcher) {
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
