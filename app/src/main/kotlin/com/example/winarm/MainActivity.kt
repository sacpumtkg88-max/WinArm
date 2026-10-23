package com.example.winarm

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var runner: Runner
    private var vms = mutableListOf<Vm>()
    private var backAction: (() -> Unit)? = null
    private var refresh: () -> Unit = {}
    private var tab = 0
    private var running: Vm? = null
    private var onRun = false
    private var vnc: VncView? = null
    private var topBar: View? = null
    private var keyBar: View? = null
    private var fs = false
    private val prefs by lazy { getSharedPreferences("winarm", MODE_PRIVATE) }

    // ตัวแปรของหน้าสร้างเครื่อง
    private var wIso: String? = null
    private var wName = "Windows 11 ARM64"
    private var wCpu = 4; private var wRam = 6; private var wDisk = 128; private var wNet = false
    private var wStep = 0
    private var nameEt: EditText? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C.bgTop, C.bgBottom)))
        window.statusBarColor = C.bgTop
        window.navigationBarColor = C.nav
        root = FrameLayout(this)
        setContentView(root)
        runner = Runner(this)
        vms = Store.load(this)
        if (prefs.getBoolean("setup_complete", false)) showMain() else showAutoSetup()
    }

    private fun go(v: View, back: (() -> Unit)? = null) {
        root.removeAllViews(); root.addView(v); backAction = back
    }

    @Deprecated("simple")
    override fun onBackPressed() { val b = backAction; if (b != null) b() else super.onBackPressed() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun sz(l: Long) = if (l >= (1L shl 30)) "%.1f GB".format(l / 1073741824.0) else "${l shr 20} MB"
    private val uefiUrl = "https://retrage.github.io/edk2-nightly/bin/RELEASEAARCH64_QEMU_EFI.fd"
    private fun uefiFile() = File(getExternalFilesDir(null), "edk2-aarch64-code.fd")
    private fun uefiReady() = uefiUri() != null || uefiFile().exists()

    private val virtioUrl = "https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/stable-virtio/virtio-win.iso"
    private fun virtioFile() = File(getExternalFilesDir(null), "virtio-win.iso")
    @Volatile private var dlCancel = false

    /** ดาวน์โหลดไฟล์ลงโฟลเดอร์แอป มีแถบความคืบหน้าและปุ่มยกเลิก */
    private fun downloadTo(url: String, dest: File, title: String, minBytes: Long, headers: Map<String, String> = emptyMap(), then: () -> Unit) {
        dlCancel = false
        val dlg = AlertDialog.Builder(this).setTitle(title).setMessage("กำลังเชื่อมต่อ...").setCancelable(false)
            .setNegativeButton("ยกเลิก") { _, _ -> dlCancel = true }.create()
        dlg.show()
        Thread {
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16); var n = 0; var done = 0L; var last = 0L
                        while (i.read(buf).also { n = it } > 0) {
                            if (dlCancel) throw IOException("ยกเลิกแล้ว")
                            o.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now; val d = done
                                runOnUiThread {
                                    dlg.setMessage(if (total > 0) "${d shr 20} / ${total shr 20} MB (${d * 100 / total}%)" else "${d shr 20} MB")
                                }
                            }
                        }
                    }
                }
                if (tmp.length() < minBytes) throw IOException("ไฟล์เล็กผิดปกติ (${tmp.length()} ไบต์)")
                dest.delete(); tmp.renameTo(dest)
                runOnUiThread { dlg.dismiss(); then() }
            } catch (e: Exception) {
                tmp.delete()
                runOnUiThread {
                    dlg.dismiss()
                    if (!dlCancel) AlertDialog.Builder(this).setTitle("ดาวน์โหลดไม่สำเร็จ")
                        .setMessage("${e.message}\n\nตรวจอินเทอร์เน็ตหรือลิงก์ แล้วลองใหม่")
                        .setPositiveButton("ตกลง", null).show()
                }
            }
        }.start()
    }

    private fun downloadUefi(then: () -> Unit) = downloadTo(uefiUrl, uefiFile(), "กำลังดาวน์โหลด UEFI", 1_000_000L, then = then)

    private fun uefiUri(): Uri? = prefs.getString("uefi", null)?.let { Uri.parse(it) }

    // ---------- Auto Setup ครั้งแรก: ดาวน์โหลดเครื่องมือที่ไม่ใช่ Windows ----------
    private fun qemuBundled(): Boolean =
        File(applicationInfo.nativeLibraryDir, "libqemu.so").exists()

    private fun showAutoSetup() {
        val p = vbox(20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        p.add(Logo(this), w = dp(76), h = dp(76), t = 24, b = 8)
        p.add(tv("เตรียม WinArm อัตโนมัติ", 24f, bold = true).apply { gravity = Gravity.CENTER }, b = 8)
        p.add(tv("ครั้งแรกจะดาวน์โหลดเครื่องมือที่จำเป็น ยกเว้น Windows ISO", 14f, C.sub).apply {
            gravity = Gravity.CENTER
        }, b = 18)
        fun status(title: String, detail: String, ready: Boolean) {
            val c = card(14)
            c.addView(tv((if (ready) "✓  " else "↓  ") + title, 15f, if (ready) C.green else C.text, true))
            c.addView(tv(detail, 12f, C.sub))
            p.add(c, b = 8)
        }
        val haveUefi = uefiFile().exists() && uefiFile().length() > 1_000_000
        val haveVirtio = virtioFile().exists() && virtioFile().length() > 10_000_000
        status("UEFI AArch64", if (haveUefi) "มีไฟล์เฟิร์มแวร์แล้ว" else "จะดาวน์โหลดอัตโนมัติ", haveUefi)
        status("ไดรเวอร์ VirtIO", if (haveVirtio) "มีไฟล์ไดรเวอร์แล้ว" else "ISO ขนาดใหญ่ อาจใช้เวลาสักครู่", haveVirtio)
        status("QEMU ARM64", if (qemuBundled()) "พบไบนารี QEMU ในแอป" else "ยังไม่พบ libqemu.so ที่คอมไพล์สำหรับ Android ARM64", qemuBundled())
        p.add(tv("แนะนำให้ใช้ Wi‑Fi และมีพื้นที่ว่างอย่างน้อย 1 GB ไฟล์จะเก็บในพื้นที่ส่วนตัวของแอป", 12f, C.sub), t = 8, b = 12)
        p.add(btn("ดาวน์โหลดเครื่องมือที่ขาดทั้งหมด") { runAutoSetup() }, b = 8)
        p.add(btn("ข้ามไปก่อน", false) {
            prefs.edit().putBoolean("setup_complete", true).apply()
            showMain()
        })
        go(ScrollView(this).apply { addView(p) }) { showAutoSetup() }
        if (!prefs.getBoolean("setup_started", false)) {
            prefs.edit().putBoolean("setup_started", true).apply()
            p.postDelayed({ runAutoSetup() }, 500)
        }
    }

    private fun runAutoSetup() {
        val haveUefi = uefiFile().exists() && uefiFile().length() > 1_000_000
        val haveVirtio = virtioFile().exists() && virtioFile().length() > 10_000_000
        fun finishSetup() {
            prefs.edit().putBoolean("setup_complete", true).putBoolean("done", true).apply()
            val msg = if (qemuBundled()) {
                "เตรียมเครื่องมือเสร็จแล้ว สามารถเพิ่ม Windows ISO ภายหลังได้"
            } else {
                "ดาวน์โหลดไฟล์สนับสนุนเสร็จแล้ว แต่ยังไม่พบ libqemu.so ในแอป\n\nโปรเจกต์นี้ยังต้องบรรจุ QEMU ที่คอมไพล์สำหรับ Android ARM64 ก่อนจึงจะเริ่ม VM ได้"
            }
            AlertDialog.Builder(this).setTitle("ผลการเตรียมระบบ").setMessage(msg)
                .setPositiveButton("ดำเนินการต่อ") { _, _ -> showMain() }.show()
        }
        fun getVirtio() {
            if (haveVirtio) finishSetup()
            else downloadTo(virtioUrl, virtioFile(), "ดาวน์โหลดไดรเวอร์ VirtIO", 10_000_000L) { finishSetup() }
        }
        if (haveUefi) getVirtio()
        else downloadTo(uefiUrl, uefiFile(), "ดาวน์โหลด UEFI AArch64", 1_000_000L) { getVirtio() }
    }

    // ---------- หน้าเริ่มต้น ----------
    private fun showSplash() {
        val p = vbox(28).apply { gravity = Gravity.CENTER }
        p.add(Logo(this), w = dp(96), h = dp(96), b = 18)
        p.add(tv("WinArm", 34f, bold = true).apply { gravity = Gravity.CENTER })
        p.add(tv("Windows on Android", 15f, C.sub).apply { gravity = Gravity.CENTER }, b = 30)
        p.add(tv("Run · Install · Use\nAnywhere", 18f).apply { gravity = Gravity.CENTER }, b = 36)
        p.add(btn("เริ่มใช้งาน") { prefs.edit().putBoolean("done", true).apply(); showMain() }, b = 10)
        p.add(btn("แนะนำการใช้งาน", false) { guide() }, b = 10)
        p.add(btn("ดาวน์โหลด UEFI เดี๋ยวนี้", false) { downloadUefi { toast("พร้อมแล้ว") } })
        go(p)
    }

    private fun guide() {
        AlertDialog.Builder(this).setTitle("คู่มือการใช้งาน").setMessage(
            "1) ไฟล์ UEFI ดาวน์โหลดและเก็บในพื้นที่ส่วนตัวของ WinArm อัตโนมัติ (ต้องต่อเน็ต)\n" +
            "2) เปิดเมนูดาวน์โหลดใน WinArm แล้วดาวน์โหลด ISO จากหน้า Microsoft ภายในแอป\n" +
            "3) หน้าหลัก > สร้างเครื่องใหม่ > เลือก ISO ตั้งสเปก\n" +
            "4) กด เริ่ม แล้วติดตั้งตามปกติ (ช้ามาก เพราะเป็นโหมดจำลองล้วน)\n" +
            "5) ติดตั้งเสร็จแล้ว กดค้างที่การ์ดเครื่อง > ถอด ISO\n" +
            "6) อยากได้เน็ต: โหลดไดรเวอร์ VirtIO (หน้าดาวน์โหลด) เปิดสวิตช์ NAT ตอนสร้างเครื่อง " +
            "แล้วใน Windows เปิด Device Manager > Ethernet Controller > Update driver > เลือกซีดี virtio-win โฟลเดอร์ NetKVM\\w11\\ARM64\n\n" +
            "ถ้าติดตั้ง Windows 11 ไม่ผ่านเงื่อนไข: กด Shift+F10 พิมพ์ regedit เพิ่มคีย์ LabConfig (BypassTPMCheck, BypassSecureBootCheck = 1)"
        ).setPositiveButton("ตกลง", null).show()
    }

    // ---------- หน้าหลัก + แท็บ ----------
    private fun showMain() {
        onRun = false; fs = false
        window.decorView.systemUiVisibility = 0
        val col = vbox()
        val content = FrameLayout(this)
        col.add(content, h = 0, wt = 1f)
        val nav = hbox().apply { setBackgroundColor(C.nav) }
        col.add(nav)
        val items = listOf("🏠" to "หน้าหลัก", "🖥" to "เครื่องเสมือน", "📁" to "ไฟล์", "⚙" to "ตั้งค่า")
        fun render() {
            content.removeAllViews()
            content.addView(ScrollView(this).apply {
                addView(when (tab) { 0 -> homeTab(); 1 -> vmsTab(); 2 -> filesTab(); else -> settingsTab() })
            })
            nav.removeAllViews()
            items.forEachIndexed { i, (ic, lb) ->
                val c = if (i == tab) C.accent2 else C.sub
                val b = vbox(8).apply { gravity = Gravity.CENTER; setOnClickListener { tab = i; render() } }
                b.addView(tv(ic, 20f, c)); b.addView(tv(lb, 11f, c))
                nav.add(b, w = 0, wt = 1f)
            }
        }
        refresh = { render() }
        render()
        go(col, null)
    }

    private fun action(p: LinearLayout, ic: String, t: String, s: String, f: () -> Unit) {
        val c = card(12).apply { setOnClickListener { f() } }
        val r = hbox()
        r.add(tv(ic, 22f), w = dp(44))
        val col = vbox(); col.addView(tv(t, 15f, bold = true)); col.addView(tv(s, 12f, C.sub))
        r.add(col, w = 0, wt = 1f); r.add(tv("›", 22f, C.sub))
        c.addView(r); p.add(c, b = 8)
    }

    private fun homeTab(): View {
        val p = vbox(16)
        val head = hbox()
        head.add(Logo(this), w = dp(32), h = dp(32))
        head.add(tv("WinArm", 22f, bold = true), w = 0, wt = 1f, l = 10)
        head.add(tv("⚙", 22f, C.sub).apply { setOnClickListener { tab = 3; refresh() } })
        p.add(head, b = 14)
        p.add(tv("เครื่องเสมือนของฉัน", 16f, bold = true), b = 8)
        if (vms.isEmpty()) p.add(card().apply { addView(tv("ยังไม่มีเครื่อง กด \"สร้างเครื่องใหม่\" ด้านล่าง", 13f, C.sub)) }, b = 10)
        vms.forEach { p.add(vmCard(it), b = 10) }
        action(p, "➕", "สร้างเครื่องใหม่", "ติดตั้ง Windows จาก ISO") { wStep = 0; wIso = null; showWizard() }
        action(p, "📂", "นำเข้าเครื่องเสมือน", "ใช้ไฟล์ดิสก์ที่มีอยู่") { pickFile(3) }
        action(p, "⬇", "ดาวน์โหลด", "Windows ARM64, Tiny11, ไดรเวอร์, UEFI") { showDownload() }
        action(p, "📖", "คู่มือการใช้งาน", "วิธีติดตั้งและใช้งาน") { guide() }
        return p
    }

    private fun vmsTab(): View {
        val p = vbox(16)
        p.add(tv("เครื่องเสมือน", 22f, bold = true), b = 12)
        if (vms.isEmpty()) p.add(tv("ยังไม่มีเครื่อง", 13f, C.sub))
        vms.forEach { p.add(vmCard(it), b = 10) }
        return p
    }

    private fun vmCard(vm: Vm): View {
        val isRun = running?.id == vm.id && runner.running
        val c = card(12)
        val r = hbox()
        val th = FrameLayout(this).apply {
            background = round(C.accent, 12)
            addView(Logo(this@MainActivity), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        r.add(th, w = dp(72), h = dp(56), r = 12)
        val col = vbox()
        col.addView(tv(vm.name, 15f, bold = true))
        col.addView(tv(if (isRun) "● กำลังทำงาน" else "● ปิดเครื่อง", 12f, if (isRun) C.green else C.accent2))
        col.addView(tv("${vm.cpu} CPU · ${vm.ramMb / 1024} GB RAM · ${vm.diskGb} GB", 12f, C.sub))
        r.add(col, w = 0, wt = 1f); c.addView(r)
        c.add(btn(if (isRun) "เปิดจอ" else "▶  เริ่ม") { startVm(vm) }, t = 10)
        c.setOnLongClickListener { vmMenu(vm); true }
        return c
    }

    private fun vmMenu(vm: Vm) {
        AlertDialog.Builder(this).setTitle(vm.name)
            .setItems(arrayOf("ถอด ISO", "ลบเครื่อง")) { _, i ->
                if (i == 0) { vm.iso = null; Store.save(this, vms); toast("ถอด ISO แล้ว (มีผลตอนเริ่มครั้งหน้า)") }
                else AlertDialog.Builder(this).setMessage("ลบ \"${vm.name}\" และดิสก์ที่แอปสร้างให้?")
                    .setPositiveButton("ลบ") { _, _ ->
                        if (running?.id == vm.id) stopVm()
                        File(getExternalFilesDir(null), "${vm.id}.img").delete()
                        File(getExternalFilesDir(null), "${vm.id}-vars.fd").delete()
                        vms.remove(vm); Store.save(this, vms); refresh()
                    }.setNegativeButton("ยกเลิก", null).show()
            }.show()
    }

    private fun filesTab(): View {
        val p = vbox(16)
        p.add(tv("ไฟล์และดิสก์", 22f, bold = true), b = 6)
        p.add(tv("ไฟล์ในโฟลเดอร์แอป (ISO/ดิสก์ที่เลือกจากที่อื่นจะไม่แสดงที่นี่)", 12f, C.sub), b = 12)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val list = getExternalFilesDir(null)?.listFiles()?.filter {
            it.extension.lowercase() in listOf("iso", "img", "qcow2", "vhdx", "fd")
        }?.sortedBy { it.name } ?: emptyList()
        if (list.isEmpty()) p.add(tv("ยังไม่มีไฟล์", 13f, C.sub), b = 8)
        list.forEach { f ->
            val c = card(12).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("ลบ ${f.name}?")
                        .setPositiveButton("ลบ") { _, _ -> f.delete(); refresh() }
                        .setNegativeButton("ยกเลิก", null).show()
                }
            }
            c.addView(tv(f.name, 14f, bold = true))
            c.addView(tv("${sz(f.length())} · ${fmt.format(Date(f.lastModified()))}  (แตะเพื่อลบ)", 12f, C.sub))
            p.add(c, b = 8)
        }
        p.add(btn("＋ สร้างดิสก์ใหม่ (128 GB)", false) {
            val f = File(getExternalFilesDir(null), "disk-${System.currentTimeMillis() / 1000}.img")
            RandomAccessFile(f, "rw").use { it.setLength(128L * 1024 * 1024 * 1024) }; refresh()
        }, t = 6)
        return p
    }

    private fun rowOf(title: String, sub: String, right: View): View {
        val r = hbox(); val col = vbox()
        col.addView(tv(title, 15f, bold = true)); if (sub.isNotEmpty()) col.addView(tv(sub, 12f, C.sub))
        r.add(col, w = 0, wt = 1f); r.addView(right)
        return r
    }

    private fun toggleRow(title: String, key: String, def: Boolean, dark: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply(); onChange(v) }
        }
        val r = hbox(); r.add(tv(title, 15f, if (dark) C.text else Color.BLACK), w = 0, wt = 1f); r.addView(sw)
        return r
    }

    private fun fpsDialog() {
        val o = arrayOf("15", "30", "60")
        AlertDialog.Builder(this).setTitle("FPS จำกัด").setItems(o) { _, i ->
            prefs.edit().putInt("fps", o[i].toInt()).apply(); vnc?.fps = o[i].toInt(); refresh()
        }.show()
    }

    private fun settingsTab(): View {
        val p = vbox(16)
        p.add(tv("ตั้งค่า", 22f, bold = true), b = 12)
        val c = card()
        c.add(rowOf("ไฟล์ UEFI", uefiUri()?.let { nameOf(this, it) }
            ?: if (uefiFile().exists()) "ดาวน์โหลดอัตโนมัติแล้ว" else "จะดาวน์โหลดอัตโนมัติตอนกดเริ่ม",
            btn("เลือก", false) { pickFile(2) }), b = 8)
        c.add(btn("ดาวน์โหลด UEFI ใหม่", false) {
            prefs.edit().remove("uefi").remove("uefi_copied").apply(); downloadUefi { refresh() }
        }, b = 14)
        c.add(rowOf("FPS จำกัด", "ลดเพื่อประหยัดแรงเครื่อง", btn("${prefs.getInt("fps", 30)} FPS", false) { fpsDialog() }), b = 14)
        c.add(toggleRow("โหมดทัชแพด (ลากเลื่อนเคอร์เซอร์)", "pad", false, true) { vnc?.touchpad = it }, b = 10)
        c.add(toggleRow("แสดงปุ่มลัด (Ctrl/Alt/Win)", "keys", true, true) { }, b = 10)
        c.add(toggleRow("แนบไดรเวอร์ VirtIO เป็นซีดีรอม", "attach_virtio", true, true) { })
        p.add(c, b = 12)
        val a = card()
        a.addView(tv("เกี่ยวกับ", 15f, bold = true))
        a.addView(tv("WinArm 0.1 · QEMU โหมด TCG (จำลองล้วน ไม่ใช้ KVM)\nไม่รองรับในรุ่นนี้: USB, เสียง, โฟลเดอร์แชร์, Snapshot", 12f, C.sub))
        p.add(a)
        return p
    }

    /** เปิดหน้าดาวน์โหลด Microsoft ภายใน WinArm และจับการดาวน์โหลด ISO ลงพื้นที่ส่วนตัวของแอป */
    private fun openInAppBrowser(title: String, startUrl: String, expectIso: Boolean = false) {
        val page = vbox(0)
        val bar = hbox().apply {
            setBackgroundColor(C.nav)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.add(btn("‹ กลับ", false) { showDownload() }, w = dp(88))
        bar.add(tv(title, 15f, bold = true), w = 0, wt = 1f)
        bar.add(btn("↻", false) { /* reload is wired below */ }, w = dp(44))
        val web = WebView(this)
        bar.getChildAt(2).setOnClickListener { web.reload() }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Prefer Thai UI/content where the official site supports localization.
        web.settings.userAgentString = web.settings.userAgentString + " WinArmLocale/th-TH"
        web.settings.setSupportZoom(true)
        web.settings.loadsImagesAutomatically = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // Keep normal HTTPS navigation inside the app. Do not launch another app.
                return !(url?.startsWith("http://") == true || url?.startsWith("https://") == true)
            }
        }
        web.webChromeClient = WebChromeClient()
        // Microsoft may show the language picker only after edition validation.
        // Select Thai when an explicit Thai option appears, but never submit the page automatically.
        val selectThaiScript = """
            (function(){
              var selects = Array.from(document.querySelectorAll('select'));
              for (var s of selects) {
                var opts = Array.from(s.options || []);
                var o = opts.find(function(x){ return /ไทย|thai/i.test((x.text||'')+' '+(x.value||'')); });
                if (o && !s.dataset.winarmThaiSet) {
                  s.value = o.value; s.dataset.winarmThaiSet = '1';
                  s.dispatchEvent(new Event('input',{bubbles:true}));
                  s.dispatchEvent(new Event('change',{bubbles:true}));
                }
              }
            })();
        """.trimIndent()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(selectThaiScript, null)
                view?.postDelayed({ view.evaluateJavascript(selectThaiScript, null) }, 1200)
                view?.postDelayed({ view.evaluateJavascript(selectThaiScript, null) }, 3000)
            }
        }
        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (url.isNullOrBlank() || !(url.startsWith("https://") || url.startsWith("http://"))) {
                toast("ลิงก์ดาวน์โหลดนี้ไม่รองรับ กรุณาใช้ลิงก์ HTTPS โดยตรง")
            } else {
                val raw = Regex("""filename[^=]*=\"?([^\";]+)""", RegexOption.IGNORE_CASE)
                    .find(contentDisposition.orEmpty())?.groupValues?.getOrNull(1)
                var filename = raw?.substringAfterLast("'")?.trim()?.trim('"')
                    ?: Uri.parse(url).lastPathSegment?.substringBefore('?')
                    ?: if (expectIso) "Windows11_ARM64.iso" else "download.bin"
                filename = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (expectIso && !filename.lowercase().endsWith(".iso")) filename = "Windows11_ARM64.iso"
                if (expectIso && contentLength > 0 && contentLength < 50_000_000L) {
                    toast("ไฟล์ที่ได้รับดูเหมือนไม่ใช่ ISO เต็ม อาจต้องเลือก ARM64 และกดดาวน์โหลดบนหน้า Microsoft")
                }
                val dest = File(getExternalFilesDir(null), filename)
                downloadTo(url, dest, "ดาวน์โหลด $filename", if (expectIso) 50_000_000L else 1L, headers = buildMap {
                    if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                    put("Referer", web.url ?: startUrl)
                }) {
                    toast("บันทึกไว้ใน WinArm แล้ว: $filename")
                    showDownload()
                }
            }
        })
        page.add(bar)
        page.add(web, h = 0, wt = 1f)
        go(page) { showDownload() }
        web.loadUrl(startUrl)
    }

    private fun showDownload() {
        val p = vbox(16)
        val head = hbox(); head.add(tv("‹", 30f).apply { setOnClickListener { showMain() } }, r = 12)
        head.addView(tv("ดาวน์โหลด", 20f, bold = true)); p.add(head, b = 10)
        fun section(t: String) { p.add(tv(t, 13f, C.accent2, true), t = 10, b = 6) }
        fun link(t: String, sub: String, url: String) {
            val c = card(12).apply { setOnClickListener { openInAppBrowser(t, url, url.contains("microsoft.com/software-download/windows11arm64")) } }
            c.addView(tv(t, 15f, bold = true)); c.addView(tv(sub, 12f, C.sub)); p.add(c, b = 8)
        }
        fun dl(t: String, sub: String, have: Boolean, f: () -> Unit) {
            val c = card(12).apply { setOnClickListener { f() } }
            c.addView(tv(t, 15f, bold = true))
            c.addView(tv(if (have) "✓ มีแล้ว (แตะเพื่อดาวน์โหลดใหม่)  $sub" else "แตะเพื่อดาวน์โหลดในแอป  $sub", 12f, if (have) C.green else C.sub))
            p.add(c, b = 8)
        }
        section("WINDOWS ARM64 · ภาษาไทย")
        p.add(tv("ตั้งค่าภาษาที่ต้องการ: ไทย (ประเทศไทย) — WinArm จะพยายามเลือกภาษาไทยอัตโนมัติเมื่อหน้า Microsoft แสดงตัวเลือกภาษา หากไม่มีตัวเลือกภาษาไทย ให้เลือกด้วยตนเองในหน้าเว็บ", 12f, C.sub), b = 8)
        link("Windows 11 ARM64 — Microsoft (ภาษาไทย)", "เปิดหน้า Microsoft ภาษาไทยใน WinArm และบันทึก ISO ลงในแอป",
            "https://www.microsoft.com/th-th/software-download/windows11arm64")
        section("TINY11 โดย NTDEV (นักพัฒนาอิสระ)")
        link("tiny11builder (GitHub ทางการ)", "สคริปต์สร้าง Tiny11 จาก ISO ของคุณเอง", "https://github.com/ntdevlabs/tiny11builder")
        link("ISO สำเร็จรูปบน Archive.org", "ค้นหา tiny11 (x64 / ARM64) ที่ NTDEV อัปโหลด", "https://archive.org/search?query=tiny11+ntdev")
        p.add(tv("⚠ Tiny11 เป็นภาพ Windows ที่ถูกดัดแปลงโดยบุคคลที่สาม Microsoft ไม่รับรอง ใช้ด้วยความเสี่ยงของคุณเอง " +
            "ถ้าไม่ไว้ใจ ให้ใช้ tiny11builder สร้างเองจาก ISO ทางการ", 11f, C.sub), b = 8)
        section("ไฟล์ที่จำเป็น (โหลดในแอปได้เลย)")
        dl("UEFI (ใช้บูตเครื่อง)", "", uefiFile().exists()) { downloadUefi { showDownload() } }
        dl("ไดรเวอร์ VirtIO (เครือข่าย ฯลฯ)", "~700 MB", virtioFile().exists()) {
            downloadTo(virtioUrl, virtioFile(), "ดาวน์โหลดไดรเวอร์ VirtIO", 10_000_000L) { showDownload() }
        }
        section("ดาวน์โหลด ISO จากลิงก์")
        val c = card(12).apply { setOnClickListener { urlDialog() } }
        c.addView(tv("วางลิงก์ไฟล์ ISO โดยตรง", 15f, bold = true))
        c.addView(tv("เช่น ลิงก์ไฟล์ .iso จาก Archive.org ไฟล์จะอยู่ในแอป เลือกได้ตอนสร้างเครื่อง", 12f, C.sub))
        p.add(c, b = 8)
        go(ScrollView(this).apply { addView(p) }) { showMain() }
    }

    private fun urlDialog() {
        val et = EditText(this).apply { hint = "https://.../tiny11.iso"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("ลิงก์ไฟล์ ISO").setView(et)
            .setPositiveButton("ดาวน์โหลด") { _, _ ->
                val u = et.text.toString().trim()
                if (!u.startsWith("http")) { toast("ลิงก์ไม่ถูกต้อง"); return@setPositiveButton }
                var n = Uri.parse(u).lastPathSegment ?: "download"
                n = n.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (!n.lowercase().endsWith(".iso")) n += ".iso"
                downloadTo(u, File(getExternalFilesDir(null), n), "ดาวน์โหลด $n", 50_000_000L) { toast("เสร็จแล้ว: $n"); showDownload() }
            }.setNegativeButton("ยกเลิก", null).show()
    }

    private fun pickAppIso() {
        val l = getExternalFilesDir(null)?.listFiles()?.filter { it.extension.lowercase() == "iso" && it.name != "virtio-win.iso" } ?: emptyList()
        if (l.isEmpty()) { toast("ยังไม่มี ISO ที่ดาวน์โหลดในแอป (ไปที่ ดาวน์โหลด ISO > วางลิงก์)"); return }
        AlertDialog.Builder(this).setTitle("เลือก ISO")
            .setItems(l.map { "${it.name} (${sz(it.length())})" }.toTypedArray()) { _, i ->
                wIso = Uri.fromFile(l[i]).toString(); showWizard()
            }.show()
    }

    // ---------- สร้างเครื่อง ----------
    private fun seek(p: LinearLayout, label: String, min: Int, max: Int, v: Int, fmt: (Int) -> String, onC: (Int) -> Unit) {
        val r = hbox(); val vt = tv(fmt(v), 14f, C.accent2, true)
        r.add(tv(label, 14f), w = dp(72))
        r.add(SeekBar(this).apply {
            this.max = max - min; progress = v - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, u: Boolean) { onC(pr + min); vt.text = fmt(pr + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, w = 0, wt = 1f)
        r.add(vt, w = dp(64)); p.add(r, t = 8, b = 8)
    }

    private fun showWizard() {
        val p = vbox(16)
        val head = hbox(); head.add(tv("‹", 30f).apply { setOnClickListener { onBackPressed() } }, r = 12)
        head.addView(tv("สร้างเครื่องเสมือนใหม่", 20f, bold = true)); p.add(head, b = 14)
        val steps = hbox()
        listOf("เลือก ISO", "ตั้งค่าสเปก", "สร้างดิสก์", "เริ่มติดตั้ง").forEachIndexed { i, s ->
            val col = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.add(tv("${i + 1}", 13f, bold = true).apply {
                gravity = Gravity.CENTER; background = round(if (i <= wStep) C.accent else C.card2, 14)
            }, w = dp(28), h = dp(28))
            col.addView(tv(s, 10f, if (i <= wStep) C.text else C.sub))
            steps.add(col, w = 0, wt = 1f)
        }
        p.add(steps, b = 14)
        val c = card(16); nameEt = null
        when (wStep) {
            0 -> {
                c.addView(tv("ไฟล์ ISO", 15f, bold = true))
                c.add(rowOf(wIso?.let { nameOf(this, Uri.parse(it)) } ?: "ยังไม่ได้เลือก", "Windows 11/10 ARM64",
                    btn("เลือกไฟล์", false) { pickFile(1) }), t = 8, b = 8)
                c.add(btn("เลือกจาก ISO ที่ดาวน์โหลดในแอป", false) { pickAppIso() }, b = 14)
                c.addView(tv("ชื่อเครื่อง", 15f, bold = true))
                nameEt = EditText(this).apply { setText(wName); setTextColor(C.text); setSingleLine() }
                c.add(nameEt!!)
            }
            1 -> {
                c.addView(tv("ปรับสเปกเครื่องเสมือน", 15f, bold = true))
                seek(c, "CPU", 1, 8, wCpu, { "$it Core" }) { wCpu = it }
                seek(c, "RAM", 2, 8, wRam, { "$it GB" }) { wRam = it }
                c.addView(tv("ควรเหลือแรมให้ Android อย่างน้อย 4 GB", 11f, C.sub))
            }
            2 -> {
                c.addView(tv("ขนาดดิสก์เสมือน", 15f, bold = true))
                val chips = hbox()
                listOf(64, 128, 256, 512).forEach { g ->
                    chips.add(tv("$g GB", 14f).apply {
                        gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
                        background = round(if (g == wDisk) C.accent else C.card2, 10)
                        setOnClickListener { wDisk = g; showWizard() }
                    }, w = 0, wt = 1f, r = 6)
                }
                c.add(chips, t = 10, b = 10)
                c.addView(tv("สร้างแบบ sparse จะใช้พื้นที่จริงตามที่ติดตั้งเท่านั้น", 11f, C.sub))
            }
            else -> {
                c.addView(tv("สรุป", 15f, bold = true))
                c.addView(tv("$wName\n$wCpu CPU · $wRam GB RAM · $wDisk GB\nISO: ${wIso?.let { nameOf(this, Uri.parse(it)) }}", 13f, C.sub))
                c.add(toggleRow("เครือข่าย NAT (ทดลอง)", "net_tmp", false, true) { wNet = it }, t = 12, b = 8)
                c.add(toggleRow("แนบไดรเวอร์ VirtIO (ถ้าโหลดไว้แล้ว)", "attach_virtio", true, true) { })
            }
        }
        p.add(c, b = 14)
        val nav = hbox()
        if (wStep > 0) nav.add(btn("ย้อนกลับ", false) { onBackPressed() }, w = 0, wt = 1f, r = 8)
        nav.add(btn(if (wStep == 3) "สร้างและเริ่มติดตั้ง" else "ต่อไป") {
            if (wStep == 0) {
                if (wIso == null) { toast("เลือกไฟล์ ISO ก่อน"); return@btn }
                nameEt?.text?.toString()?.let { if (it.isNotBlank()) wName = it }
            }
            if (wStep < 3) { wStep++; showWizard() } else {
                val vm = Vm(System.currentTimeMillis().toString(), wName, wCpu, wRam * 1024, wDisk, wIso, null, wNet)
                vms.add(vm); Store.save(this, vms)
                startVm(vm); if (!onRun) showMain()
            }
        }, w = 0, wt = 1f)
        p.add(nav)
        go(ScrollView(this).apply { addView(p) }) { if (wStep > 0) { wStep--; showWizard() } else showMain() }
    }

    // ---------- เริ่ม/หยุดเครื่อง ----------
    private fun startVm(vm: Vm) {
        if (runner.running) {
            if (running?.id == vm.id) showRun(vm) else toast("มีเครื่องอื่นกำลังทำงานอยู่ ปิดก่อน")
            return
        }
        if (!uefiReady()) { downloadUefi { startVm(vm) }; return }
        val err = runner.start(vm, uefiUri()) { log ->
            runOnUiThread {
                running = null; vnc?.disconnect()
                AlertDialog.Builder(this).setTitle("QEMU หยุดทำงาน").setMessage(log.ifBlank { "(ไม่มีข้อความ)" })
                    .setPositiveButton("ตกลง", null).show()
                if (onRun) leaveRun() else refresh()
            }
        }
        if (err != null) {
            AlertDialog.Builder(this).setTitle("เริ่มไม่ได้").setMessage(err).setPositiveButton("ตกลง", null).show(); return
        }
        running = vm; showRun(vm)
    }

    private fun stopVm() { runner.stop(); running = null; vnc?.disconnect(); if (onRun) leaveRun() }
    private fun leaveRun() { showMain() }

    private fun modBtn(label: String, sym: Int): View {
        var on = false
        val b = btn(label, false) {}
        b.setOnClickListener { on = !on; vnc?.key(on, sym); b.background = round(if (on) C.accent else C.card2, 12) }
        return b
    }

    private fun showRun(vm: Vm) {
        onRun = true
        val col = vbox().apply { setBackgroundColor(Color.BLACK) }
        val top = hbox().apply { setBackgroundColor(C.nav); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val ttl = vbox(); ttl.addView(tv(vm.name, 15f, bold = true)); ttl.addView(tv("● กำลังทำงาน", 11f, C.green))
        top.add(ttl, w = 0, wt = 1f)
        top.add(tv("⌨", 22f).apply { setOnClickListener { toggleKbd() } }, r = 18)
        top.add(tv("⚙", 22f).apply { setOnClickListener { tools(vm) } }, r = 18)
        top.add(tv("⏻", 22f).apply { setOnClickListener { powerDialog() } })
        col.add(top)
        val v = VncView(this).also { it.fps = prefs.getInt("fps", 30); it.touchpad = prefs.getBoolean("pad", false) }
        vnc = v
        col.add(v, h = 0, wt = 1f)
        val bar = hbox().apply { setBackgroundColor(C.nav); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        bar.add(modBtn("Ctrl", 0xffe3), w = 0, wt = 1f, r = 6)
        bar.add(modBtn("Alt", 0xffe9), w = 0, wt = 1f, r = 6)
        bar.add(modBtn("⊞", 0xffeb), w = 0, wt = 1f, r = 6)
        bar.add(btn("⌨", false) { toggleKbd() }, w = 0, wt = 1f, r = 6)
        bar.add(btn("⋯", false) { tools(vm) }, w = 0, wt = 1f)
        bar.visibility = if (prefs.getBoolean("keys", true)) View.VISIBLE else View.GONE
        col.add(bar)
        topBar = top; keyBar = bar
        go(col) { powerDialog() }
        v.connect()
    }

    private fun toggleKbd() {
        vnc?.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun powerDialog() {
        AlertDialog.Builder(this).setTitle("เครื่องเสมือน")
            .setItems(arrayOf("ย่อ (ให้เครื่องทำงานต่อ)", "ปิดเครื่อง (ตัดทันที)", "ยกเลิก")) { _, i ->
                if (i == 0) { vnc?.disconnect(); leaveRun() } else if (i == 1) stopVm()
            }.show()
    }

    private fun tools(vm: Vm) {
        val v = vnc ?: return
        val d = AlertDialog.Builder(this).create()
        val p = vbox(18).apply { background = round(C.sheet, 24) }
        val h = hbox(); h.add(tv("เครื่องมือขณะใช้งาน", 17f, Color.BLACK, true), w = 0, wt = 1f)
        h.addView(tv("✕", 20f, Color.DKGRAY).apply { setOnClickListener { d.dismiss() } }); p.add(h, b = 12)
        val tiles = hbox()
        fun tile(ic: String, lb: String, f: () -> Unit) {
            val t = vbox(8).apply { gravity = Gravity.CENTER; setOnClickListener { f() } }
            t.add(tv(ic, 22f, Color.BLACK).apply { gravity = Gravity.CENTER; background = round(0xFFE3EAF6.toInt(), 30) },
                w = dp(52), h = dp(52), b = 4)
            t.addView(tv(lb, 11f, Color.DKGRAY)); tiles.add(t, w = 0, wt = 1f)
        }
        tile("⌨", "คีย์บอร์ด") { d.dismiss(); toggleKbd() }
        tile("🖱", "คลิกขวา") { v.rightClick = !v.rightClick; toast(if (v.rightClick) "คลิกขวา: เปิด" else "คลิกขวา: ปิด") }
        tile("⛶", "เต็มหน้าจอ") {
            fs = !fs
            topBar?.visibility = if (fs) View.GONE else View.VISIBLE
            keyBar?.visibility = if (fs || !prefs.getBoolean("keys", true)) View.GONE else View.VISIBLE
            window.decorView.systemUiVisibility = if (fs) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else 0
            d.dismiss()
        }
        tile("📷", "จับภาพ") {
            val bm: Bitmap? = v.snapshot()
            if (bm == null) toast("ยังไม่มีภาพ") else {
                val f = File(getExternalFilesDir("screenshots"), "shot-${System.currentTimeMillis() / 1000}.png")
                f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("บันทึก: ${f.path}")
            }
        }
        p.add(tiles, b = 14)
        p.add(tv("ปรับประสิทธิภาพ", 15f, Color.BLACK, true), b = 8)
        p.add(rowOf("FPS จำกัด", "", btn("${prefs.getInt("fps", 30)} FPS", false) { d.dismiss(); fpsDialog() }), b = 8)
        p.add(toggleRow("โหมดทัชแพด", "pad", false, false) { v.touchpad = it }, b = 6)
        p.add(toggleRow("แสดงปุ่มลัด", "keys", true, false) { keyBar?.visibility = if (it && !fs) View.VISIBLE else View.GONE }, b = 10)
        p.add(btn("Ctrl + Alt + Del", false) {
            val s = intArrayOf(0xffe3, 0xffe9, 0xffff)
            s.forEach { v.key(true, it) }; s.reversed().forEach { v.key(false, it) }
        }, b = 8)
        val off = btn("ปิดเครื่อง") { d.dismiss(); stopVm() }
        off.background = round(C.red, 12); p.add(off)
        d.setView(p); d.show()
        d.window?.setBackgroundDrawable(null); d.window?.setGravity(Gravity.BOTTOM)
    }

    // ---------- เลือกไฟล์ ----------
    private fun pickFile(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, code)
    }

    @Deprecated("simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        when (req) {
            1 -> { wIso = u.toString(); showWizard() }
            2 -> { prefs.edit().putString("uefi", u.toString()).apply(); toast("เลือก UEFI แล้ว"); if (prefs.getBoolean("done", false)) refresh() }
            3 -> {
                val n = nameOf(this, u).substringBeforeLast('.')
                vms.add(Vm(System.currentTimeMillis().toString(), n, disk = u.toString())); Store.save(this, vms)
                tab = 0; showMain()
            }
        }
    }

    override fun onDestroy() { if (isFinishing) runner.stop(); super.onDestroy() }
}
