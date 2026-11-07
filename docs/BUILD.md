# 可复核构建

工具链：JDK 21、Gradle 9.7.1（wrapper 带官方 SHA-256）、AGP 9.4.1、Kotlin 2.2.10、Android SDK platform 37.0、Build Tools 37.0.0。minSdk 24，targetSdk 36。此 NO_TUN-only 候选不编译 Root JNI，因此不需要 NDK/CMake/Rust；保留的上游 native 源码仅用于归属、对应源码与以后单独重建。

初始检查发现原有 Gradle 只有 9.2.1，Build Tools 只有 36.0.0，存在 platform 37.0/JDK 21，但无 NDK。首次候选采用 `.local` 的隔离 SDK 与 Gradle home，平台文件从已有 SDK 复制，工具包均核验官方摘要。用户随后明确授权持久安装 Gradle/NDK 供未来开发，已另行完成 Gradle 9.7.1、NDK 30.0.16248370 与 Build Tools 37.0.0 的持久部署，并通过 ARM64 C 编译验证；此候选仍不依赖 NDK。未运行 adb 安装或控制手机。

| 下载 | 校验值 |
|---|---|
| Gradle 9.7.1 bin，151433392 字节 | SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` |
| Build Tools 37 Windows，61077164 字节 | 官方 repository2-3.xml SHA-1 `e9c97e26b5b5678002e9d3fed632c841ae62d99f`；SHA-256 另记实际构建证据 |

本次 Build Tools 包另算 SHA-256 为 `68075aa319ed8a01cf1a565ed1e61a3c1a801dd49191c35851248dc293c33b1a`。官方大文件整包请求曾停滞，改为范围下载后仍以整文件官方摘要验收，不关闭 TLS 校验。

在其他电脑，先自行准备上述 JDK/SDK 包，设置 JAVA_HOME，并在未跟踪的 `local.properties` 写 `sdk.dir=...`。SDK/JDK 自动下载已关闭；Maven/Gradle 正常依赖仍需首次联网。不要把代理凭据或签名信息提交到 gradle.properties。

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 多 ABI（不是本手机默认候选）：
.\gradlew.bat :app:assembleDebug -PcandidateAbis=arm64-v8a,armeabi-v7a,x86_64
```

Linux/macOS 用 `./gradlew`。默认 ARM64 输出 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。该 APK 采用标准本机 debug key；不兼容原应用签名，也不会因包名不同而覆盖它。不要把 debug key 当作未来稳定发布身份。`assembleRelease` 没有明确签名配置时产生 unsigned 包，不静默使用调试身份。

本候选测试使用 Robolectric Android 13 模型和可替换的 JNI/前台服务边界；测试不会连接生产网络或读取手机配置。首次 Robolectric 测试还会解析其 Android 模型依赖，不需要启动模拟器。

对应原生源固定到 `EasyTier-Core` 子模块。源码打包命令在提交候选后运行：

```powershell
python tools/package_source.py
```

脚本从已提交 Git 对象导出，忽略本机 CRLF 转换设置，输出应用＋固定原生构建源码压缩包及摘要，不包含 `.git`、缓存、日志、用户配置、签名材料或父工作区。固定核心中两个上游 GUI 签名文件被明确排除，具体路径与理由写入 SOURCE-MANIFEST.json；JNI 构建脚本与 Rust `build/*.rs` 均保留。若工作树有未提交修改则拒绝打包；失败时不会覆盖已有完整包。未来重编原生库是另一项显式工具链工作：参见固定核心源码中的构建脚本和上游应用 update-easytier-jni.ps1，不因工具链已安装而自动扩展本候选范围。

2026-11-07 已逐项核验持久工具安装：NDK 8627 文件、Gradle 712 文件、Build Tools 169 文件的大小/CRC 与通过 SHA-256 验证的安装 ZIP 一致；Build Tools 另有 SDK 包管理元数据 package.xml。独立离线 AGP 9.4.1 工程通过 `sdkComponents.ndkDirectory` 发现固定 NDK 30.0.16248370。重复暂存与 Gradle/NDK 下载分块已清，完整 ZIP 和环境回退记录保留在本机。没有另行验证 SDKManager/Android Studio UI，也没有重编 EasyTier Rust 核心。
