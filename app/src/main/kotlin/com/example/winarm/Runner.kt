package com.example.winarm

import android.app.Activity
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile

class Runner(private val a: Activity) {
    private var proc: Process? = null
    private val pfds = mutableListOf<ParcelFileDescriptor>()
    private val tail = mutableListOf<String>()
    @Volatile private var stopping = false
    val running get() = proc?.isAlive == true

    private fun fdPath(u: Uri, mode: String): String {
        val p = a.contentResolver.openFileDescriptor(u, mode)!!
        pfds += p
        return "/proc/${android.os.Process.myPid()}/fd/${p.fd}"
    }

    private fun closeFds() { pfds.forEach { try { it.close() } catch (_: Exception) {} }; pfds.clear() }

    /** คืนข้อความ error ถ้าเริ่มไม่ได้ */
    fun start(vm: Vm, uefi: Uri?, onExit: (String) -> Unit): String? {
        if (running) return null
        val dir = a.getExternalFilesDir(null)!!
        try {
            val sp = a.getSharedPreferences("winarm", 0)
            val code = File(dir, "edk2-aarch64-code.fd")
            if (uefi != null && (!code.exists() || sp.getString("uefi_copied", null) != uefi.toString())) {
                a.contentResolver.openInputStream(uefi)!!.use { i -> code.outputStream().use { o -> i.copyTo(o) } }
                sp.edit().putString("uefi_copied", uefi.toString()).apply()
            }
            // pflash ของเครื่อง virt ต้องมีขนาด 64 MB พอดี ไฟล์ UEFI ที่เล็กกว่าให้ต่อท้ายด้วย 0
            val flash = 64L * 1024 * 1024
            if (code.exists() && code.length() < flash) RandomAccessFile(code, "rw").use { it.setLength(flash) }
            if (!code.exists()) return "ยังไม่ได้เลือกไฟล์ UEFI (edk2-aarch64-code.fd)\nไปที่ ตั้งค่า > UEFI"
            val lib = File(a.applicationInfo.nativeLibraryDir, "libqemu.so")
            if (!lib.exists()) return "ยังไม่มี libqemu.so ในแอป (ดู README / tools/fetch_qemu.py)"

            val vars = File(dir, "${vm.id}-vars.fd")
            if (!vars.exists()) RandomAccessFile(vars, "rw").use { it.setLength(flash) }

            val diskPath: String; val fmt: String
            if (vm.disk != null) {
                val u = Uri.parse(vm.disk)
                diskPath = fdPath(u, "rw")
                fmt = when (nameOf(a, u).substringAfterLast('.', "").lowercase()) {
                    "qcow2" -> "qcow2"; "vhdx" -> "vhdx"; else -> "raw"
                }
            } else {
                val d = File(dir, "${vm.id}.img")
                if (!d.exists()) RandomAccessFile(d, "rw").use { it.setLength(vm.diskGb * 1024L * 1024 * 1024) }
                diskPath = d.path; fmt = "raw"
            }

            val args = mutableListOf(
                lib.path, "-M", "virt,highmem=on", "-cpu", "max", "-smp", "${vm.cpu}", "-m", "${vm.ramMb}",
                "-accel", "tcg,thread=multi",
                "-drive", "if=pflash,format=raw,unit=0,file=${code.path},readonly=on",
                "-drive", "if=pflash,format=raw,unit=1,file=${vars.path}",
                "-device", "ramfb", "-device", "qemu-xhci", "-device", "usb-kbd", "-device", "usb-tablet",
                "-drive", "file=$diskPath,format=$fmt,if=none,id=hd0",
                "-device", "nvme,drive=hd0,serial=winarm,bootindex=0",
                "-display", "none", "-vnc", "127.0.0.1:0"
            )
            args += if (vm.net) listOf("-nic", "user,model=virtio-net-pci") else listOf("-nic", "none")
            val vf = File(dir, "virtio-win.iso")
            if (vf.exists() && sp.getBoolean("attach_virtio", true)) args += listOf(
                "-drive", "file=${vf.path},format=raw,if=none,id=cd1,media=cdrom,readonly=on",
                "-device", "usb-storage,drive=cd1"
            )
            vm.iso?.let {
                args += listOf(
                    "-drive", "file=${fdPath(Uri.parse(it), "r")},format=raw,if=none,id=cd0,media=cdrom,readonly=on",
                    "-device", "usb-storage,drive=cd0,bootindex=1"
                )
            }
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = a.applicationInfo.nativeLibraryDir
            stopping = false; tail.clear()
            val p = pb.start(); proc = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { l ->
                    synchronized(tail) { tail += l; if (tail.size > 8) tail.removeAt(0) }
                }
                p.waitFor(); closeFds()
                if (!stopping) onExit(synchronized(tail) { tail.joinToString("\n") })
            }.start()
        } catch (e: Exception) { closeFds(); return "เริ่มไม่ได้: ${e.message}" }
        return null
    }

    fun stop() { stopping = true; proc?.destroy(); proc = null }
}
