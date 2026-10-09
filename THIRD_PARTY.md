# 第三方组件与来源

随连集成项目使用 AGPL-3.0；以下组件保留上游许可证、作者版权和声明。许可证文本位于 `licenses/`，完整版本和下载校验位于 `dependencies.lock.json`。这里是工程来源清单，不把第三方成果表述为随连原创。

| 组件 | 固定版本 / 源码 | 许可证 | 使用方式 |
|---|---|---|---|
| RustDesk | [源码](https://github.com/rustdesk/rustdesk/tree/fada664df7a294d1d1a9ca3e7cd3637069122f17)，1.5.0 原生包 | AGPL-3.0 | Android 适配、远控 Flutter 补丁、Windows 官方组件 |
| FlClash | [源码](https://github.com/chen08209/FlClash/tree/4b59eca853778d4e7be3de26589252889c899bc5)，0.8.99 | GPL-3.0 | `native/core/` Go/C 桥接、Android 核心宿主、官方辅助原生库 |
| Mihomo fork | [源码](https://github.com/chen08209/Clash.Meta/tree/8597778c7df410ebe88c57e59eaab32d203156df) | GPL-3.0 | 应用 `patches/mihomo.patch` 后编译；直接模式、切网与队列修复 |
| Moonlight Android | [源码](https://github.com/moonlight-stream/moonlight-android/tree/b48494cb96bff23d8886c4775cc4f39a1075495d)，12.2 | GPL-3.0 | `android/moonlight/` 源码与固定官方 native 库 |
| Sunshine | [源码与发布](https://github.com/LizardByte/Sunshine/releases/tag/v2026.914.233613) | GPL-3.0 | Windows 高帧率主机，官方未修改组件 |
| EasyTier | [源码与发布](https://github.com/EasyTier/EasyTier/releases/tag/v2.7.0) | LGPL-3.0 | Windows 2.7.0，Android Go 库版本另见 `native/core/go.mod` |
| Flutter | [3.24.5](https://github.com/flutter/flutter/tree/3.24.5) | BSD-3-Clause | 引擎与 Flutter UI；依赖声明随 Flutter 资产的 `NOTICES.Z` 生成 |
| Flutter Rust Bridge | [1.80.1](https://github.com/fzyzcjy/flutter_rust_bridge/tree/v1.80.1) | MIT | 绑定生成器 |
| rustls-platform-verifier Android | [0.1.1 crate](https://crates.io/crates/rustls-platform-verifier-android/0.1.1) | MIT / Apache-2.0 | 从官方 crate 提取 Android AAR |
| MetaCubeX meta-rules-dat | [固定规则版本](https://github.com/MetaCubeX/meta-rules-dat/tree/32bbb6c45c63a491c571f98cd9d863d7fd6771dc) | 见上游许可证及数据源声明 | 构建时下载通用规则，不包含个人路由器配置 |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle) | Apache-2.0 | wrapper 文件及构建下载入口 |

Android Maven、Flutter pub、Go modules 等传递依赖的精确版本分别记录在 Gradle 构建文件、RustDesk 补丁中的 `pubspec.lock` 及 `go.mod/go.sum`。上游 Git 子模块也按固定提交恢复；重新分发时需保留对应声明。

## 源码完整性与再分发

本仓库包含随连 Android / Windows 自有实现、随连实际使用的 Kotlin/Java 适配源码、完整 Go/C 桥接和改动补丁。未修改的第三方大型源码以固定上游 commit / 发布链接提供，`tools/dependencies.py` 恢复有改动组件的固定源码并应用补丁。

当前构建使用部分上游官方原生二进制，未将它们上传到仓库。自行发布包含这些二进制的安装包时，应提供满足上游许可证的对应源码、补丁、构建材料及必要声明，不能仅假定一个 URL 满足所有分发形式。GPL/AGPL 组合规则可查阅 [GNU GPL v3 第 13 节](https://www.gnu.org/licenses/gpl-3.0.html#section13)和 [GNU FAQ](https://www.gnu.org/licenses/gpl-faq.html)。

