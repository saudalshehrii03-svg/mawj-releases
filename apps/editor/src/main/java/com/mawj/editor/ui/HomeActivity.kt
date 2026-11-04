package com.mawj.editor.ui

import com.mawj.editor.L

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mawj.editor.MawjApp
import com.mawj.editor.R
import com.mawj.editor.databinding.ActivityHomeBinding
import com.mawj.editor.databinding.ItemProjectBinding
import com.mawj.editor.model.ClipType
import com.mawj.editor.model.Project
import com.mawj.editor.sync.DriveSync
import com.mawj.editor.sync.GoogleAuth
import com.mawj.editor.sync.SyncPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private val store by lazy { (application as MawjApp).store }
    private val adapter = ProjectAdapter()
    private val thumbs = LruCache<String, Bitmap>(40)
    private var syncSheet: Sheet? = null
    private var syncStatus: TextView? = null

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            r.accessToken?.let { onSignedIn(it) }
        } catch (e: Exception) {
            showError(GoogleAuth.friendlyError(e))
        }
    }

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { Fonts.import(this@HomeActivity, uri) }
            if (f == null) { Toast.makeText(this@HomeActivity, L.t("الملف مو خط صالح (استخدم TTF أو OTF)", "Not a valid font file (use TTF or OTF)"), Toast.LENGTH_LONG).show(); return@launch }
            Fonts.setAppFont(this@HomeActivity, f.file)
            Toast.makeText(this@HomeActivity, L.t("تم تطبيق خط «${f.name}» ✅", "Font “${f.name}” applied ✅"), Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.list.adapter = adapter
        b.brand.text = L.t("موج+", "MAWJ+")
        b.settingsPill.text = L.t("الإعدادات", "Settings")
        b.settingsPill.setOnClickListener { showSettings() }
        b.newButton.setOnClickListener { newProject() }
        b.newTitle.text = L.t("مشروع جديد", "NEW PROJECT")
        b.newSubtitle.text = L.t("مقاطع • صوت • دمج • تصدير HD", "Clips • audio • transitions • HD export")
        b.newBadge.text = L.t("تيك توك HD", "TikTok HD")
        b.accountLine.setOnClickListener { showAccount() }
        b.empty.text = L.t("ما عندك مشاريع للحين\nاضغط «مشروع جديد» وابدأ", "No projects yet\nTap “New project” to start")

        tile(R.drawable.i_film, L.t("مونتاج", "Video editor")) { newProject() }
        tile(R.drawable.i_send, L.t("تيك توك HD", "TikTok HD")) { newProject() }
        tile(R.drawable.i_text, L.t("الخطوط", "Fonts")) { showFonts() }
        tile(R.drawable.i_more, L.t("المزيد", "More")) { showSettings() }

        nav(R.drawable.i_layers, true) {}
        nav(R.drawable.i_film) { newProject() }
        nav(R.drawable.i_folder) { showAccount() }
        nav(R.drawable.i_user) { showSettings() }
        Fonts.apply(b.root)
    }

    private fun tile(icon: Int, label: String, onClick: () -> Unit) {
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        col.addView(android.widget.ImageView(this).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.tile)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener { onClick() }
        }, android.widget.LinearLayout.LayoutParams(-1, dp(68)))
        col.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(0x99FFFFFF.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 1
        })
        b.tiles.addView(col, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        })
    }

    private fun nav(icon: Int, active: Boolean = false, onClick: () -> Unit) {
        b.bottomNav.addView(android.widget.ImageView(this).apply {
            setImageResource(icon)
            alpha = if (active) 1f else 0.45f
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = getDrawable(R.drawable.btn_flat)
            setOnClickListener { onClick() }
        }, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
    }

    // ---------- Settings (language, font, account) ----------

    private fun showSettings() {
        val sheet = Sheet(this, L.t("الإعدادات", "Settings"))
        sheet.chips(L.t("لغة التطبيق", "App language"), listOf("العربية", "English"), if (L.en) 1 else 0) { i ->
            val en = i == 1
            if (en != L.en) { sheet.dismiss(); L.set(this, en) }
        }
        sheet.option(R.drawable.i_text, L.t("خط التطبيق", "App font"), Fonts.list(this).firstOrNull { it.file == Fonts.appFontFile(this) }?.name ?: L.t("الخط الافتراضي", "Default font"), false) {
            sheet.dismiss(); showFonts()
        }
        sheet.option(R.drawable.ic_cloud, L.t("حساب جوجل", "Google account"), SyncPrefs.email(this) ?: L.t("غير مسجّل", "Not signed in"), false) {
            sheet.dismiss(); showAccount()
        }
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        sheet.note(L.t("موج مونتاج • الإصدار $ver", "Mawj Edit • version $ver"))
        sheet.show()
    }

    override fun onResume() {
        super.onResume()
        reload()
        updateAccountLine()
    }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { store.list() }
            adapter.items = items
            adapter.notifyDataSetChanged()
            b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.sectionTitle.text = L.t("المشاريع", "PROJECTS")
        }
    }

    private fun updateAccountLine() {
        val email = SyncPrefs.email(this)
        b.accountLine.text = if (email != null) "☁️ " + email.substringBefore('@') else L.t("☁️ نسخ احتياطي", "☁️ Backup")
    }

    private fun newProject() {
        val n = store.list().size + 1
        val p = store.create(L.t("مشروع $n", "Project $n"))
        startActivity(Intent(this, EditorActivity::class.java)
            .putExtra(EditorActivity.EXTRA_ID, p.id)
            .putExtra(EditorActivity.EXTRA_PICK, true))
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, p.id))
    }

    private fun menu(anchor: View, p: Project) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, L.t("إعادة تسمية", "Rename"))
            menu.add(0, 2, 1, L.t("تكرار", "Duplicate"))
            menu.add(0, 3, 2, L.t("حذف", "Delete"))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(p)
                    2 -> lifecycleScope.launch { withContext(Dispatchers.IO) { store.duplicate(p.id) }; reload() }
                    3 -> MaterialAlertDialogBuilder(this@HomeActivity)
                        .setTitle(L.t("حذف «${p.name}»؟", "Delete “${p.name}”?"))
                        .setMessage(L.t("بينحذف المشروع من الجهاز. النسخة الاحتياطية في جوجل (إن وجدت) تبقى.", "The project will be removed from this device. Your Google backup (if any) stays."))
                        .setNegativeButton(L.t("إلغاء", "Cancel"), null)
                        .setPositiveButton(L.t("حذف", "Delete")) { _, _ -> store.delete(p.id); reload() }
                        .show().also { Glass.style(it) }
                }
                true
            }
        }.show()
    }

    private fun rename(p: Project) {
        val sheet = Sheet(this, L.t("إعادة تسمية", "Rename"))
        val input = sheet.input(L.t("اسم المشروع", "Project name"), p.name)
        sheet.button(L.t("حفظ", "Save")) {
            val n = input.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty()) { store.rename(p.id, n); reload() }
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Fonts ----------

    private fun showFonts() {
        val sheet = Sheet(this, L.t("خط التطبيق", "App font"), L.t("ارفع أي خط (TTF / OTF) من جوالك ويتطبّق على كل التطبيق", "Upload any font (TTF / OTF) and it applies to the whole app"))
        val current = Fonts.appFontFile(this)
        sheet.option(R.drawable.i_text, L.t("الخط الافتراضي", "Default font"), L.t("خط النظام", "System font"), current == null) {
            Fonts.setAppFont(this, null); sheet.dismiss(); recreate()
        }
        Fonts.list(this).forEach { f ->
            sheet.option(R.drawable.i_text, f.name, L.t("اضغط للتطبيق • اضغط مطولاً للحذف", "Tap to apply • long-press to delete"), current == f.file,
                Fonts.load(java.io.File(Fonts.dir(this), f.file))) {
                Fonts.setAppFont(this, f.file); sheet.dismiss(); recreate()
            }.setOnLongClickListener {
                Fonts.delete(this, f.file); sheet.dismiss(); recreate(); true
            }
        }
        sheet.button(L.t("＋ رفع خط من جوالي", "＋ Upload a font")) { sheet.dismiss(); pickFont.launch(Fonts.PICK_TYPES) }
        sheet.note(L.t("💡 الخطوط اللي ترفعها تقدر تستخدمها كمان في نصوص المقاطع داخل المشاريع (أداة «نص»).", "💡 Uploaded fonts can also be used for on-video text (the “Text” tool)."))
        sheet.show()
    }

    // ---------- Google account ----------

    private fun showAccount() {
        val email = SyncPrefs.email(this)
        val sheet = Sheet(this, L.t("حساب جوجل", "Google account"), L.t("نسخ احتياطي لمشاريعك على Google Drive", "Back up your projects to Google Drive"))
        syncSheet = sheet
        if (email == null) {
            sheet.note(L.t("☁️ سجّل دخولك بحساب جوجل عشان تنحفظ مشاريعك (المقاطع + التعديلات) في مساحة خاصة بالتطبيق داخل Google Drive.\n\n📲 إذا غيرت جوالك: نزّل التطبيق وسجّل دخول وترجع لك مشاريعك.", "☁️ Sign in with Google to keep your projects (clips + edits) in a private app folder in Google Drive.\n\n📲 New phone? Install the app, sign in, and your projects come back."))
            sheet.button(L.t("تسجيل الدخول بحساب جوجل", "Sign in with Google")) { signIn() }
        } else {
            sheet.note(L.t("✅ مسجّل باسم: $email", "✅ Signed in as: $email"))
            val last = SyncPrefs.lastSync(this)
            sheet.text(if (last > 0) L.t("آخر نسخ: ", "Last backup: ") + DateUtils.getRelativeTimeSpanString(last) else L.t("ما صار نسخ احتياطي للحين", "No backup yet"))
            sheet.toggle(L.t("نسخ احتياطي تلقائي عند الخروج من المشروع", "Auto backup when leaving a project"), SyncPrefs.auto(this)) { SyncPrefs.setAuto(this, it) }
            sheet.button(L.t("نسخ احتياطي الآن", "Back up now")) { runSync(backup = true) }
            sheet.button(L.t("استعادة المشاريع من جوجل", "Restore projects from Google"), outlined = true) { runSync(backup = false) }
            sheet.button(L.t("تسجيل الخروج", "Sign out"), outlined = true) {
                SyncPrefs.setEmail(this, null)
                updateAccountLine()
                sheet.dismiss()
            }
        }
        syncStatus = sheet.text("")
        sheet.show()
    }

    private fun signIn() {
        lifecycleScope.launch {
            try {
                val r = GoogleAuth.authorize(this@HomeActivity)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else r.accessToken?.let { onSignedIn(it) }
            } catch (e: Exception) {
                showError(GoogleAuth.friendlyError(e))
            }
        }
    }

    private fun onSignedIn(token: String) {
        syncStatus?.text = L.t("جاري تسجيل الدخول…", "Signing in…")
        lifecycleScope.launch {
            try {
                val email = withContext(Dispatchers.IO) { DriveSync(store).email(token) } ?: L.t("حساب جوجل", "Google account")
                SyncPrefs.setEmail(this@HomeActivity, email)
                updateAccountLine()
                syncStatus?.text = L.t("تم تسجيل الدخول ✅ جاري البحث عن مشاريعك…", "Signed in ✅ Looking for your projects…")
                runSync(backup = false, tokenOverride = token)
            } catch (e: Exception) {
                showError(GoogleAuth.friendlyError(e))
            }
        }
    }

    private fun runSync(backup: Boolean, tokenOverride: String? = null) {
        lifecycleScope.launch {
            try {
                val token = tokenOverride ?: GoogleAuth.silentToken(this@HomeActivity)
                if (token == null) { signIn(); return@launch }
                val drive = DriveSync(store)
                withContext(Dispatchers.IO) {
                    val report: (String) -> Unit = { msg -> runOnUiThread { syncStatus?.text = msg } }
                    if (backup) drive.backup(token, null, report) else drive.restore(token, report)
                }
                SyncPrefs.setLastSync(this@HomeActivity, System.currentTimeMillis())
                reload()
            } catch (e: Exception) {
                syncStatus?.text = L.t("تعذّر: ", "Failed: ") + GoogleAuth.friendlyError(e)
            }
        }
    }

    private fun showError(msg: String) {
        syncStatus?.text = msg
        Toast.makeText(this, msg.lineSequence().first(), Toast.LENGTH_LONG).show()
    }

    // ---------- List ----------

    private fun loadThumb(p: Project, into: android.widget.ImageView) {
        val c = p.clips.firstOrNull() ?: run { into.setImageDrawable(null); return }
        val key = p.id + c.file + c.trimStartUs
        into.tag = key
        thumbs.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = store.mediaFile(p.id, c.file)
                    if (c.type == ClipType.IMAGE) {
                        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
                    } else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(f.absolutePath)
                            r.getScaledFrameAtTime(c.trimStartUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
                        } finally { r.release() }
                    }
                }.getOrNull()
            }
            if (bmp != null) {
                thumbs.put(key, bmp)
                if (into.tag == key) into.setImageBitmap(bmp)
            }
        }
    }

    inner class ProjectAdapter : RecyclerView.Adapter<ProjectAdapter.VH>() {
        var items: List<Project> = emptyList()

        inner class VH(val v: ItemProjectBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = items[position]
            h.v.name.text = p.name
            val sec = p.durationUs / 1_000_000
            h.v.info.text = "${p.aspect.label} • ${p.clips.size} " + L.t("مقطع", "clips")
            val size = runCatching { store.mediaDir(p.id).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
            val date = java.text.SimpleDateFormat("d MMM yy", if (L.en) Locale.ENGLISH else Locale("ar")).format(java.util.Date(p.updatedAt))
            h.v.duration.text = String.format(Locale.US, "%s     %02d:%02d     %dMB", date, sec / 60, sec % 60, size / 1_000_000)
            loadThumb(p, h.v.thumb)
            h.v.card.setOnClickListener { open(p) }
            h.v.card.setOnLongClickListener { menu(it, p); true }
            h.v.more.setOnClickListener { menu(it, p) }
            Fonts.apply(h.v.root)
        }
    }
}
