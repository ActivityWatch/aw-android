#!/usr/bin/env python3
import os
import struct
import sys


ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
LIB_NAME = "libaw_server.so"


def check_lib(abi):
    path = os.path.join("mobile", "src", "main", "jniLibs", abi, LIB_NAME)
    if not os.path.exists(path):
        print(f"MISSING  {path}", file=sys.stderr)
        return False

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

    print(f"OK       {abi}/{LIB_NAME}  ({fsize:,} bytes)")
    return True


def main():
    ok = True
    for abi in ABIS:
        ok = check_lib(abi) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
