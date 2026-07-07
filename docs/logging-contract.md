# Logging Contract

## Goals

`app.log` is the quick diagnostic entry point. In this project it contains both structured one-line events and the readable full LLM call block, because seeing the generated response and reasoning in the main run log is the fastest way to debug agent behavior.

`game-trace` remains the game decision replay file. It keeps RP-visible context, reasoning, plan, operation queue, execution result, and failure feedback.

## Main Log Format

Main log events use stable key-value fields:

```text
domain=GAME event=turn.completed outcome=SUCCEEDED game=STS2MCP session=default rpSession=qt-session totalStep=18
domain=LLM event=call.completed outcome=SUCCEEDED service=rp call=RpAiService:chat session=qt-session model="deepseek-reasoner @ https://..." inputTokens=1243 outputTokens=312 totalTokens=1555
domain=STT event=sidecar.unavailable outcome=DEGRADED engine=sherpa-qwen reason=runtime_missing hint=backend/tools/asr/bootstrap-sherpa-qwen.ps1
domain=TTS event=speech.skipped outcome=SKIPPED session=qt-session reason=no_audio_subscriber
```

Blank and null values are omitted by `AppLogger`. Values containing whitespace are quoted.

## Domains

- `LLM`: model calls and model-call failures.
- `GAME`: game loop, action queue, bridge interruption, and session stop summaries.
- `STT`: ASR runtime, sidecar, stream, and routing boundary events.
- `TTS`: speech synthesis skip/failure boundary events.
- `MCP`: MCP registry/client/runtime boundary events.
- `MEMORY`: memory/archive/vector boundary events.
- `HTTP`: HTTP/API boundary events.
- `RUNTIME`: local runtime/bootstrap boundary events.

## Outcomes

- `SUCCEEDED`: operation completed.
- `SKIPPED`: expected no-op, such as disabled TTS or no audio subscriber.
- `DEGRADED`: recoverable fallback or unavailable optional runtime.
- `FAILED`: operation failed and caller must handle it.
- `FATAL`: repeated failure forced a session or runtime stop.

## Level Policy

- `ERROR`: unexpected failure that changes control flow or stops a session/runtime.
- `WARN`: recoverable degradation, unavailable optional runtime, interrupted operation queue.
- `INFO`: successful method/turn/call summary, expected user-visible state change, and full LLM call blocks when enabled.
- `DEBUG`: connection churn, probes, attempts, and internal retry details.

Expected missing local STT/TTS runtime is not an `ERROR`. It should be `WARN` or `INFO` with a structured `reason`.

## LLM Trace In App Log

LLM calls write a structured summary first, then the readable full block to the same application logger when `assistant.llm-logs.console.<service>` is enabled. Defaults enable `rp`, `parser`, `checker`, and `supervisor`.

`app.log` preserves the readable LLM block:

```text
==================== [LLM调用跟踪开始] ====================
[服务] rp
[调用] RpAiService:chat
[模型] deepseek-reasoner @ https://...
[SessionId] qt-session
[当前对话轮数] 12
[最新请求]
...
[推理内容]
...
[响应]
...
[本次调用 Tokens] [I:1243 O:312 T:1555]
[本次缓存命中] [Hit:800 I:1243 Rate:64%]
[Session Tokens] [I:24000 O:6100 T:30100]
[Session缓存命中] [Hit:12000 I:24000 Rate:50%]
==================== [LLM调用跟踪结束] ====================
```

Do not reintroduce a separate LLM trace logger or file unless the project outgrows the single-log workflow.

## Implementation Rules

Use `AppLogger` for new main-log events. Use `LogContext` when a boundary needs temporary MDC fields such as `traceId`, `sessionId`, `rpSessionId`, `game`, or `serviceInfo`.

Do not add per-step success logs on hot paths. If a step matters only when it fails, log it only on failure or DEBUG.

## Boundary AOP

Use `@LoggedOperation(domain = ..., operation = ...)` on controller/service entry methods whose log value is only boundary success/failure.

The aspect emits:

```text
domain=GAME event=method.completed outcome=SUCCEEDED operation=game.loop.start method=GamerController.startLoop durationMs=18
domain=HTTP event=method.failed outcome=FAILED operation=chat.send method=ChatController.send httpStatus=500 reason=http_error_status durationMs=920
domain=GAME event=method.failed outcome=FAILED operation=game.loop.stop method=GamerController.stopLoop exception=IllegalStateException reason="state changed" durationMs=4
```

