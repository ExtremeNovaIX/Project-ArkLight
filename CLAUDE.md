# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 协作规则

进行代码改动之前，先向用户说明理解、改动范围、预期效果和风险，获得明确确认后再执行。用户在当前轮明确要求“实现”“修复”“执行”等同等含义时，视为已授权。

## 项目概览

ArcLight-chat 是本地 Qt 桌面应用和 Spring Boot 后端组成的 RP 助手。后端使用 Java 21、Maven、LangChain4j 整合 LLM，Qt/QML 是唯一维护的用户界面。

核心链路：

```text
Qt 输入/语音 -> Spring Boot RP/Gamer/STT/TTS API -> LLM/RP Agent -> TTS 音频事件 -> Qt 播放
```

Web 前端、后端本地配置页面和旧 LLM 指令路由链路均已废弃，不要按旧 Web 页面、后端配置编辑 API 或本地路由小模型设计新功能。

## 构建与测试

优先使用项目脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope quick
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope backend
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt
```

后端单测可在 `backend/` 下运行：

```powershell
mvn.cmd test
mvn.cmd -Dtest=SttStreamHandlerTest test
```

`surefire` 默认排除 `manual` group；手动测试通过 IDE 或显式环境变量运行。

## 配置体系

`application.yaml` 通过 `spring.config.import` 按模块拆分，并支持外部 `config/` 覆盖：

| 文件 | 职责 |
|------|------|
| `application-ai.yaml` | LLM 供应商、模型名和 Embedding |
| `application-ai-services.yaml` | LLM 服务到轻/重模型的映射 |
| `application-tts.yaml` | TTS 引擎、服务地址和运行时自动拉起 |
| `application-stt.yaml` | STT / ASR sidecar 与识别参数 |
| `application-rp.yaml` | RP 主动发言参数 |
| `application-memory.yaml` | 对话记忆、Lucene 向量索引 |
| `application-infrastructure.yaml` | 数据库、H2 控制台、日志级别 |
| `application-mcp.yaml` | MCP 工具和 Gamer 游戏交互 |
| `application-frontend.yaml` | Qt 前端默认设置 |

Qt 配置页直接读写本地 YAML，不走后端配置编辑 API。敏感值使用 `${ENV_VAR:default}` 占位符。

## 当前架构

### RP / Gamer

- `RpAgent` 管理角色 prompt、工具调用和流式输出。
- `RpProactiveAgent` 负责主动搭话。
- `component/agent/gamer/` 负责游戏循环、桥接、适配器、中断和执行追踪。
- 语音游戏门控使用 `SttGameIntentGate` 的正则策略；partial 只允许短暂 voice hold，final 再决定 wait、continue 或 apply instruction。

### STT / ASR

- Qt 将麦克风或指定应用音频推给 `/stt/stream`。
- 后端 `SttStreamHandler` 转发到本地 ASR sidecar，并只把完成、非噪声、非重叠的人声文本交给 RP。
- ASR sidecar 负责声学门控，低响度、异常频段和重叠语音不应进入 LLM。

### TTS

- `TtsSpeechService` 串行处理文本规范化、分句和 provider 合成，保证多句音频顺序。
- `VoxCpm2TtsProvider` 和 `GptSoVitsTtsProvider` 通过 HTTP 调用本地 TTS 服务。
- `TtsAudioHub` 通过 SSE 推送音频事件，Qt 端负责解码、缓冲、重采样和播放。

### Qt

- 入口：`qt-frontend/src/main.cpp`。
- QML 入口：`qt-frontend/qml/Main.qml`。
- `FrontendSettings` 管理 Qt 本地偏好。
- `ConfigCatalogController` 只负责本地 YAML 配置页读取、编辑和自动保存。

## 注释规范

- 注释面向未来维护者，说明长期稳定的职责、约束、边界或不明显风险。
- 不在注释里记录“本次改了什么”、临时背景、修复过程或提交理由。
- 配置注释说明字段语义、默认行为和影响范围；相似开关并存时说明职责边界。
- 代码注释使用中文，专有名词、协议字段、API 名称按原名保留。
