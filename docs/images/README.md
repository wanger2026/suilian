# 界面图片

| 图片 | 尺寸 | 来源与展示状态 |
|---|---|---|
| [Android 连接页](android-connection.png) | 1440 × 3200 | 0.3.x 测试版开发期间的实机截图，未连接状态；用于展示连接入口与状态检查 |
| [Windows 设备概览](windows-overview.png) | 1136 × 799 | 当前 0.3.17 电脑管理器的 `--preview` 模式，未准备连接，配对二维码未显示 |
| [Windows 设备工具](windows-tools.png) | 1136 × 799 | 当前 0.3.17 电脑管理器的 `--preview` 模式，展示远控、串流、USB 与联调入口 |

图片直接来自应用界面，没有用生成式图片重绘，也没有拼入模拟连接或流量结果。它们用于展示界面，不代表连通性、帧率或稳定性测试结果。

2026-10-09 发布前已逐张检查：不含个人 IP、设备标识、配对二维码、密码或浏览记录。PNG 仅包含图像及色彩 / 像素密度等技术信息，没有文本备注或 EXIF 信息。

更新图片时，请使用空白状态或明确标注的测试数据；不要公开真实流量历史或远控中的个人桌面。电脑界面可在构建后使用预览导出，不会连接后台服务或生成配对凭据：

```powershell
.\releases\WangChuanManager.exe --preview docs\images\windows-overview.png 概览
.\releases\WangChuanManager.exe --preview docs\images\windows-tools.png 设备工具
```
