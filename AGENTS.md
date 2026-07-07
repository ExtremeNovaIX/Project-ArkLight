# AGENTS.md

## 修改前确认

- 默认不得直接修改文件、运行会改变项目状态的命令、调整 UI 样式或重构实现。
- 在做任何修改前，必须先向用户说明准备修改的范围、涉及文件、预期效果和可能风险，并等待用户明确同意。
- 只有当用户在当前轮明确要求“直接改”“实现”“修复”“执行”“加上”等同等含义时，才视为已授权修改。
- 用户只是在提问、讨论方案、要求判断、要求看看或表达不满时，不得擅自改代码；只能先给结论、方案或请求确认。
- 若修改过程中发现需要扩大范围，必须停下并再次请求用户确认。

## 工具使用限制

- 当前 Windows sandbox 下 `apply_patch` 不可用；每次调用都会报错并拖慢进度。需要修改文件时，使用精确的 PowerShell 读写或其他可用方式。

## PowerShell 编码规范

- Windows PowerShell 5.1 下，即使 `[Console]::OutputEncoding` 是 UTF-8，`$OutputEncoding` 仍可能默认是 `us-ascii`；把包含中文的 here-string、字符串或脚本文本通过管道传给 `node`、`python` 等 native 程序时，非 ASCII 字符会被替换成 `?`。
- 需要通过 PowerShell 管道向 native 程序传递非 ASCII 文本前，先在同一个命令里设置：
  ```powershell
  $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
  [Console]::InputEncoding = $utf8NoBom
  [Console]::OutputEncoding = $utf8NoBom
  $OutputEncoding = $utf8NoBom
  ```
- 更稳妥的做法是避免把含中文的脚本正文直接经 PowerShell 管道传给 native 程序。需要批量改文件时，优先使用 ASCII-only 的脚本内容（例如 Unicode escape 或 Base64）驱动 `node`/`python` 读写 UTF-8 文件。
- 写入含中文的文件时，优先使用显式 UTF-8 no BOM：
  ```powershell
  $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
  [System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
  ```
  避免裸用 `Set-Content`、`Out-File` 或 shell 重定向写入含中文内容；如果必须使用，必须显式指定编码并复查结果。
- 修改含中文的源码、配置或文档后，至少用 `Get-Content -Encoding UTF8` 检查目标片段；涉及批量替换时，再用 `rg -n "\\?{2,}" <目标文件或目录>` 扫描疑似编码损坏。

## 项目地图

- `backend/`：Spring Boot 后端，提供 RP 聊天、TTS、STT 转发、本地配置、游戏控制和 doctor API。
- `qt-frontend/`：Qt/QML 桌面前端，入口为 `qt-frontend/src/main.cpp`，QML 入口为 `qt-frontend/qml/Main.qml`。
- `docs/contracts/arclight-api.openapi.json`：Qt 与后端之间的机器可读 API 契约。
- `backend/runtime/`、`backend/tts/`、`backend/llm/`：本机 runtime、模型和第三方整合包目录，默认不进 git。

## 验证入口

- 快速验证：`powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope quick`
- 后端完整测试：`powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope backend`
- Qt 构建：`powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt`
- 全量验证：`powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope all`

涉及前后端接口、doctor、STT/TTS runtime 的改动，至少运行 quick 验证。涉及 QML/C++ 的改动，额外运行 qt 验证。

## 运行时与契约

- 本地运行时检查由后端 `GET /api/doctor/status` 提供，命令行入口是 `scripts/doctor.ps1`，Qt 启动时会自动调用并弹出缺失项。
- 前后端 API 变更必须同步 `docs/contracts/arclight-api.openapi.json`，并保证 `ApiContractDocumentTest` 通过。
- 外部 ASR 整合包预留目录是 `backend/runtime/asr/custom/`；该目录只存本机文件，不提交。
- 项目级轻 Python runtime 位于 `backend/runtime/python/.venv`，初始化入口是 `backend/tools/python/bootstrap.ps1`。

## 注释规范

- 注释面向未来维护者，说明长期稳定的职责、约束、边界或不明显的风险。
- 不在注释里记录“本次改了什么”、临时背景、修复过程或提交理由。
- 避免“我们自己的”“这次”“上次”“刚刚”等会随时间失效的表述。
- 配置注释说明字段语义、默认行为和影响范围；相似开关并存时说明各自职责边界。
- 代码注释使用中文，专有名词、协议字段、API 名称按原名保留。
