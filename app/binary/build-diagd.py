#!/usr/bin/env python3
"""Rebuild the packaged arm64 Android helper from its checked-in C sources.

Example: python app/binary/build-diagd.py --ndk /path/to/android-ndk-r29
Run synthetic host decoder/capture tests separately: python app/binary/tests/test_capture.py
"""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess


def main():
    source = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--output", type=Path, default=source.parent / "app/src/main/assets/qbm-diagd")
    args = parser.parse_args()
    if args.ndk is None:
        parser.error("Supply --ndk or ANDROID_NDK_HOME; no tools are downloaded automatically.")
    host = {"Windows": "windows-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system(), "linux-x86_64")
    suffix = ".exe" if platform.system() == "Windows" else ""
    tools = args.ndk.resolve() / "toolchains/llvm/prebuilt" / host / "bin"
    clang, strip = tools / ("clang" + suffix), tools / ("llvm-strip" + suffix)
    if not clang.is_file() or not strip.is_file():
        parser.error(f"NDK host toolchain is missing: {tools}")
    build = source.parent / "build/native"
    build.mkdir(parents=True, exist_ok=True)
    temporary = build / "qbm-diagd"
    subprocess.run([str(clang), "--target=aarch64-linux-android29", "-O2", "-D_GNU_SOURCE",
                    "-DHAVE_TM_GMTOFF", "-DHAVE_TIMEGM", "-fPIE", "-pie",
                    "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections", "-Wl,-z,max-page-size=16384",
                    str(source / "qbm-diagd.c"), str(source / "rrc_csi.c"), str(source / "rrc_nr_gen.c"),
                    "-ldl", "-lm", "-o", str(temporary)], check=True)
    subprocess.run([str(strip), "--strip-unneeded", str(temporary)], check=True)
    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(temporary, destination)
    data = destination.read_bytes()
    print(f"Built {destination} ({len(data)} bytes)")
    print(f"SHA-256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
