# Host Integration — 连接 Android-Guru-Agent

本目录是**即取即用**的宿主接入物料：配置片段、可导入技能、逐步操作指引。
（完整架构文档见 `docs/HOST-INTEGRATION.md`。）

## 30 秒接入（同机两 App）

### 1. 起 Pixel-Lab 服务器

装 Pixel-Lab 样例 App（CI 的 `pixel-lab-sample-apk` 产物，或自建
`./gradlew :sample-app:assembleDebug`），打开画廊右上角 **MCP server** 按钮：

- 打开面板里的开关 → 服务器启动（固定端口，配置一次永久有效）
- 状态卡会显示：HTTP `127.0.0.1:8901`（`POST /mcp` + `GET /sse`）、
  WebSocket `127.0.0.1:8902`
- 持久化目录 = App 私有 `files/pixel-lab`（与画廊**同一个存储**：
  Agent 的 `session_save` 存档会出现在人类画廊里，反之亦然）

### 2. 让 Android-Guru-Agent 连上来（三选一）

| 方式 | 操作 | 适用 |
|:---|:---|:---|
| **配置导入** | 设置 → MCP 服务器 → 导入 → 选本目录 `mcp-servers.pixellab.json` | 最稳，一次配置 |
| **对话内连接** | 直接对 Agent 说：「连接 MCP 服务器 http://127.0.0.1:8901/mcp 然后列出工具」→ 宿主 `mcp_connect` 工具自动注册 | 临时试用 |
| **市场页添加** | MCP 市场页手动填 streamable_http + URL | 有 UI 偏好的用户 |

连接成功后 **174 个工具**以 `mcp__pixel-lab__*` 前缀注册为宿主一等工具，
schema 自动并入 LLM 工具目录——无需任何宿主代码改动。

### 3. 灌入技能（让 Agent 会用这 174 个工具）

`skills/` 目录下三个方法论技能（`apex-skill-v1` 格式，与宿主
`SkillRegistry` 兼容）：

| 技能 | 教会 Agent |
|:---|:---|
| `pixel-sprite-design.json` | 剪影优先三阶段 + draw_batch/checkpoint/canvas_read 工作节奏 |
| `pixel-palette-coach.json` | 选板/量化/色数红线/覆盖率与对比度审计 |
| `pixel-animation-workflow.json` | 关键帧先行/帧差核对/循环闭合门/导出选择 |

安装：把 JSON 放进宿主 `<filesDir>/skills/`（`adb push` 或宿主的技能
导入/市场流程）。Prompt 型技能每轮对话自动注入方法论；无 `toolsRequired`、
无权限要求，`privilegeLevel: none`。

### 4. 验证端到端

对 Agent 说：

> 用 draw_batch 在 16x16 画布上画一个像素苹果，checkpoint_set 先设检查点，
> 画完 canvas_read 给我看，然后 session_save 存为 "apple-16"。

预期行为：一次批量调用完成轮廓+填充 → sketch 读回展示 → 命名存档
（画廊可见）。任何一步卡住说明连接/技能有问题，按面板状态卡排查。

## 交叉开发（不同机器）

MCP 服务器只绑 `127.0.0.1`。跨设备场景用 adb 端口转发后在宿主机侧把
URL 换成 `http://127.0.0.1:8901/mcp`：

```
adb forward tcp:8901 tcp:8901
```

## 生产宿主（App 内嵌）

样例 App 的服务器是**活动级生命周期**（关屏即停）。生产集成请从前台
Service 持有 `PixelMcpServer`，并参考 `docs/HOST-INTEGRATION.md` 的：

- 路径 A（进程内 Kotlin API）与路径 C（AAR / BUILTIN transport）选型
- `onProjectDiscarded` → `engine.clearHistory` 的防泄漏接线
- `persistenceRoot` 持久化目录规划（建议与画廊共用一个 root）
