# 候选实际检查结果

更新：2026-11-07（Asia/Hong_Kong）。本地候选交付检查完成，手机实测与公开发布待单独确认。交付入口见 [CANDIDATE-DELIVERY.md](CANDIDATE-DELIVERY.md)。

## 2026-11-06 构建与模型检查

- ARM64 debug APK 构建成功，独立包名 `cc.ptoe.easytier.compose.keepalive`，版本 `1.0.10-keepalive.1` / `1001001`。
- 91 项测试全部通过，包含 17 项新增生命周期、Android Service 模型和持久恢复测试；0 失败、0 错误。
- Lint：0 错误、44 警告。警告含上游 API/版本、保留资源和 application-context 单例静态引用提示；未将警告隐藏为通过。
- JVM 模型为 Android 13 / Robolectric 4.16.1。Windows 的 Java rename 无法覆盖已有目标，测试专用 AtomicFileReplaceShadow 模拟 Android 的替换式原子 rename；生产仍使用 Android AtomicFile。模型检查不替代真机验收。
- 已核对候选 Manifest 仅声明普通无 TUN Service，无 VPN/Root Service；ARM64 原生 EasyTier 库与固定上游摘要一致。最终分发检查见下。
- 实际 debug-runtime 依赖清单 96 项，导出 60 份内嵌归属/许可证文本；两个 libsu 元数据缺项已按精确 6.0.0 上游 LICENSE 补足来源，声明缺项为 0。

已保存候选 `dist/EasyTier-1.0.10-keepalive.1-arm64-debug.apk`：29366982 字节，SHA-256 `9384db5630776a491e70a24bf5d05c06abc09d637ddc440ccfc8ba9567e23aa1`。摘要和测试计数另存 dist/candidate-summary.json，dist 不进入 Git。当次本地构建没有执行手机安装/替换或 GitHub 建仓/推送/Release；后续使用者反馈见 DEVICE-FEEDBACK.md。

## 2026-11-07 最终分发检查

- 保存 APK 的大小与 SHA-256 不变，且与原构建输出逐字节一致。`apksigner verify --verbose --print-certs` 通过，1 个 RSA-2048 Android Debug 签名者，APK v2 签名；证书 SHA-256：`8ff570dd4e82dcec45d0883f5231351cc8a880c27afd4f059fee46e8c7be4a8b`。这是候选调试身份，尚未确定稳定公开发布签名。
- 从最终 APK 读取 Manifest：独立包名/版本正确，minSdk 24 / targetSdk 36，仅 ARM64、debuggable、禁止应用备份；唯一 Service 为 non-exported `EasyTierNoTunService`，类型 specialUse，无 VPN/Root Service。`zipalign -c -P 16 4` 通过；不将 ZIP 对齐检查扩展为全部设备兼容性保证。
- 三种 ABI 的源码树 EasyTier JNI 库均与 v1.0.10 Git 对象及 NATIVE-SHA256.json 一致；APK 内 ARM64 EasyTier 库摘要为 `fc0aed1d095d53434cb77e6ea239ccee9928c1c0eb37917b2d56f6e8d5404dcf`。另外两个 APK 原生库逐字节对应已记录依赖的 AAR：graphics-path 1.0.1 与 datastore-core-android 1.2.1；完整摘要在分发清单。
- 源包从已提交应用与固定核心 Git 对象生成，逐文件验证 Git blob、字节数和 SHA-256，保留核心中已提交的 Rust `build/*.rs`。固定上游自带的 GUI keystore 与密码 properties 两项被明确排除；它们不是 JNI 构建源码。排除清单写入 SOURCE-MANIFEST.json。源包不会包含本机签名密钥、`.local`、缓存、dist 或父运维目录。
- 最终第一方源码、176 个 APK 条目和完整源码 ZIP 全量扫描均无命中（已知敏感格式与本地私有部署标记）。最终计数、源包大小/摘要与精确提交见 dist/DELIVERY-MANIFEST.json。扫描无命中不保证覆盖所有凭据格式。
- 本轮只改打包工具和交付文档，应用/构建输入与保存候选一致；未为文档重跑生命周期测试。重新读取此前测试 XML / Lint XML，确认 91 项通过、0 失败/错误/跳过、Lint 0 错误/44 警告，不能称为新一次真机检查。

原生库未在本轮重编，源码/二进制逐位可重现尚未建立；原生依赖已完成保守锁图采集与显式缺项说明（688 包、1284 份 notice，5 项只有元数据），详见 NATIVE-LICENSE-REVIEW.md，不宣称全部分发义务已穷尽。GitHub 仍是发布草稿。使用者随后反馈手机长时间保活与锁屏成功，标准化完整矩阵和真实 LOW_MEMORY 恢复仍待验。
