# 电脑端开发与运行

这是本地自用测试程序。`go test ./...` 覆盖 DPAPI、访问认证、规则接口以及真实 TCP/UDP 回环代理，不代表手机链路验收。

日常运行完整交付目录的 `WangChuanManager.exe`。首次创建 EasyTier 虚拟网卡需要 Windows 管理员确认。程序仅将代理和规则接口绑定到 `10.144.77.1`，本机管理页绑定 `127.0.0.1:17881`。EasyTier 网络凭证通过子进程环境传递，配对保存在当前 Windows 用户的 DPAPI 保护文件中。

本版本使用 EasyTier 社区公共发现入口。端到端网络密钥保护组网流量，但入口可用性和跨运营商连接速度仍依赖实测；不回退业务中继。管理工具自动设置 RustDesk 直接访问及配对密码；Sunshine 作为可选功能，已加入受限手机接口自动准备账户和确认 PIN，实际视频和输入仍需真机验收。

规则包在 `rules/`，手机的“从电脑同步规则集”通过私有加密组网和令牌认证读取。它同步的是发布到此目录的规则包，不会自动登录或修改路由器。准备公开规则：在源码根目录执行 `python tools/dependencies.py rules` 后执行 `python tools/build.py package`。规则来源和哈希由 `dependencies.lock.json` 固定。不要在电脑端运行时覆盖整个程序目录。

开发与运行目录请区分：源码在 `companion/`，完整运行包在 `releases/windows-0.3.17-test/`，单独复制 EXE 不能工作。`tools/build-manager.ps1` 用系统 .NET Framework 编译管理器，完整构建见根目录 `docs/BUILD.md`。
