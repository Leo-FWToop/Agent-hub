# AgentHub - Android 客户端

AgentHub 是一个 Android 客户端应用，用于连接和管理 OpenClaw Gateway 服务。

## 功能特性

- **WebSocket 连接**：通过 WebSocket 协议连接 OpenClaw Gateway
- **流式聊天**：实时显示 agent 回复内容
- **多对话管理**：支持独立会话隔离和本地持久化
- **文件上传**：支持上传文本和二进制文件
- **Markdown 渲染**：助手回复支持 Markdown 格式渲染
- **配置持久化**：配置保存到外部存储，卸载不丢失

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **网络**：OkHttp WebSocket
- **构建**：Gradle Kotlin DSL

## 构建说明

1. 使用 Android Studio 打开项目
2. 同步 Gradle 依赖
3. 连接设备或启动模拟器
4. 点击运行按钮构建并安装应用

## 版本信息

- 当前版本：1.13 (versionCode 18)
- 最低支持：Android 8.0 (API 26)
- 目标版本：Android 15 (API 35)
