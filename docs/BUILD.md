# 从源码构建

本分支以 Windows x64 为构建主机，Android 输出为 arm64-v8a 测试版。请把源码、SDK 和缓存放在无空格的 ASCII 路径，例如 `D:\src\suilian`。脚本不安装 SDK、不读取已有随连的配置，也不操作连接中的手机。

## 工具版本

| 工具 | 版本 / 用途 |
|---|---|
| Git、Python | Git 2.x；Python 3.10+，Windows 打包另需 `brotli==1.1.0` |
| Go | 1.26.x，电脑端与 Android 网络核心 |
| Windows .NET Framework | 4.x 自带 `csc.exe`，用于 C# 管理器 |
| JDK | 17 |
| Android SDK | platform 36、build-tools 35.0.0、platform-tools |
| Android NDK | 28.2.13676358 |
| Gradle / AGP / Kotlin | wrapper 8.11.1 / 8.10.1 / 2.1.21 |
| Flutter | 3.24.5，engine `a18df97ca57a249df5d8d68cd0820600223ce262` |
| Flutter Rust Bridge generator | 1.80.1 |
| Rust / LLVM | FRB 生成器需要 Rust/Cargo 和带头文件的 libclang / LLVM；本轮使用 LLVM 18 |

建议在虚拟环境中运行 `python -m pip install brotli==1.1.0`。FRB 可按其[官方源码](https://github.com/fzyzcjy/flutter_rust_bridge/tree/v1.80.1)安装指定版本，或使用官方该版本发布的生成器。Flutter 及 Android SDK 从各自官方渠道取得。

## 环境变量

以下都是示例目录，按自己的安装位置填写。路径内不放凭据。

```powershell
$env:JAVA_HOME = 'D:\sdk\jdk-17'
$env:ANDROID_HOME = 'D:\sdk\android'
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\28.2.13676358"
$env:FLUTTER_ROOT = 'D:\sdk\flutter-3.24.5'
$env:FRB_CODEGEN = 'D:\sdk\frb\flutter_rust_bridge_codegen.exe'
$env:LLVM_HOME = 'D:\sdk\llvm'
$env:SUILIAN_WORK = 'D:\suilian-build'
$env:GRADLE_USER_HOME = 'D:\cache\gradle'
$env:PUB_CACHE = 'D:\cache\pub'
$env:GOPATH = 'D:\cache\gopath'
$env:GOMODCACHE = 'D:\cache\go-mod'
$env:GOCACHE = 'D:\cache\go-build'
$env:CARGO_HOME = 'D:\cache\cargo'
$env:RUSTUP_HOME = 'D:\sdk\rustup'
$env:PATH = "$env:JAVA_HOME\bin;D:\sdk\go\bin;$env:FLUTTER_ROOT\bin;$env:CARGO_HOME\bin;$env:PATH"
```

`SUILIAN_WORK` 默认是仓库下的 `.build/`。保留自己已有的 Rust/Go 缓存配置，不必照抄示例中的每个目录。

## 构建顺序

在仓库根目录运行：

```powershell
python tools/dependencies.py all
python tools/build.py native
python tools/build.py remote
python tools/build.py android
python tools/build.py windows
python tools/build.py package
```

每一步失败会停止并报错。已存在但与标记不符的上游源码不会被覆盖；请保留自己的修改并另建干净 checkout。初次下载需要能访问 GitHub、Go modules、pub.dev、Maven、Google Android/Flutter 仓库。

- `dependencies.py` 校验 `dependencies.lock.json` 的 SHA-256，取得官方原生组件、规则和 Mihomo 源码；RustDesk 按完整 commit checkout 并应用补丁。
- `native` 从仓库 Go/C 桥接代码和 Mihomo 补丁编译 `libclash.so`，包含只直连、切网快照和队列排空修复。
- `remote` 生成 FRB 绑定，编译有改动的 Flutter 界面；不会用官方未修改 `libapp.so` 替代本项目界面。符号检查不能代替真机 ABI 验收。
- `android` 执行单元测试并构建 APK；Gradle 插件路径写入忽略的 `android/plugins.local.json`，不再依赖开发者电脑绝对路径。
- `windows` 测试并构建 Go 后端与 C# 管理器。
- `package` 在 `releases/windows-0.3.17-test/` 组装自己的运行目录，包含固定版本 EasyTier、RustDesk、Sunshine，以及自己的 Android SDK 中的 ADB 和声明。

输出 APK：`releases/SuiLian-0.3.17-test-arm64.apk`。签名由当前开发环境的 debug keystore 产生；仓库不提供原作者签名，不保证能覆盖原有安装。不要为覆盖安装而导入他人的私钥。

本仓库不托管第三方二进制，构建时从官方带校验的地址下载。未修改的 RustDesk / Moonlight / FlClash 辅助原生库使用固定官方产物；如需全部原生组件都自行编译，按 [THIRD_PARTY.md](../THIRD_PARTY.md) 中对应版本的源码与上游构建说明替换，再做 ABI、16 KB 页对齐及真机验收。

`native-artifacts.lock.json`、`integrated-ui.lock.json`、`direct-core.lock.json` 是原测试版本的技术哈希基线，不是要求所有 SDK 环境下的自行构建产物逐字节一致。`tools/verify_artifacts.py --apk PATH --baseline` 可核对这套基线；自行构建用不带 `--baseline` 的结构和完整性检查。

## 源码检查

```powershell
python tools/check_public_tree.py
python -m unittest discover -s tests -p 'test_*.py'
cd companion
go test ./...
go vet ./...
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-desktop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tests/UsbPreflightTests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tests/UsbPairFieldsTests.ps1
```

桌面测试使用独立模拟窗口和测试子进程，不停止正在运行的随连。源码 CI 不连接真实手机，也不替代无线切换、远控、编码帧率或长时间省电测试。发布检查记录见 [PUBLICATION.md](PUBLICATION.md)。

