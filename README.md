# ArcLight-chat

ArcLight-chat 想做的不是一个只会陪聊的角色扮演机器人，而是一个能进入游戏、理解局势、和玩家一起做决定的 Gamer Agent。

当前项目的重点是 Gamer：它通过 MCP 连接真实游戏，把游戏状态交给 RP Agent 理解，再把角色说出的行动意图转换成可执行的游戏操作。它既能作为角色说话，也能在合适的时机推动游戏行动，目标是让 Agent 更像一个真正坐在语音里的队友。

## 亮点

### 流式指令解析，加速 LLM 决策落地

传统做法通常要等 LLM 完整输出后再解析指令。ArcLight-chat 的 Gamer 链路会在 parser 流式输出过程中持续读取增量内容，一旦检测到完整、可执行的 `operations` 数组，就提前完成解析并取消后续生成。

这带来几个直接收益：

- 游戏动作不必等待整段回复结束，行动延迟更低。
- parser 只负责把 RP 的动作翻译成 MCP 操作，不再承担额外解释，决策链路更短。
- 每次解析都会记录 latency、是否提前完成、是否提前取消，方便排查真实延迟来源。

对回合制游戏来说，这个差异很明显：Agent 不只是“想到了怎么做”，而是能更快把动作交给游戏执行。

### 游戏状态驱动，而不是脚本点击

Gamer 不依赖固定脚本路线。它会先读取游戏 MCP server 暴露的最新状态，再把状态压缩成 RP Agent 能理解的局势摘要。

当前 STS2 适配器已经支持：

- 判断当前是否轮到玩家行动。
- 区分战斗、奖励、事件、地图、菜单等状态。
- 根据当前状态收窄可用操作，减少 LLM 误选工具。
- 在执行前修复过期参数，例如手牌、奖励、事件选项和地图节点的 index。
- 在状态变化后中断剩余操作队列，下一轮重新观察再决策。

换句话说，Agent 做的是基于局势的操作，而不是盲目复读一串按钮。

### RP 和游戏控制在同一条链路里

ArcLight-chat 的核心体验是角色扮演，所以 Gamer 不是单独的自动化工具。它会和 RP 对话、TTS、实时消息、记忆系统一起工作。

Agent 可以：

- 观察游戏状态后说出自己的判断。
- 把自然语言动作转换成真实 MCP 操作。
- 在需要用户确认时发起战术询问。
- 等待用户回答或超时后继续游戏。
- 把关键行动、失败原因和 parser 输出写入复盘日志。

这让 Gamer 更接近“队友”，而不是一个藏在后台的宏。

## 愿景

这个项目的长期目标，是让 Gamer Agent 能真的作为多人游戏队友存在。

理想状态下，它应该能做到：

- 听懂队友的临场指令，而不是只执行预设命令。
- 判断什么时候该行动、什么时候该等待、什么时候该问人。
- 用自然语言解释自己的战术意图。
- 在多人游戏里尊重其他玩家的行动窗口，不抢操作、不重复触发同一个指令。
- 面对局势变化时重新计划，而不是固执执行过期队列。

第一阶段会优先服务回合制和低反应压力游戏。这样的游戏状态更清晰，行动窗口更稳定，也更适合把 Agent 的观察、讨论、确认和执行能力做扎实。高频动作游戏不是当前第一版目标。

## 当前能力

| 能力 | 状态 |
| --- | --- |
| RP 角色对话 | 已接入，支持角色 prompt、记忆和流式回复。 |
| TTS 语音输出 | 已接入，可把 RP 回复推送到前端播放。 |
| MCP 游戏接入 | 已接入，支持 stdio 和 SSE 两类传输。 |
| Gamer 自动循环 | 已实现，可启动、暂停、恢复、停止。 |
| 流式动作 parser | 已实现，可提前解析可执行操作并取消剩余生成。 |
| STS2 适配器 | 已实现，当前主要面向杀戮尖塔 2 MCP Mod。 |
| 游戏复盘日志 | 已实现，默认写入 `data/game-traces/`。 |
| 多人语音中断 | 设计中，当前已有战术询问、等待窗口和慢观察退避。 |

## Gamer 是怎么工作的

Gamer 的主链路如下：

1. 前端或 API 启动游戏循环，指定 `gameName`、`sessionId` 和 `rpSessionId`。
2. `RpGameDriver` 按固定间隔轮询活动游戏会话。
3. `GameBridgeService` 通过 MCP 读取最新游戏状态。
4. `GameAdapter` 判断当前是否可行动，并渲染状态摘要和可用操作。
5. `RpGameTurnService` 唤醒在线 RP 会话，让角色基于当前局势做一轮判断。
6. RP 输出游戏控制块，说明要说什么、做什么。
7. `RpActionParserService` 使用流式 parser 把自然语言动作翻译成 MCP 操作 JSON。
8. `GameOperationQueueProcessor` 逐条执行操作队列。
9. 执行过程中如果状态变化、工具失败或需要用户确认，队列会中断，下一轮基于新状态重新计划。

