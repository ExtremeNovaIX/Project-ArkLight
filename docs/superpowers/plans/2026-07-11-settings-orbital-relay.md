# Settings Orbital Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 将 Qt 设置页重构为克制、留白充足且具有隐藏技术信息层的设置界面，并接入 Orbital Relay 桌面图标。

**Architecture:** 保留 SettingsCompatDialog 的状态、网络请求和保存生命周期。通过 SettingsNav、SettingsCategoryGlyph、SettingsUiCard、SettingsSectionTitle、SettingsBlackToggle 等展示组件统一视觉语言，各业务面板只调整用户文案和局部排版。桌面图标通过 Qt 资源和 Windows RC 文件接入。

**Tech Stack:** Qt 6.5、QML、C++17、CMake、Qt Test。

## Global Constraints

- 不改变配置读取、保存、游戏控制、STT、TTS 和角色选择逻辑。
- 主背景使用米白、近黑、橙色与少量青绿色，不加入红黄蓝分段线。
- 一级分类使用用户语言：常规、模型与服务、游戏联动、语音与音频。
- 集合几何图标只在选择与悬停时执行克制的装配动画，不持续旋转。
- 设置页保持 1180 × 720 基准尺寸并支持现有 scaleFactor。
- QML/C++ 改动完成后运行 Qt 验证和原生截图对照。

---

### Task 1: 视觉契约测试

**Files:**
- Modify: `qt-frontend/tests/ConfigCatalogSupportTest.cpp`

- [ ] 添加 QML 源码契约测试，验证用户分类文案、SettingsCategoryGlyph 引用、无“前端设置”一级标题、无彩色分段线组件。
- [ ] 运行 Qt 测试并确认测试因新组件尚不存在而失败。

### Task 2: 设置页框架与导航

**Files:**
- Create: `qt-frontend/qml/SettingsCategoryGlyph.qml`
- Create: `qt-frontend/qml/SettingsBackdrop.qml`
- Modify: `qt-frontend/qml/SettingsCompatDialog.qml`
- Modify: `qt-frontend/qml/SettingsNav.qml`
- Modify: `qt-frontend/qml/SettingsNavButton.qml`
- Modify: `qt-frontend/qml/ArkLightTokens.qml`
- Modify: `qt-frontend/CMakeLists.txt`

- [ ] 实现米白单层弹窗、窄导航、Orbital Relay 标志、微信息背景和无卡片主内容区。
- [ ] 实现四种集合几何图标及 140–220 ms 的选择装配动画。
- [ ] 运行 Qt 测试并修复 QML 资源和语法问题。

### Task 3: 基础控件与业务分区

**Files:**
- Modify: `qt-frontend/qml/SettingsUiCard.qml`
- Modify: `qt-frontend/qml/SettingsSectionTitle.qml`
- Modify: `qt-frontend/qml/SettingsBlackToggle.qml`
- Modify: `qt-frontend/qml/SettingsUiButton.qml`
- Modify: `qt-frontend/qml/SettingsUiField.qml`
- Modify: `qt-frontend/qml/SettingsUiCombo.qml`
- Modify: `qt-frontend/qml/SettingsFrontendPanel.qml`
- Modify: `qt-frontend/qml/SettingsConfigPanel.qml`
- Modify: `qt-frontend/qml/SettingsGamePanel.qml`
- Modify: `qt-frontend/qml/SettingsVoicePanel.qml`

- [ ] 将通用卡片改为轻量台账行与细分隔线。
- [ ] 将深色开关卡改为米白工业开关行。
- [ ] 调整一级标题和说明文案为用户语言。
- [ ] 保留所有现有 signal、binding 和 onClicked/onToggled 行为。

### Task 4: Orbital Relay 桌面图标

**Files:**
- Create: `qt-frontend/assets/app-icon/orbital-relay.png`
- Create: `qt-frontend/assets/app-icon/orbital-relay.ico`
- Create: `qt-frontend/src/windows/arklight.rc`
- Modify: `qt-frontend/src/main.cpp`
- Modify: `qt-frontend/CMakeLists.txt`

- [ ] 生成黑色圆盘、奶白双轨道、中央通信槽和橙色刻度的方形图标。
- [ ] 生成包含 16、24、32、48、64、128、256 px 的 ICO。
- [ ] 在 QGuiApplication 和 Windows 可执行文件资源中设置图标。

### Task 5: 视觉规范与验证

**Files:**
- Create: `docs/design/arklight-visual-language.md`
- Create: `design-qa.md`

- [ ] 记录配色、字体、间距、图标、动效、纹理、设置页组件和禁止项。
- [ ] 运行 `powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt`。
- [ ] 启动原生 Qt 应用，截取设置页并与目标稿并排比较。
- [ ] 修复 P0/P1/P2 视觉差异，直到 `design-qa.md` 写入 `final result: passed`。
