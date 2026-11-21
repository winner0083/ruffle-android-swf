# 构建

本仓库基于 Ruffle 0.7.1（Apache-2.0/MIT）fork。Android 端为 `ruffle-android-main/`，Ruffle 源码在 `Ruffle-master/`（两者通过 Cargo path 依赖联编）。

## 前置

- JDK（`JAVA_HOME`）
- Rust + `rustup target add armv7-linux-androideabi aarch64-linux-android`
- Android NDK 29（`ANDROID_NDK_HOME` 或 `D:\era_sdk\ndk\...`）
- Cargo rsproxy 镜像（crates.io 索引直连超时）

## 构建

```bat
set "JAVA_HOME=D:\era_jdk"
set "PATH=D:\era_jdk\bin;D:\cargo\bin;D:\mingw64\bin;D:\era_sdk\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin;%%PATH%%"
set "RUSTUP_HOME=D:\rust"
set "CARGO_HOME=D:\cargo"
set "GRADLE_USER_HOME=D:\gradle-home"
set "SIGNING_STORE_PASSWORD=…"
set "SIGNING_KEY_ALIAS=era"
set "SIGNING_KEY_PASSWORD=…"
cd /d ruffle-android-main
gradlew.bat assembleRelease --console=plain
```

> 注意 `set "VAR=VALUE"` 必须带引号：不带引号会吃掉尾部空格。

产物在 `ruffle-android-main/app/build/outputs/apk/release/`。

## v7a 单独 cargo check（调试用）

```bash
export CC_armv7_linux_androideabi=armv7a-linux-androideabi23-clang
export CXX_armv7_linux_androideabi=armv7a-linux-androideabi23-clang++
export AR_armv7_linux_androideabi=llvm-ar
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER=armv7a-linux-androideabi23-clang
cargo check --target armv7-linux-androideabi --offline
```

## 音频后端

- `aaudio` feature 关闭时走 `src/audio_opensl.rs`（OpenSL ES，API 9+）
- `aaudio` feature 开启时走 `src/audio.rs`（AAudio，API 26+）
- minSdk 23 的构建用 OpenSL，保证 Android 6-7 有声音

## 真机验收

```bash
adb install -r -d app/build/outputs/apk/release/app-armeabi-v7a-release.apk
adb shell am start -a android.intent.action.VIEW -d file:///storage/emulated/0/Download/swf/<file>.swf -t application/x-shockwave-flash -n rs.ruffle/.PlayerActivity
```