这条链路把“说话”和“行动”放在同一个会话里。Agent 不只是调用工具，它会以角色身份参与游戏。

## STS2 适配器

当前最完整的游戏适配器是 `STS2Adapter`，adapter id 为 `sts2`，面向杀戮尖塔 2 MCP Mod。

它负责处理杀戮尖塔 2 的具体游戏语义：

- 自动检测单人和多人模式，并处理多人模式的 `mp_` 工具名前缀。
- 用 `get_game_state` 获取 JSON 状态。
- 把复杂状态渲染成 RP 能读懂的摘要。
- 判断战斗中是否处于玩家行动阶段。
- 在奖励、事件、地图、菜单等非战斗状态中暴露合适动作。
- 编译多步操作计划，例如连续出牌、领奖励、选地图节点。
- 执行前重新读取状态，修复因为状态推进导致的过期 index。
- 抽牌、弃牌、打开选择界面等会改变行动窗口的操作后，主动中断剩余队列并等待重新规划。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `backend/` | Spring Boot 后端，包含 RP Agent、TTS、记忆、MCP 和 Gamer 逻辑。 |
| `frontend/arklight-frontend/` | Vue 3 + Vite Web 前端，包含聊天界面和游戏循环控制面板。 |
| `qt-frontend/` | Qt/QML 前端骨架，用于桌面端聊天体验。 |
| `launcher/` | Windows 启动器，用于发布包中拉起后端和前端。 |
| `config/` | 外部配置目录，优先级高于后端 resources 默认配置。 |
| `mcp-servers/` | 本地 MCP server 自动发现目录。 |
| `chara/` | 角色资源目录，包含角色 prompt 和表情图片等内容。 |
| `scripts/` | 开发和打包脚本。 |

## 后端 Gamer 目录

Gamer 主体位于 `backend/src/main/java/p1/component/agent/gamer/`。

| 包或类 | 职责 |
| --- | --- |
| `adapter/` | 游戏适配器接口和实现。 |
| `adapter/core/` | 通用状态快照、操作、队列项、行动窗口、前置条件和 MCP schema 兼容逻辑。 |
| `adapter/sts2/` | 杀戮尖塔 2 专属状态摘要、动作渲染、出牌计划编译、操作修复和状态监控。 |
| `bridge/` | RP 游戏控制链路与底层 MCP 工具之间的桥接服务。 |
| `bridge/queue/` | 操作队列解析、排队、执行和中断处理。 |
| `bridge/result/` | 操作结果渲染和写入 RP 记忆。 |
| `interrupt/` | 游戏执行中断请求和中断状态管理。 |
| `loop/` | 自动游戏循环、活动会话注册、执行锁和慢观察退避。 |
| `trace/` | 游戏决策与 MCP 执行复盘。 |
| `GamerMCPClientFactory` | 根据配置创建并缓存 MCP client 和 ToolProvider。 |
| `GamerRequestResolver` | 把可选请求参数解析成明确的游戏名和会话 id。 |
| `GamerPendingQuestionService` | 处理 RP 向用户发起的短战术确认。 |

## 配置 Gamer

Gamer 的配置入口主要在 `application-mcp.yaml`、`mcp-catalog.yaml` 和运行时 MCP 注册表。

默认配置：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `mcp.servers-directory` | `../mcp-servers` | 自动扫描本地 MCP server 的目录。 |
| `mcp.registry-file` | `config/mcp-registry.json` | REST API 注册项的持久化文件。 |
| `game.default-session-id` | `default` | 请求未传 `sessionId` 时使用的默认游戏 session。 |
| `game.loop.poll-interval-ms` | `500` | 游戏循环轮询间隔。 |
| `game.loop.slow-observe.enabled` | `true` | 无有效动作时启用慢观察退避。 |
| `game.trace.trace-enabled` | `true` | 是否写入游戏操作复盘。 |
| `game.trace.trace-directory` | `data/game-traces` | 复盘文件输出目录。 |

### 启用 STS2MCP

`config/mcp-catalog.yaml` 和后端默认资源中都包含 `STS2MCP` 模板。使用模板时，可以在外部 `config/application-mcp.yaml` 中提供安装路径：

```yaml
mcp:
  games:
    STS2MCP:
      install-path: E:/path/to/STS2_MCP
      enabled: true
```

模板会解析为：

```yaml
transport: stdio
command: uv
args: ["run", "--directory", "E:/path/to/STS2_MCP", "python", "server.py"]
adapter: sts2
state-tool-name: get_game_state
```

也可以通过 REST API 注册：

```bash
curl -X POST http://127.0.0.1:8080/api/mcp/register \
  -H "Content-Type: application/json" \
  -d '{
    "gameName": "STS2MCP",
    "displayName": "杀戮尖塔2",
    "transport": "stdio",
    "command": "uv",
    "args": ["run", "--directory", "E:/path/to/STS2_MCP", "python", "server.py"],
    "adapter": "sts2",
    "stateToolName": "get_game_state",
    "enabled": true
  }'
```

