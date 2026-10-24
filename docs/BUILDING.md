# 构建与开发

## 已验证环境

- Windows，PowerShell 7，JDK 21。
- Android SDK Platform 36、Build Tools 36.0.0；安装到手机时需要 Platform Tools / ADB。
- 可选 Python 3.13 和 `requirements-dev.txt`，用于合成 PNG 样本验证。

构建链是 `aapt2 → javac → jar → d8 → zipalign → apksigner`。Android Studio 可用于安装 SDK，但不是构建前提。当前没有 Gradle 工程；其他操作系统尚未验证。

0.1.4 起最低运行版本为 Android 15 / API 35，资源清单和 DEX 编译使用相同的最低要求；目标及编译 SDK 仍为 API 36。构建时继续安装 Platform 36，无需为了最低运行版本另换编译平台。

## 标准 SDK 安装布局

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'C:\Android\Sdk'
./build.ps1 -Test -Release
```

将路径改为实际位置。脚本读取 `JAVA_HOME`，未设置时从 PATH 查找 Java 工具；SDK 读取 `ANDROID_HOME`，其次为 `ANDROID_SDK_ROOT`。平台固定使用 API 36，默认构建工具目录为 `build-tools/36.0.0`。

## 独立解压或自定义工具目录

```powershell
./build.ps1 -Test -Release `
  -JavaHome 'C:\Tools\jdk-21' `
  -AndroidJar 'C:\Android\android-36\android.jar' `
  -BuildTools 'C:\Android\build-tools\36.0.0' `
  -WorkRoot 'C:\Temp'
```

`-BuildTools` 指向包含 `aapt2`、`zipalign` 和 `lib` 的目录。Windows 原生资源工具可能拒绝中文路径，脚本将输入复制到每次构建独立的 ASCII 临时目录；若系统临时路径包含非 ASCII 字符，请显式设置 `-WorkRoot`。每次构建结束只清理本次生成、且父目录与名称均经过核对的暂存目录。

## 参数与产物

| 参数 | 用途 |
| --- | --- |
| `-Test` | 构建前运行 7 项匹配测试 |
| `-Release` | 将打包后的 `debuggable` 设为 false |
| `-Install` | 用 ADB 覆盖安装到一台已授权的 USB 手机 |
| `-AdbPath` | 指定独立 ADB 可执行文件 |
| `-SdkRoot / -JavaHome` | 覆盖 SDK / JDK 环境变量 |
| `-AndroidJar / -BuildTools` | 使用非标准 SDK 布局 |
| `-WorkRoot` | ASCII 临时工作目录 |
| `-KeystorePath / -KeyAlias` | 指定已有签名文件与别名 |

调试产物为 `build/LongCapture-debug.apk`；非调试产物为 `build/LongCapture.apk`。`-Release` 表示关闭调试能力，不表示已建立完整的生产签名、应用商店或多机兼容流程。

首次构建默认生成被 Git 忽略的 `local-debug.keystore`，开发口令为 `android`。自定义签名口令从 `LONGPAGE_STORE_PASSWORD` 和 `LONGPAGE_KEY_PASSWORD` 环境变量读取，不写入仓库。已有自定义证书时同时传入路径和别名；密码使用进程环境或自己的凭据工具提供。

保留自己的签名私钥，后续覆盖安装需要匹配签名。GitHub Actions 使用运行时生成的临时开发签名，不能用于替换本项目 Releases 的签名。CI 不持有发布者私钥，也不会自动发布安装包。

## 合成样本与图标预览

```powershell
python -m pip install -r requirements-dev.txt
python tests/verify_fixture.py
python tools/preview_icons.py
```

样本脚本默认读取 `tests/fixtures`，报告写入被忽略的 `build/fixture-validation.json`。验证另一组同结构样本时，可传入 `--directory`、`--prefix` 和 `--output`。

图标脚本直接读取 Android 矢量路径，生成 `build/branding/icon-preview.html` 与 SVG；用浏览器打开 HTML 查看。README 的 PNG 预览来自该页面，未使用私人手机桌面作为展示材料。

开发调试版可长按主界面底部版本信息，打开内置编号页。任何实际自动采集都应由设备使用者明确开始；不要把日志、原始会话或私人截图作为公开问题附件。
