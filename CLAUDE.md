# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 协作规则

进行代码改动之前，先向用户说明你的理解和计划的改动方案，获取用户确认后再执行。不可直接动手修改。

## 项目概览

ArcLight-chat — Spring Boot 4.0.4 后端，Java 21 + Maven，以 LangChain4j 1.12.2 整合 LLM。

核心能力：角色扮演（RP）Agent 流式生成对话 → TTS 合成语音 → WebSocket 推送音频帧给前端。附带本地指令路由小模型（llama.cpp）做闭集意图分类。

## 构建与测试

```bash
# 编译
mvn compile

# 运行全部测试（排除 @Tag("manual") 的测试）
mvn test

# 运行单个测试类
mvn test -Dtest=VoxCpm2RouterAccuracyTest

# 运行单个测试方法
mvn test -Dtest=RpTtsPipelineTest#fullPipeline
```

`surefire` 默认排除 `manual` group，手动测试通过 IDE 运行配置注入环境变量（如 API Key）。

## 配置体系

`application.yaml` 通过 `spring.config.import` 按模块拆分，并支持外部配置覆盖：

| 文件 | 职责 |
|------|------|
| `application-ai.yaml` | LLM 供应商（DeepSeek API / 本地模型）+ Embedding |
| `application-tts.yaml` | TTS 引擎（VoxCPM2 / GPT-SoVITS）及运行时自动拉起 |
| `application-rp.yaml` | RP 主动表达、表达欲参数 |
| `application-memory.yaml` | 对话记忆、Lucene 向量索引 |
| `application-infrastructure.yaml` | 数据库（H2）、日志级别 |
| `application-mcp.yaml` | MCP 工具 + Gamer（游戏交互） |
| `application-frontend.yaml` | Web/Qt 前端配置 |

外部配置通过 `arclight.config.uri` 环境变量指向 `../config/` 目录，优先级高于 resources 默认值。敏感值使用 `${ENV_VAR:default}` 占位符。

## 架构

### TTS 管线（当前分支 `tts` 的核心）

```
RP Agent（streaming）→ TtsSpeechService.accept(text)
  ├─ TtsTextNormalizer → 去 Markdown/URL/特殊字符
  ├─ TtsStyleExtractor → 正则提取句首（风格），router 分类
  ├─ TtsTextChunker → 按句末标点拆分 + 合并不足 min-chunk-chars 的碎片
  └─ VoxCpm2TtsProvider → HTTP 调用 VoxCPM2 Python 服务合成 WAV
       └─ TtsAudioHub → WebSocket 推送音频帧给前端
```

关键细节：
- `TtsSpeechService` 使用 `CompletableFuture` 链式串行合成，保证多句音频顺序输出
- `TtsStyleExtractor` 通过 `InstructionRouter`（本地 Qwen3.5-0.8B）从播音风格描述中提取 emotion（前端表情）和 vox_tag（VoxCPM2 非语言标签如 `[laughing]`）
- `TtsTextNormalizer` 的正则 `\[[^\]]{1,24}\]` 会匹配并保留 vox_tag 短标签（如 `[laughing]`），但会过滤 Markdown 链接等长括号内容

### 指令路由器（InstructionRouter）

```
业务调用方 → InstructionRouter.route(request)
  └─ LocalModelInstructionRouter → HTTP → llama.cpp server (:8087)
       ├─ system prompt: InstructionRouterTask.SYSTEM_PROMPT（通用路由 JSON schema）
       ├─ user prompt: task.renderUserPrompt()（XML 结构：scene_id + allowed_intents + scene_instruction + runtime_context + user_message）
       └─ 解析 JSON → InstructionRouteDecision(intent, confidence, instruction, reason)
```

- 通用路由：用于 RP 游戏控制指令分类（`rp-game-control` task）
- 专项路由：TTS 风格提取（`tts-style-extract` task），使用 `TtsStyleExtractor.SCENE_INSTRUCTION` 作为 system prompt，不走 XML 包装
- 模型：Qwen3.5-0.8B-Q8_0.gguf，`--reasoning off` 禁用思考 token
- 配置：`InstructionRouterModelConfig` 写死地址/端口/超时/启动命令，不走 yaml

### RP Agent

`RpAgent` 是角色扮演对话的核心，管理 prompt 拼接、工具调用、流式输出。`RpProactiveAgent` 负责主动搭话。RpAgent 通过 `game_control` 工具可被前端实时打断/暂停/恢复。

### Gamer（游戏交互层）

`component/agent/gamer/` 下的子包：
- `adapter/` — 适配不同游戏后端（STS2）
- `bridge/` — 状态/结果/队列的桥接
- `interrupt/` — 中断处理
- `loop/` — 游戏主循环
- `memory/` — 游戏状态记忆
- `trace/` — 执行追踪

### Memory 系统

`component/agent/memory/dream/` 实现对话记忆的长期存储和检索，包括 episode 划分、insight 提取、cluster 聚合、anchor 锚定，基于 Lucene 向量索引。

## 本地服务依赖

开发和测试需要手动启动以下本地服务（Spring 在生产环境通过 `auto-start-enabled` 自动拉起）：

| 服务 | 端口 | 用途 |
|------|------|------|
| llama.cpp server | 8087 | 指令路由小模型 |
| VoxCPM2 | 8810 | TTS 合成 |
| GPT-SoVITS | 9880 | 备用 TTS 引擎 |

Router 启动命令参考 `InstructionRouterModelConfig.runtimeCommand()`，TTS 启动命令参考 `application-tts.yaml` 中对应 provider 的 runtime 配置。

## 代码约定

- 配置类不走 yaml 的，写在 `*Config` 类中作为工程级默认值（如 `InstructionRouterModelConfig`）
- 接口 + 实现：`InstructionRouter` → `LocalModelInstructionRouter`，`TtsProvider` → `VoxCpm2TtsProvider` / `GptSoVitsTtsProvider`
- `@PostConstruct` 用于注册 task（如 `TtsStyleExtractor.registerTask()`）
- 使用 `InstructionRouteRequest.byTask()` 走 task registry 的专项任务；`byPrompt()` 兼容旧调用方式
- `normalizedIntent()` 返回 `.toUpperCase()` 后的 intent 字符串
