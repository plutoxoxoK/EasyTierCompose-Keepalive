# 原生依赖归属采集与边界

2026-11-07，固定核心 `81487c363a56779e745eae927e130a813535c71e`。

从 Cargo.lock 中的 easytier-android-jni 出发保守遍历依赖图，得到 688 包：675 个 registry 包、8 个 Git 包、5 个工作区包。这个图含其他目标和特性，不能当作最终 ARM64 APK 的精确 SBOM。没有安装 Rust 或重编 EasyTier。

所有 registry 源包均对应锁文件 SHA-256；Git 源包按固定 commit 下载并保存实际 archive 摘要。673 项同时具有许可证声明和捕获的 notice；15 项初始采集异常逐项给出说明：8 项保留了校验源包中的原始版权/许可头，2 项工作区包缺少单包许可证声明但仓库 LGPL-3.0 文件可查，5 项只找到许可证元数据。1284 份 notice、声明和来源摘要一起保存，不将上述差别隐藏为“全部通过”。

| 只有元数据的包 | 声明 | 已查来源 |
|---|---|---|
| encoding-utils 0.1.0 | MIT | 校验源包及其记录的固定 VCS 提交 |
| rustls-platform-verifier-android 0.1.1 | MIT OR Apache-2.0 | 校验源包；包中没有固定 VCS 提交信息 |
| seahash 4.1.0 | MIT | 校验源包；记录的来源为 GitLab，未从该固定提交补取正文 |
| tun-easytier 1.1.1 | WTFPL | 固定 Git 源码 |
| valuable 0.1.0 | MIT | 校验源包；包中没有固定 VCS 提交信息 |

这些包没有被改成“已找到专属许可正文”。另附 SPDX 标准文本供阅读；MIT 模板里的占位符不代表本项目补造了上游版权人。原始声明、上游作者字段、源包版权头、固定源码和补充来源分开记录。由于依赖图是保守超集，表中出现一个包也不能证明它实际编入了当前 APK。

机器记录见 [NATIVE-DEPENDENCY-LICENSES.json](NATIVE-DEPENDENCY-LICENSES.json) 与 [NATIVE-LICENSE-REVIEW.json](NATIVE-LICENSE-REVIEW.json)；notice 位于 licenses/native，标准文本位于 licenses/native-standard。字段含义遵循 [Cargo manifest 的 license / license-file 文档](https://doc.rust-lang.org/cargo/reference/manifest.html#the-license-and-license-file-fields)，标准文本来源和 SHA-256 见 review JSON。

复核流程（只采集，不执行上游代码）：先运行 tools/export_native_notices.py，再运行 tools/supplement_native_notices.py，最后运行 tools/review_native_notices.py。下载保留在忽略目录 .local/native-license-cache，已验证源包可离线复用。元数据、notice 与上述异常说明不等于已证明原生逐位可重现或全部分发义务已穷尽。
