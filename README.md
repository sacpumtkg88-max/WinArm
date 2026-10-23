# WinArm: รัน Windows ARM64 บน Android ด้วย QEMU (TCG)

## ต้องเตรียมเอง 3 อย่าง
1. **QEMU สำหรับ Android arm64** ตั้งชื่อ `libqemu.so` วางที่ `app/src/main/jniLibs/arm64-v8a/`
   - ไลบรารีที่ QEMU ต้องใช้ (glib, pixman, ฯลฯ) ก็ใส่โฟลเดอร์เดียวกัน ชื่อต้องขึ้นต้น `lib` ลงท้าย `.so`
   - เอาจาก Termux (แพ็กเกจ qemu-system-aarch64) หรือจาก APK ของ Vectras VM
     (ต้องเป็นบิลด์ที่ลิงก์กับ libc ของ Android ใช้ได้ในไดเรกทอรีนี้)
2. **UEFI**: `edk2-aarch64-code.fd` (ใน QEMU package)
3. **ดิสก์วินโดวส์**: `.qcow2` / `.vhdx` / `.img`
   - ติดตั้งใหม่: วาง `install.iso` (Windows 11 ARM64) แล้วสร้างดิสก์เปล่า
     `qemu-img create -f qcow2 win.qcow2 64G`

## ส่งไฟล์เข้าเครื่อง
คัดลอก 2 ข้อหลังไปที่ `/sdcard/Android/data/com.example.winarm/files/`

## ใช้งาน
เปิดแอป > Start (บูตครั้งแรกใช้เวลานาน) > แตะ = คลิกซ้าย, ปุ่ม R-click สลับเป็นคลิกขวา, ปุ่ม Kbd เปิดคีย์บอร์ด

## ข้อจำกัด
- ใช้ TCG ล้วน จึงช้ามาก
- ไม่มีเน็ตในเครื่องจำลอง (`-nic none`)
- QEMU รันในโปรเซสของแอป ถ้าระบบปิดแอปเบื้องหลังก็ดับ

## ได้ libqemu.so
บนคอมที่ต่อเน็ต (ต้องมี python3, patchelf, binutils):
    python3 tools/fetch_qemu.py
แล้วก๊อป out/jniLibs/arm64-v8a/* ไป app/src/main/jniLibs/arm64-v8a/ (และ out/edk2-aarch64-code.fd ไว้เลือกในแอป)

## หน้าจอแอป
Splash > หน้าหลัก (การ์ดเครื่อง, สร้าง/นำเข้า/ดาวน์โหลด ISO/คู่มือ) > วิซาร์ดสร้างเครื่อง 4 ขั้น >
หน้าจอเครื่อง (แถบบน, ปุ่ม Ctrl/Alt/Win, เมนูเครื่องมือ) พร้อมแท็บ เครื่องเสมือน / ไฟล์ / ตั้งค่า

## การดาวน์โหลดภายในแอป (ปรับปรุงในรุ่นนี้)
- เมนู **ดาวน์โหลด > Windows 11 ARM64 — Microsoft** เปิดหน้า Microsoft ภายใน WebView ของ WinArm ไม่เปิดเบราว์เซอร์ภายนอก
- เมื่อหน้าเว็บเริ่มดาวน์โหลดไฟล์ ISO แอปจะรับ URL แล้วดาวน์โหลดลงพื้นที่ส่วนตัวของแอป พร้อมแถบความคืบหน้าและยกเลิกได้
- เนื่องจาก Microsoft สร้างลิงก์ ISO แบบชั่วคราวตามตัวเลือก/ภูมิภาค ผู้ใช้ยังต้องเลือกตัวเลือกและกดดาวน์โหลดบนหน้า Microsoft เอง แอปไม่สร้างลิงก์ ISO ปลอมหรือโฮสต์ไฟล์ Windows เอง
- UEFI และ VirtIO ดาวน์โหลดจากเมนูในแอปและเก็บในพื้นที่แอปเช่นกัน
- QEMU ทำงานเป็นโปรเซสลูกภายใต้ UID ของแอป WinArm และไม่ต้องเปิดแอปจำลองภายนอก อย่างไรก็ตาม Android อาจหยุดโปรเซสเมื่อแอปถูกปิด/ระบบฆ่าเบื้องหลัง
- **ข้อจำกัดสำคัญ:** ZIP นี้ยังไม่ได้บรรจุ `libqemu.so` ดังนั้นการดาวน์โหลด ISO/UEFI ใช้ได้ แต่การเริ่มเครื่องเสมือนจะยังไม่ทำงานจนกว่าจะใส่ QEMU build ที่เข้ากันได้กับ Android arm64

## Thai language preference
The in-app Microsoft Windows 11 ARM64 page now opens at the Thai-localized `th-th` page. WinArm also attempts to select an explicit Thai/Thailand option if the official page renders a language `<select>` control. It does not automatically submit or download until the user proceeds. Microsoft controls which language options and download flow are available; if Thai is not offered for a specific image, select another available official option or use a Thai-language ISO from a trusted, licensed source.

## ทางลัด: ให้ GitHub สร้าง APK ที่มี libqemu.so ให้เลย (ไม่ต้องใช้คอม)
1. สร้าง repo บน GitHub แล้วอัปโหลดโฟลเดอร์โปรเจกต์นี้ทั้งหมด
2. แท็บ **Actions** > **Build WinArm APK** > **Run workflow**
3. รอประมาณ 5–10 นาที แล้วโหลด artifact `WinArm-apk` (ได้ `app-debug.apk` ที่มี QEMU อยู่ในตัว) ติดตั้งได้เลย
4. artifact `edk2-aarch64-code` คือไฟล์ UEFI ให้เลือกในแอป ตั้งค่า > UEFI