The aspect reads MDC fields (`traceId`, `sessionId`, `rpSessionId`, `game`, `serviceInfo`) and does not log method arguments. This avoids leaking prompts, user text, credentials, or large objects.

## Event Naming

Use dotted, stable event names. Current main-log events include:

| Event | Domain | Purpose |
| --- | --- | --- |
| `method.completed` / `method.failed` | any | Boundary AOP summaries. |
| `call.completed` / `call.failed` | `LLM` | LLM call summaries in `app.log`; full readable block also goes to `app.log` when enabled. |
| `turn.completed` / `turn.failed` | `GAME` | One game-loop turn summary. |
| `operation.interrupted` | `GAME` | Operation queue interrupted or state did not advance as expected. |
| `session.registered` / `session.unregistered` | `GAME` | Active game session registry changes. |
| `session.paused` / `session.resumed` | `GAME` | User-visible loop state changes. |
| `coordination.wait_started` / `coordination.wait_expired` | `GAME` | RP/game user-wait coordination. |
| `coordination.intent_submitted` | `GAME` | User supplied a new game instruction. |
| `runtime.ready` / `runtime.started` / `runtime.exited` / `runtime.unavailable` | `TTS`/`STT` | Local runtime lifecycle and degradation. |
| `speech.skipped` | `TTS` | Expected no-op such as no audio subscriber. |
| `batch.collecting_created` / `batch.promoted` / `batch.acknowledged` / `batch.deleted` | `MEMORY` | Raw dialogue batch lifecycle. |
| `archive.write_started` / `archive.write_completed` / `archive.write_failed` | `MEMORY` | Archive persistence boundary. |
| `vector.write_completed` / `vector.rebuild_fallback` | `MEMORY` | Vector write and recoverable rebuild fallback. |
| `json.sanitized_parse_succeeded` / `json.repair_succeeded` / `json.repair_failed` | `RUNTIME` | Tolerant JSON parsing and repair results. |
| `registry.loaded` / `registry.saved` / `registry.load_failed` / `registry.save_failed` | `MCP` | Runtime MCP registry file store. |

## Replacement Rules

- Boundary-only logs move to `@LoggedOperation`.
- Business branches, degradation, skip, fallback, and state transition logs use explicit `AppLogger` events.
- LLM replay-grade details stay in `app.log`; game replay-grade details stay in `game-trace`.
- Existing `DEBUG` logs may stay as local diagnostics.
- New `INFO`, `WARN`, and `ERROR` main-log events should be structured with `domain`, `event`, and `outcome` unless they are explicit readable blocks such as `AppLogger.infoBlock(...)`.
- Event names must be semantic. Do not use generated names such as `some.class.info.1`, `some.class.warn.2`, or `some.class.error.3`.
- Field names must be meaningful. Do not use `arg0`, `arg1`, `value7`, `sourceLine`, or similar mechanical placeholders.
- MCP startup/discovery banner logs are intentionally outside this pass.

## Lombok Logger Shortcut

Backend code configures Lombok `@CustomLog` in `backend/lombok.config`:

```properties
lombok.log.fieldName = log
lombok.log.custom.declaration = p1.infrastructure.logging.AppLogger p1.infrastructure.logging.AppLogger.getLogger(TYPE)
```

Use `@CustomLog` instead of `@Slf4j` in production classes:

```java
@CustomLog
class MyService {
    void run() {
        log.info(LogDomain.GAME, "turn.completed", LogOutcome.SUCCEEDED, fields);
        log.debug("state probe skipped: session={}, reason={}", sessionId, reason);
    }
}
```

`log` is an `AppLogger`, not an SLF4J `Logger`. New main-log `INFO`, `WARN`, and `ERROR` events should use the `LogDomain/event/LogOutcome` overloads when they describe business state, degradation, skip, failure, or method boundaries.

`AppLogger` exposes SLF4J-style `trace/debug/info/warn/error(String, Object...)` overloads for compatibility, and `infoBlock(String)` for explicit readable multi-line blocks. Production `INFO`, `WARN`, and `ERROR` calls must use structured `LogDomain/event/LogOutcome` overloads except for those explicit blocks. `LoggingContractStaticTest` enforces this for `backend/src/main/java`, including structured main-log entry points, semantic event names, and non-mechanical field names. Local diagnostic `DEBUG` and `TRACE` messages may stay string-based when they are not part of the main log contract.

Direct SLF4J access is reserved for logging infrastructure itself.
