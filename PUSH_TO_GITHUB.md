# 发布到 GitHub

本目录 `D:\ruffle-android\github_swf_player\` 是一个干净的、可直接发布的 SWF 播放器源码仓库（58 MB，不含任何游戏数据/密钥）。

## 一、装 git（机器上没有）

```bat
winget install --id Git.Git -e --source winget
winget install --id GitHub.cli -e --source winget
```

装完**新开一个终端**（刷新 PATH）。

## 二、初始化并提交

```bat
cd /d D:\ruffle-android\github_swf_player
git init
git add -A
git status          # 确认没有 .jks / .swf / local.properties
git commit -m "Ruffle-based Android SWF player (AVM1/AVM2, OpenSL ES audio, gamepad overlay)"
```

## 三、登录并推送

```bat
gh auth login
gh repo create ruffle-android-swf --public --source=. --push
```

或者手动：

```bat
git branch -M main
git remote add origin https://github.com/<你的账号>/ruffle-android-swf.git
git push -u origin main
```

## 四、传 APK（建议用 Releases，不要直接进 git）

```bat
gh release create v0.1 dist/ruffle-arm64-v8a.apk dist/ruffle-shield.apk dist/ruffle-universal.apk
```

（APK 从 `D:\ruffle-android\dist\` 取。）

## 五、发布前自查

```bat
git ls-files | findstr /i ".jks .keystore .swf .spl local.properties password token"
```
应无输出。

## 备注

- 许可证：Ruffle 为 MIT / Apache-2.0 双许可（见 `LICENSE.md`）。fork 的改动在 `ruffle-android-main/src/`（OpenSL ES 音频、手柄覆盖层、SAF 库）。
- 不要提交任何游戏 SWF 或签名密钥。