# 车机管家

面向横屏车机的 Android 管理工具，使用 Java 实现，兼容 Android 4.4.3（API 19）。

## 功能

- **应用与服务管理**：查看应用状态、搜索应用、管理服务组件、安装和备份 APK。
- **文件管理**：浏览本地存储和 U 盘，执行批量文件操作，并支持文件传输。
- **自动化任务**：功能尚未完善，当前版本不可用。
- **工具箱**：提供设备信息、连接状态、移动数据、蓝牙、音量、辅助功能和无线调试等工具。

部分功能取决于设备系统版本、厂商服务和已授予的权限。请先在目标设备上确认兼容性，再使用会改变系统或应用状态的操作。

## 界面预览

界面采用与 DiPlay 一致的深色扁平卡片风格。下图来自交互式原型，使用示例数据，不连接车机。自动化任务暂未开放，预览中仅显示不可用提示。

| 总览 | 应用管理 |
|:---:|:---:|
| ![总览页面](docs/preview/screenshots/overview.png) | ![应用管理页面](docs/preview/screenshots/apps.png) |

| 文件管理 | 工具箱 |
|:---:|:---:|
| ![文件管理页面](docs/preview/screenshots/files.png) | ![工具箱页面](docs/preview/screenshots/tools.png) |

[打开交互式界面预览](docs/preview/index.html)

## 下载与构建

[GitHub Releases](https://github.com/BradyXs/headunit-manager/releases) 仅提供平台签名版 APK。它只适用于平台签名匹配的目标固件。仓库不包含平台签名材料；本机构建平台版需要 Android SDK Platform 19、Build Tools 35.0.1 及合法授权的签名材料，并使用 `build-platform.sh`。

## 许可证与图标

本项目以 GNU GPL-3.0 许可证发布，详见 [LICENSE](LICENSE)。

部分界面图标来自 Lucide，许可文本见 [Lucide ISC License](docs/licenses/Lucide-ISC.txt)。