## Gamer API

### 游戏循环控制

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/gamer/loop/start` | 启动自动游戏循环。 |
| `POST` | `/api/gamer/loop/stop` | 停止自动游戏循环。 |
| `POST` | `/api/gamer/loop/pause` | 暂停自动游戏循环。 |
| `POST` | `/api/gamer/loop/resume` | 恢复自动游戏循环。 |
| `GET` | `/api/gamer/loop/status` | 查询游戏循环状态。 |

启动请求示例：

```json
{
  "gameName": "STS2MCP",
  "sessionId": "default",
  "rpSessionId": "default"
}
```

`sessionId` 是游戏侧会话 id，`rpSessionId` 是 RP 聊天会话 id。两者可以相同，也可以拆开绑定。

### MCP 管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/mcp/catalog` | 查看预设 MCP 模板。 |
| `GET` | `/api/mcp/servers` | 查看当前启用的 MCP server。 |
| `GET` | `/api/mcp/discovered` | 查看从 `mcp-servers/` 自动发现的 MCP server。 |
| `POST` | `/api/mcp/register` | 注册或更新一个 MCP server。 |
| `POST` | `/api/mcp/servers/{name}/refresh` | 刷新指定 MCP server 连接。 |
| `DELETE` | `/api/mcp/servers/{name}` | 删除运行时注册的 MCP server。 |

## Web 前端中的 Gamer

Web 前端的游戏控制面板位于 `frontend/arklight-frontend/src/setting/GameSettingsPanel.vue`。

面板支持：

- 设置 `gameName`，默认配置为 `STS2MCP`。
- 设置游戏 `sessionId`，留空时使用聊天 session。
- 设置绑定的 `rpSessionId`，留空时使用聊天 session。
- 启动、暂停、恢复、停止游戏循环。
- 查看当前状态、游戏名、步数、启动时间和最后活动时间。

前端默认值在 `application-frontend.yaml` 中配置：

```yaml
frontend:
  web:
    settings:
      game-name: STS2MCP
      game-session-id: ""
      game-rp-session-id: ""
```

## 本地开发

### 前置要求

- JDK 21。
- Maven，或使用 `backend/mvnw.cmd`。
- Node.js 与 npm。
- 使用 STS2MCP 时需要安装 `uv`，并把 STS2 MCP Mod 准备在本地目录。
- 完整 RP 对话需要配置可用的 LLM provider。私密配置建议放在 `config/` 下的外部配置文件或环境变量中。

### 启动 Web 开发环境

第一次启动前安装前端依赖：

```bash
cd frontend/arklight-frontend
npm install
```

然后在仓库根目录运行：

```bat
scripts\start-web-dev.bat
```

脚本会设置 `ARCLIGHT_CONFIG_DIR`、`ARCLIGHT_CHARA_DIR`、`MCP_SERVERS_DIR` 和 `MCP_REGISTRY_FILE`，启动后端 `http://127.0.0.1:8080`，再启动 Vite 前端 `http://127.0.0.1:3000`。

### 单独启动后端

```bat
cd backend
mvn.cmd spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

### 常用检查

后端测试：

```bat
cd backend
mvn.cmd test
```

Gamer 相关测试：

```bat
cd backend
mvn.cmd test -Dtest=GamerPendingQuestionServiceTest,RpGameDriverTest,GamerRequestResolverTest,GamerDecisionTraceServiceTest
```

前端类型检查：

```bash
cd frontend/arklight-frontend
npm run lint
```

## 打包

Windows 发布包脚本：

```bat
scripts\package-windows.bat
```

脚本会构建 Web 前端、后端 jar、复制 `config/`、`chara/` 和 `mcp-servers/`，并生成 `dist/Arklight-v<version>/`。如果配置了 Qt 环境，也会打包 Qt 前端。

发布包中可用入口：

- `Arklight-Web.exe`：启动后端并打开 Web UI。
- `Arklight-Qt.exe`：启动后端并打开 Qt 前端，只有打包 Qt 时存在。
- `start-web.bat`：脚本方式启动 Web UI。
- `start-qt.bat`：脚本方式启动 Qt UI，只有打包 Qt 时可用。

## 调试 Gamer

- 查看 `/api/mcp/servers`，确认游戏 MCP server 已启用。
- 查看 `/api/gamer/loop/status`，确认循环是否处于 `RUNNING`。
- 检查后端日志中的 `[MCP]`、`[RP游戏驱动]`、`[游戏桥接]` 和 `[游戏复盘]` 前缀。
- 查看 `data/game-traces/` 下的复盘 markdown，定位 RP 控制块、parser 输出、MCP 工具调用和中断原因。
- 如果请求没有传 `gameName`，且启用了多个 MCP 游戏，后端会要求显式传入游戏名。
