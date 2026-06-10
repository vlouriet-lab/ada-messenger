#!/bin/bash
export ANDROID_NDK_HOME="C:/Android/Sdk/ndk/27.2.12479018"
export PATH="/usr/bin:/c/Users/pc/.cargo/bin:$PATH"
cd /c/Users/pc/source/repos/ADA/ada-core
cargo ndk -t arm64-v8a --platform 26 -o /c/Users/pc/source/repos/ADA/android-app/app/src/main/jniLibs build --no-default-features --features mobile > /c/Users/pc/source/repos/ADA/cargo_ndk_arm64_exportSnapshot.txt 2>&1
echo "Exit: $?"
tail -20 /c/Users/pc/source/repos/ADA/cargo_ndk_arm64_exportSnapshot.txt
