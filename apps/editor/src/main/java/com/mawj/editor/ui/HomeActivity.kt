package com.mawj.editor.ui

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
import androidx.recyclerview.widget.GridLayoutManager
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
            if (f == null) { Toast.makeText(this@HomeActivity, "الملف مو خط صالح (استخدم TTF أو OTF)", Toast.LENGTH_LONG).show(); return@launch }
            Fonts.setAppFont(this@HomeActivity, f.file)
            Toast.makeText(this@HomeActivity, "تم تطبيق خط «${f.name}» ✅", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = GridLayoutManager(this, 2)
        b.list.adapter = adapter
        b.newButton.setOnClickListener { newProject() }
        b.accountButton.setOnClickListener { showAccount() }
        b.fontButton.setOnClickListener { showFonts() }
        Fonts.apply(b.root)
        Glass.gradientText(b.brand)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        b.greeting.text = if (hour in 4..11) "صباح الإبداع ☀️" else "مساء الإبداع ✨"
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
            b.sectionTitle.text = if (items.isEmpty()) "مشاريعك" else "مشاريعك (${items.size})"
        }
    }

    private fun updateAccountLine() {
        val email = SyncPrefs.email(this)
        b.accountLine.text = if (email != null) "☁️  $email" else "📱  مشاريعك محفوظة في الجهاز"
    }

    private fun newProject() {
        val n = store.list().size + 1
        val p = store.create("مشروع $n")
        startActivity(Intent(this, EditorActivity::class.java)
            .putExtra(EditorActivity.EXTRA_ID, p.id)
            .putExtra(EditorActivity.EXTRA_PICK, true))
    }

    private fun open(p: Project) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, p.id))
    }

    private fun menu(anchor: View, p: Project) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "إعادة تسمية")
            menu.add(0, 2, 1, "تكرار")
            menu.add(0, 3, 2, "حذف")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(p)
                    2 -> lifecycleScope.launch { withContext(Dispatchers.IO) { store.duplicate(p.id) }; reload() }
                    3 -> MaterialAlertDialogBuilder(this@HomeActivity)
                        .setTitle("حذف «${p.name}»؟")
                        .setMessage("بينحذف المشروع من الجهاز. النسخة الاحتياطية في جوجل (إن وجدت) تبقى.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("حذف") { _, _ -> store.delete(p.id); reload() }
                        .show().also { Glass.style(it) }
                }
                true
            }
        }.show()
    }

    private fun rename(p: Project) {
        val sheet = Sheet(this, "إعادة تسمية")
        val input = sheet.input("اسم المشروع", p.name)
        sheet.button("حفظ") {
            val n = input.text?.toString()?.trim().orEmpty()
            if (n.isNotEmpty()) { store.rename(p.id, n); reload() }
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---------- Fonts ----------

    private fun showFonts() {
        val sheet = Sheet(this, "خط التطبيق", "ارفع أي خط (TTF / OTF) من جوالك ويتطبّق على كل التطبيق")
        val current = Fonts.appFontFile(this)
        sheet.option(R.drawable.i_text, "الخط الافتراضي", "خط النظام", current == null) {
            Fonts.setAppFont(this, null); sheet.dismiss(); recreate()
        }
        Fonts.list(this).forEach { f ->
            sheet.option(R.drawable.i_text, f.name, "اضغط للتطبيق • اضغط مطولاً للحذف", current == f.file,
                Fonts.load(java.io.File(Fonts.dir(this), f.file))) {
                Fonts.setAppFont(this, f.file); sheet.dismiss(); recreate()
            }.setOnLongClickListener {
                Fonts.delete(this, f.file); sheet.dismiss(); recreate(); true
            }
        }
        sheet.button("＋ رفع خط من جوالي") { sheet.dismiss(); pickFont.launch(Fonts.PICK_TYPES) }
        sheet.note("💡 الخطوط اللي ترفعها تقدر تستخدمها كمان في نصوص المقاطع داخل المشاريع (أداة «نص»).")
        sheet.show()
    }

    // ---------- Google account ----------

    private fun showAccount() {
        val email = SyncPrefs.email(this)
        val sheet = Sheet(this, "حساب جوجل", "نسخ احتياطي لمشاريعك على Google Drive")
        syncSheet = sheet
        if (email == null) {
            sheet.note("☁️ سجّل دخولك بحساب جوجل عشان تنحفظ مشاريعك (المقاطع + التعديلات) في مساحة خاصة بالتطبيق داخل Google Drive.\n\n📲 إذا غيرت جوالك: نزّل التطبيق وسجّل دخول وترجع لك مشاريعك.")
            sheet.button("تسجيل الدخول بحساب جوجل") { signIn() }
        } else {
            sheet.note("✅ مسجّل باسم: $email")
            val last = SyncPrefs.lastSync(this)
            sheet.text(if (last > 0) "آخر نسخ: " + DateUtils.getRelativeTimeSpanString(last) else "ما صار نسخ احتياطي للحين")
            sheet.toggle("نسخ احتياطي تلقائي عند الخروج من المشروع", SyncPrefs.auto(this)) { SyncPrefs.setAuto(this, it) }
            sheet.button("نسخ احتياطي الآن") { runSync(backup = true) }
            sheet.button("استعادة المشاريع من جوجل", outlined = true) { runSync(backup = false) }
            sheet.button("تسجيل الخروج", outlined = true) {
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
        syncStatus?.text = "جاري تسجيل الدخول…"
        lifecycleScope.launch {
            try {
                val email = withContext(Dispatchers.IO) { DriveSync(store).email(token) } ?: "حساب جوجل"
                SyncPrefs.setEmail(this@HomeActivity, email)
                updateAccountLine()
                syncStatus?.text = "تم تسجيل الدخول ✅ جاري البحث عن مشاريعك…"
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
                syncStatus?.text = "تعذّر: " + GoogleAuth.friendlyError(e)
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
            val s = p.durationUs / 1_000_000
            h.v.duration.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            h.v.info.text = "${p.aspect.label} • ${p.clips.size} مقطع • " + DateUtils.getRelativeTimeSpanString(p.updatedAt)
            loadThumb(p, h.v.thumb)
            h.v.card.setOnClickListener { open(p) }
            h.v.card.setOnLongClickListener { menu(it, p); true }
        }
    }
}
