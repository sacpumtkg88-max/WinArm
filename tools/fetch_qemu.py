#!/usr/bin/env python3
"""
ดึง qemu-system-aarch64 จากคลัง Termux (aarch64) แล้วจัดเป็น libqemu.so + ไลบรารีที่ต้องใช้
เหมาะกับ jniLibs ของแอป Android

ต้องมีบนคอม (Linux/WSL/macOS): python3, patchelf, ar (binutils), xz
ใช้:  python3 tools/fetch_qemu.py
ผลลัพธ์:  out/jniLibs/arm64-v8a/*.so   และ  out/edk2-aarch64-code.fd (ถ้าเจอ)
"""
import gzip, io, lzma, os, re, shutil, subprocess, sys, tarfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"
ROOT_PKG = "qemu-system-aarch64-headless"
SKIP = {"termux-exec", "termux-tools", "termux-licenses", "termux-keyring", "termux-core",
        "dash", "bash", "coreutils", "dpkg", "apt", "busybox", "resolv-conf", "ncurses-utils"}
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
          "libstdc++.so", "libEGL.so", "libGLESv2.so", "libGLESv1_CM.so", "libvulkan.so",
          "libOpenSLES.so", "libnativewindow.so", "libjnigraphics.so", "libmediandk.so"}
W = "work"; OUT = "out/jniLibs/arm64-v8a"


def get(url):
    print("GET", url)
    with urllib.request.urlopen(url) as r:
        return r.read()


def load_index():
    base = f"{REPO}/dists/stable/main/binary-{ARCH}/Packages"
    for ext, dec in ((".xz", lzma.decompress), (".gz", gzip.decompress), ("", lambda b: b)):
        try:
            return dec(get(base + ext)).decode("utf-8", "replace")
        except Exception as e:
            print("  ไม่ได้:", e)
    sys.exit("โหลดรายการแพ็กเกจไม่ได้ (ตรวจอินเทอร์เน็ต/ที่อยู่คลัง)")


def parse(text):
    pk = {}
    for st in text.split("\n\n"):
        f = {}
        key = None
        for line in st.splitlines():
            if line[:1] in " \t" and key:
                f[key] += " " + line.strip()
            elif ":" in line:
                key, v = line.split(":", 1); f[key] = v.strip()
        if "Package" in f:
            pk[f["Package"]] = f
    return pk


def deps(f):
    out = []
    for d in f.get("Depends", "").split(","):
        d = d.strip()
        if d:
            out.append(re.split(r"[ (]", d.split("|")[0].strip())[0])
    return out


def main():
    pk = parse(load_index())
    if ROOT_PKG not in pk:
        sys.exit(f"ไม่พบ {ROOT_PKG} ในคลัง ลองดูชื่อแพ็กเกจ qemu ล่าสุดแล้วแก้ ROOT_PKG")
    todo, seen = [ROOT_PKG], set()
    while todo:
        n = todo.pop()
        if n in seen or n in SKIP:
            continue
        if n not in pk:
            print("  ข้าม (ไม่อยู่ในคลัง):", n); continue
        seen.add(n); todo += deps(pk[n])
    print("แพ็กเกจ:", sorted(seen))

    shutil.rmtree(W, ignore_errors=True); os.makedirs(W + "/debs"); os.makedirs(W + "/root")
    for n in sorted(seen):
        deb = f"{W}/debs/{n}.deb"
        open(deb, "wb").write(get(f"{REPO}/{pk[n]['Filename']}"))
        d = f"{W}/root/{n}"; os.makedirs(d)
        members = subprocess.check_output(["ar", "t", deb], text=True).split()
        data = next(m for m in members if m.startswith("data.tar"))
        subprocess.check_call(["ar", "x", os.path.abspath(deb), data], cwd=d)
        with tarfile.open(f"{d}/{data}") as t:
            t.extractall(d)
        os.remove(f"{d}/{data}")

    # รวบรวมไลบรารี: alias(soname) -> ไฟล์จริง
    alias, binary, fw = {}, None, None
    for dp, _, fs in os.walk(W + "/root"):
        for fn in fs:
            p = os.path.join(dp, fn)
            if fn == "qemu-system-aarch64":
                binary = p
            if fn == "edk2-aarch64-code.fd":
                fw = p
            if "/usr/lib" in dp and ".so" in fn:
                alias[fn] = os.path.realpath(p)
    if not binary:
        sys.exit("ไม่พบ qemu-system-aarch64 ในแพ็กเกจ")

    def needed(path):
        return subprocess.check_output(["patchelf", "--print-needed", path], text=True).split()

    # เลือกเฉพาะไลบรารีที่ถูกใช้จริง
    reach, queue = {}, [binary]
    while queue:
        cur = queue.pop()
        for nd in needed(cur):
            if nd in SYSTEM:
                continue
            real = alias.get(nd)
            if not real:
                print("  คำเตือน: หาไลบรารีไม่เจอ:", nd); continue
            if real not in reach:
                reach[real] = "lib_" + re.sub(r"\W", "_", os.path.basename(real)) + ".so"
                queue.append(real)
    amap = {a: reach[r] for a, r in alias.items() if r in reach}

    shutil.rmtree("out", ignore_errors=True); os.makedirs(OUT)
    targets = [(binary, "libqemu.so")] + [(r, n) for r, n in reach.items()]
    for src, name in targets:
        dst = f"{OUT}/{name}"
        shutil.copy(src, dst); os.chmod(dst, 0o755)
        for nd in needed(dst):
            if nd in amap:
                subprocess.check_call(["patchelf", "--replace-needed", nd, amap[nd], dst])
        subprocess.call(["patchelf", "--remove-rpath", dst])
    if fw:
        shutil.copy(fw, "out/edk2-aarch64-code.fd")
    else:
        print("ไม่พบ edk2-aarch64-code.fd ในแพ็กเกจ ต้องหามาเอง")
    print(f"\nเสร็จ: {len(targets)} ไฟล์ใน {OUT}\nก๊อปโฟลเดอร์ out/jniLibs/arm64-v8a/* ไปที่ app/src/main/jniLibs/arm64-v8a/")


main()
