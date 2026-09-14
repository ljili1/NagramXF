"""Assemble requirements-{common,abi}.imy from pip-downloaded wheels.

usage: freeze_requirements.py <out-dir> <abi>=<wheel-dir> [<abi>=<wheel-dir> ...]

The pinned wheels (requirements.txt) provide every byte: .py compile to .pyc in
requirements-common.imy, non-code files (dist-info, data, headers) go to common, and the
android-ABI .so go to requirements-<abi>.imy. INSTALLER/REQUESTED markers (pip install-time
artifacts not present in wheels) are regenerated to match a standard pip install.
"""
import marshal, os, re, sys, zipfile, importlib.util

MAGIC = importlib.util.MAGIC_NUMBER
DIRECT = ["beautifulsoup4", "debugpy", "lxml", "packaging", "pillow", "pyyaml", "requests"]
ABI_TAG = {"arm64-v8a": "arm64_v8a", "armeabi-v7a": "armeabi_v7a"}

def stem_so(n):
    d, b = os.path.split(n)
    return f"{d}/{re.sub(r'.cpython-3..*.so$', '.so', b)}" if d else b

def norm(n):
    m = re.match(r"[^/]+\.data/(?:platlib|purelib|data|scripts)/(.+)", n)
    return m.group(1) if m else n

def compile_pyc(src_bytes, name):
    code = compile(src_bytes.decode("utf-8"), os.path.basename(name), "exec")
    hdr = MAGIC + b"\0" * 8 + len(src_bytes).to_bytes(4, "little")
    return hdr + marshal.dumps(code)

def main():
    out_dir = sys.argv[1]
    abi_dirs = dict(a.split("=", 1) for a in sys.argv[2:])
    if not abi_dirs:
        raise SystemExit("need at least one abi=<wheel-dir>")
    primary = next(iter(abi_dirs))

    common = {}
    per_abi = {abi: {} for abi in abi_dirs}
    distinfos = set()

    def scan(wdir, abi_key):
        is_primary = abi_key == primary
        for wf in sorted(os.listdir(wdir)):
            if not wf.endswith(".whl"):
                continue
            support = wf.startswith("chaquopy_")
            z = zipfile.ZipFile(os.path.join(wdir, wf))
            for i in z.infolist():
                if i.is_dir():
                    continue
                nm = norm(i.filename.replace("\\", "/"))
                data = z.read(i.filename)
                if ".dist-info/" in nm:
                    distinfos.add(nm.split("/")[0])
                    if nm.endswith(("/RECORD", "/WHEEL", "/INSTALLER", "/REQUESTED")):
                        continue
                    if is_primary:
                        common[nm] = data
                    continue
                if support:
                    if nm.startswith("chaquopy/lib/") and nm.endswith(".so"):
                        per_abi[abi_key][nm] = data
                    continue
                if nm.endswith(".py"):
                    common[nm[:-3] + ".pyc"] = compile_pyc(data, nm)
                elif nm.endswith(".so") and "android_" in wf:
                    per_abi[abi_key][stem_so(nm)] = data
                elif is_primary and not nm.endswith(".gitignore"):
                    common[nm] = data

    for abi, wdir in abi_dirs.items():
        scan(wdir, abi)

    for di in distinfos:
        common[f"{di}/INSTALLER"] = b"pip\n"
    for di in distinfos:
        if di.split("-")[0].lower() in DIRECT:
            common[f"{di}/REQUESTED"] = b""

    os.makedirs(os.path.join(out_dir, "chaquopy"), exist_ok=True)
    def write(mapping, name):
        path = os.path.join(out_dir, "chaquopy", name)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as out:
            dirs = set()
            for fn in sorted(mapping):
                parts = fn.split("/")[:-1]
                for j in range(len(parts)):
                    d = "/".join(parts[:j + 1]) + "/"
                    if d not in dirs:
                        dirs.add(d)
                        out.writestr(zipfile.ZipInfo(d), b"")
                out.writestr(fn, mapping[fn])
        print("wrote", name, len(mapping), "entries")
    write(common, "requirements-common.imy")
    for abi, mapping in per_abi.items():
        write(mapping, f"requirements-{abi}.imy")

if __name__ == "__main__":
    main()

