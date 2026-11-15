# MiniMe-Code

手机上的 AI 编程工具与移动工作站。

无需电脑，在手机上即可完成从写代码、调试到提交的完整开发流程。内置 Linux 容器与终端，AI Agent 可自主读写文件、执行 Shell 命令与运行构建，还支持 Git、MCP、远程 SSH 与完整模型生态。

## 核心特性

### AI Agent

支持 OpenAI / Anthropic / Gemini 兼容协议，多供应商随时切换；内置文件读写、Shell 执行、后台终端、搜索与图片识别等工具，流式输出，长对话自动压缩上下文。

### 内置终端与 Linux 容器

基于 Termux 组件与 PRoot 的本地容器，内置 Alpine 镜像，可导入自定义 rootfs、挂载宿主目录，终端支持后台常驻。

### 远程 SSH 模式

以远程服务器作为执行后端，命令走 exec channel、文件走 SFTP、终端走 shell channel，在手机上直接操作远程项目。

### 检查点与撤销

AI 改代码前自动记录文件快照，对话中可一键回滚，支持只恢复代码、只恢复对话，或两者同时恢复。

### MCP、技能与记忆

可连接本地 stdio 与远程 HTTP 的 MCP 服务器动态扩展工具；支持全局与项目级技能和长期记忆，让 AI 跨会话复用经验。

### Git 与工作区同步

内置状态、分支、提交、差异与标签管理；支持 SFTP / FTP 同步，并自带 FTP 服务端方便电脑侧管理文件。

## 开始使用

- **快速上手**：从安装到完成第一条 AI 对话，见「开始使用 → 快速上手」。
- **功能总览**：了解全部能力，见「开始使用 → 功能总览」。
- **获取安装包**：当前仅提供 arm64 真机包（`minimecode-arm64-<版本>.apk`），在「设置 → 关于 → 检查更新」中可直接在 App 内下载安装。
- **获取源码**：访问 [GitHub 仓库](https://github.com/Lisir2002/MiniMe-AIBot)。

MiniMe-Code 基于 [AiCode](https://github.com/jieapi/AiCode) 二次开发，感谢上游项目及其贡献者。
