# Ruffle Android — SWF 播放器

基于 [Ruffle](https://ruffle.rs/) 0.7.1 的 Android 原生 SWF（Flash）播放器。一个 App 即可运行**任意 SWF 游戏/动画**，不限数量、不限来源。

## 特性

- **任意 SWF**：扫描文件夹或单个选择，自动识别 `.swf` / `.spl`，无白名单、无游戏库限制
- **全 Android 版本**：minSdk 23（Android 6.0），armeabi-v7a / arm64-v8a / universal 三套 APK
- **声音**：MP3 / AAC / Nellymoser / ADPCM；Android 8.0+ 用 AAudio，6.0–7.1 回退 OpenSL ES
- **手柄**：可自定义按键覆盖层（位置/大小/透明度），支持键位改绑
- **按键**：键盘面板 + 手柄覆盖层，按键事件直接送达 Flash 播放器
- **性能**：wgpu (GLES) 渲染，软件视频后端（h263 / vp6 / screen video）

## 下载

| 文件 | 适用 |
|---|---|
| `ruffle-arm64-v8a.apk` | 64 位安卓机（推荐，绝大多数现代手机/平板） |
| `ruffle-shield.apk` | armeabi-v7a（NVIDIA SHIELD K1 等 32 位 ARM 机） |
| `ruffle-universal.apk` | 三 ABI 合体，一个装所有机 |

## 使用

1. 装 APK（签名：CN=Era Ruffle，可互相覆盖升级）
2. 打开 App → 主页选「选择文件夹」定位到放 `.swf` 的目录（或「扫描内置目录」）
3. 列表点游戏即播；右下角工具条可开键盘/手柄/编辑/菜单

## 本地 SWF 调取机制

App 把任意目录的 SWF 通过 SAF 树或文件 URI 交给内置 Ruffle 引擎，见 `SWF_LOAD_GUIDE.md`。

## 构建

见 `BUILD.md`。核心是 Ruffle 源码 + Android fork 通过 Cargo path 依赖联编。

## 许可

Ruffle 为 MIT / Apache-2.0 双许可（见 `LICENSE-MIT` / `LICENSE-APACHE`）。本 fork 在 Ruffle 基础上加了手柄覆盖层、Android 音频后端与 SAF 库管理。

## 免责

本 App 为通用 SWF 播放器，不含任何游戏数据。请自行确保你运行的 SWF 内容合法。