#!/usr/bin/env python3
import glob
import os
import struct
import sys


ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
# Libs the Makefile always packages; MISSING is an error for these. Any other
# *.so found in an ABI dir is validated too (ELF sanity + alignment).
REQUIRED_LIBS = ["libaw_server.so", "libaw_sync.so"]

# Google Play requires 16 KB page-size support for 64-bit native libs
# (enforced for updates since Nov 2025). NDK r28+ links with 16 KB
# max-page-size by default; this guards against regressing to an older NDK.
PAGE_ALIGNED_ABIS = {"arm64-v8a", "x86_64"}
REQUIRED_LOAD_ALIGN = 0x4000


def min_load_align(f, path, fsize, header, e_class):
    """Return (ok, min p_align across PT_LOAD headers or None if none)."""
    if e_class == 1:  # 32-bit
        e_phoff = struct.unpack("<I", header[28:32])[0]
        e_phentsize, e_phnum = struct.unpack("<HH", header[42:46])
        min_entsize = 32
    else:  # 64-bit
        e_phoff = struct.unpack("<Q", header[32:40])[0]
        e_phentsize, e_phnum = struct.unpack("<HH", header[54:58])
        min_entsize = 56

    if e_phentsize < min_entsize or e_phoff + e_phnum * e_phentsize > fsize:
        print(
            f"CORRUPT  {path}: program headers out of bounds "
            f"(e_phoff=0x{e_phoff:x}, e_phentsize={e_phentsize}, "
            f"e_phnum={e_phnum}, size={fsize})",
            file=sys.stderr,
        )
        return False, None

    align = None
    for i in range(e_phnum):
        f.seek(e_phoff + i * e_phentsize)
        phdr = f.read(e_phentsize)
        if len(phdr) < e_phentsize:
            print(
                f"CORRUPT  {path}: short read of program header {i}",
                file=sys.stderr,
            )
            return False, None
        p_type = struct.unpack("<I", phdr[0:4])[0]
        if p_type != 1:  # PT_LOAD
            continue
        if e_class == 1:
            p_align = struct.unpack("<I", phdr[28:32])[0]
        else:
            p_align = struct.unpack("<Q", phdr[48:56])[0]
        align = p_align if align is None else min(align, p_align)
    return True, align


def check_lib(abi, path):
    lib_name = os.path.basename(path)
    fsize = os.path.getsize(path)
    with open(path, "rb") as f:
        header = f.read(64)

        if header[:4] != b"\x7fELF":
            print(f"NOT_ELF  {path}", file=sys.stderr)
            return False

        if len(header) < 64:
            print(
                f"CORRUPT  {path}: file too small for ELF header "
                f"({len(header)} bytes)",
                file=sys.stderr,
            )
            return False

        e_class = header[4]  # 1=32-bit, 2=64-bit
        if e_class == 1:
            e_shoff = struct.unpack("<I", header[32:36])[0]
        elif e_class == 2:
            e_shoff = struct.unpack("<Q", header[40:48])[0]
        else:
            print(f"CORRUPT  {path}: unknown ELF class {e_class}", file=sys.stderr)
            return False

        if e_shoff != 0 and e_shoff >= fsize:
            print(
                f"CORRUPT  {path}: section header past EOF "
                f"(e_shoff=0x{e_shoff:x}, size={fsize})",
                file=sys.stderr,
            )
            return False

        ok, align = min_load_align(f, path, fsize, header, e_class)
        if not ok:
            return False
        if abi in PAGE_ALIGNED_ABIS:
            if align is None or align < REQUIRED_LOAD_ALIGN:
                print(
                    f"BAD_ALIGN {path}: LOAD align "
                    f"{'none' if align is None else hex(align)} < "
                    f"{hex(REQUIRED_LOAD_ALIGN)} (16 KB pages required; "
                    f"build with NDK r28+)",
                    file=sys.stderr,
                )
                return False

    align_str = "none" if align is None else hex(align)
    print(f"OK       {abi}/{lib_name}  ({fsize:,} bytes, LOAD align {align_str})")
    return True


def check_abi(abi):
    abi_dir = os.path.join("mobile", "src", "main", "jniLibs", abi)
    ok = True

    seen = set()
    for lib_name in REQUIRED_LIBS:
        path = os.path.join(abi_dir, lib_name)
        if not os.path.exists(path):
            print(f"MISSING  {path}", file=sys.stderr)
            ok = False
            continue
        seen.add(lib_name)
        ok = check_lib(abi, path) and ok

    # Validate any additional packaged libs as well; every 64-bit .so that
    # ships in the APK/AAB is subject to the Play 16 KB requirement.
    for path in sorted(glob.glob(os.path.join(abi_dir, "*.so"))):
        if os.path.basename(path) in seen:
            continue
        ok = check_lib(abi, path) and ok

    return ok


def main():
    ok = True
    for abi in ABIS:
        ok = check_abi(abi) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
