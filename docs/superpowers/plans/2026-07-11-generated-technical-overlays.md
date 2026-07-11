# Generated Technical Overlays Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two image-generated, alpha-backed raster overlays to the Qt chat background so the approved megastructure and complex technical graphics return without competing with chat content.

**Architecture:** Generate two 1024 x 1152 chroma-key source images with the built-in image generator, remove the key locally into alpha PNG assets, then render those assets behind the existing code-drawn technical backdrop. QML continues to own crisp microtext, registration marks, contours, and color lines, while generated imagery supplies non-deterministic visual complexity.

**Tech Stack:** Qt 6 QML, CMake `qt_add_qml_module`, built-in image generation, Python/Pillow chroma-key helper, PowerShell verification.

## Global Constraints

- Preserve the clean modern-engineer retro-futurist direction.
- Final assets are exactly 1024 x 1152 alpha PNG files.
- Generated imagery contains no text, labels, logos, stars, paper texture, shadows, or colored accents.
- Runtime opacity remains within 0.035 to 0.070 for the megastructure and 0.025 to 0.055 for technical graphics.
- Keep settings, chat data flow, messages, character stage, composer, and backend behavior unchanged.
- Do not commit, stage, or discard existing worktree changes unless the user separately authorizes it.
- Because this is generated visual material and QML resource configuration, verification uses asset inspection, Qt compilation, and native screenshot comparison rather than adding behavioral unit tests.

---

### Task 1: Generate and Validate the Megastructure Asset

**Files:**
- Create: `qt-frontend/assets/ui/technical-overlays/megastructure-raster.png`
- Temporary: `tmp/imagegen/megastructure-raster-keyed.png`

**Interfaces:**
- Consumes: approved design source and the 1024 x 1152 right-pane slot.
- Produces: an alpha PNG whose visible subject is a right-biased image-derived megastructure.

- [ ] **Step 1: Establish the failing asset gate**

Run:

```powershell
$path = 'qt-frontend/assets/ui/technical-overlays/megastructure-raster.png'
if (-not (Test-Path $path)) { throw "Missing required asset: $path" }
```

Expected: FAIL with `Missing required asset`.

- [ ] **Step 2: Generate the chroma-key source with the built-in image generator**

Use this prompt verbatim:

```text
Use case: stylized-concept
Asset type: full-pane Qt background overlay, 1024 x 1152
Primary request: a single highly detailed orbital ring megastructure shown as image-derived square-cell rasterization, not a simple dot pattern
Scene/backdrop: perfectly flat solid #00ff00 chroma-key background with no texture or lighting variation
Subject: nested incomplete orbital rings, radial trusses, asymmetrical structural modules, deep construction layers, partial cropped arcs, recognizable engineering geometry
Style/medium: monochrome archival technical imaging sampled through coarse square pixels, ordered dither, halftone blocks, retro-futurist engineering scan
Composition/framing: portrait 1024 x 1152, visual center right of center, large structure extending beyond the right and lower edges, generous negative space through the left-center message area
Color palette: subject only in charcoal, warm black, and dark gray; do not use green in the subject
Constraints: one coherent source image visibly transformed into raster cells; complex geometry remains recognizable; crisp separated edges for background removal; no cast shadow
Avoid: text, letters, numerals, labels, logos, watermark, stars, nebula, paper, dirt, grain-only texture, isolated circles, regular polka dots, simple concentric-circle icon
```

- [ ] **Step 3: Copy the selected keyed result into the workspace temporary directory**

Run after the built-in tool returns its saved path:

```powershell
New-Item -ItemType Directory -Force tmp/imagegen | Out-Null
$generatedPath = Get-ChildItem -LiteralPath 'C:/Users/PC/.codex/generated_images' -Recurse -Filter '*.png' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
Copy-Item -LiteralPath $generatedPath -Destination 'tmp/imagegen/megastructure-raster-keyed.png'
```

Expected: the keyed PNG exists at the temporary path.

- [ ] **Step 4: Remove chroma key into the project asset**

Run:

```powershell
New-Item -ItemType Directory -Force qt-frontend/assets/ui/technical-overlays | Out-Null
python C:/Users/PC/.codex/skills/.system/imagegen/scripts/remove_chroma_key.py --input tmp/imagegen/megastructure-raster-keyed.png --out qt-frontend/assets/ui/technical-overlays/megastructure-raster.png --auto-key border --soft-matte --transparent-threshold 12 --opaque-threshold 220 --despill
```

Expected: helper exits 0 and writes an RGBA PNG.

- [ ] **Step 5: Inspect and validate the alpha asset**

Open `megastructure-raster.png` with the image viewer. Confirm transparent corners, no green fringe, no fake text, recognizable complex construction, and no simple-dot-only treatment. If a fringe remains, rerun the helper once with `--edge-contract 1`.

- [ ] **Step 6: Re-run the asset gate**

Run the Step 1 command again.

Expected: PASS.

### Task 2: Generate and Validate the Technical Graphics Asset

**Files:**
- Create: `qt-frontend/assets/ui/technical-overlays/technical-graphics-raster.png`
- Temporary: `tmp/imagegen/technical-graphics-raster-keyed.png`

**Interfaces:**
- Consumes: approved design source and the same full-pane slot as Task 1.
- Produces: an alpha PNG with sparse edge-weighted clusters of complex engineering forms.

- [ ] **Step 1: Establish the failing asset gate**

Run:

```powershell
$path = 'qt-frontend/assets/ui/technical-overlays/technical-graphics-raster.png'
if (-not (Test-Path $path)) { throw "Missing required asset: $path" }
```

Expected: FAIL with `Missing required asset`.

- [ ] **Step 2: Generate the chroma-key source with the built-in image generator**

Use this prompt verbatim:

```text
Use case: stylized-concept
Asset type: full-pane Qt background overlay, 1024 x 1152
Primary request: sparse clusters of complex abstract engineering imagery rendered as image-derived square-cell rasterization, not decorative dots or generic UI icons
Scene/backdrop: perfectly flat solid #00ff00 chroma-key background with no texture or lighting variation
Subject: cropped machinery cross-sections, partial sensor arrays, calibration structures, asymmetric orbital sections, antenna silhouettes, layered construction fragments, broken technical forms
Style/medium: monochrome archival systems imagery sampled through coarse square pixels, ordered dither, block halftone, restrained retro-futurist engineering scan
Composition/framing: portrait 1024 x 1152, three to five separated clusters placed near top-right, far right, bottom-right, and lower-left edges; leave the central message column mostly empty
Color palette: subject only in charcoal, warm black, and dark gray; do not use green in the subject
Constraints: every cluster must retain recognizable source geometry beneath the rasterization; varied scale and density; crisp separated edges for background removal
Avoid: text, letters, numerals, labels, logos, watermark, stars, paper, dirt, generic settings icons, simple circles, triangle-square-circle icon rows, regular polka dots, repeating dot wallpaper
```

- [ ] **Step 3: Copy and remove the chroma key**

Run:

```powershell
$generatedPath = Get-ChildItem -LiteralPath 'C:/Users/PC/.codex/generated_images' -Recurse -Filter '*.png' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
Copy-Item -LiteralPath $generatedPath -Destination 'tmp/imagegen/technical-graphics-raster-keyed.png'
python C:/Users/PC/.codex/skills/.system/imagegen/scripts/remove_chroma_key.py --input tmp/imagegen/technical-graphics-raster-keyed.png --out qt-frontend/assets/ui/technical-overlays/technical-graphics-raster.png --auto-key border --soft-matte --transparent-threshold 12 --opaque-threshold 220 --despill
```

Expected: helper exits 0 and writes an RGBA PNG.

- [ ] **Step 4: Inspect and validate the alpha asset**

Open `technical-graphics-raster.png`. Confirm transparent corners, no green fringe, no text, no icon row, varied complex clusters, and central negative space. Regenerate if the result collapses into simple dots.

- [ ] **Step 5: Re-run the asset gate**

Run the Step 1 command again.

Expected: PASS.

### Task 3: Register and Render the Generated Layers

**Files:**
- Modify: `qt-frontend/CMakeLists.txt`
- Modify: `qt-frontend/qml/TechnicalBackdrop.qml`

**Interfaces:**
- Consumes: the two alpha PNG assets from Tasks 1 and 2.
- Produces: two non-interactive background `Image` layers inside `TechnicalBackdrop`.

- [ ] **Step 1: Add resource registration before the closing parenthesis of `qt_add_qml_module`**

Insert:

```cmake
    RESOURCES
        assets/ui/technical-overlays/megastructure-raster.png
        assets/ui/technical-overlays/technical-graphics-raster.png
```

- [ ] **Step 2: Run Qt verification and confirm the current QML does not yet consume the assets**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt
rg -n "megastructure-raster|technical-graphics-raster" qt-frontend/qml/TechnicalBackdrop.qml
```

Expected: Qt build passes; `rg` finds no QML references and exits 1.

- [ ] **Step 3: Add both full-pane image layers before the existing `Canvas` in `TechnicalBackdrop.qml`**

Insert:

```qml
    Image {
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/megastructure-raster.png"
        fillMode: Image.PreserveAspectCrop
        horizontalAlignment: Image.AlignRight
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.055
    }

    Image {
        anchors.fill: parent
        source: "../assets/ui/technical-overlays/technical-graphics-raster.png"
        fillMode: Image.PreserveAspectCrop
        horizontalAlignment: Image.AlignHCenter
        verticalAlignment: Image.AlignVCenter
        smooth: true
        mipmap: true
        opacity: 0.040
    }
```

- [ ] **Step 4: Re-run the QML reference gate and full Qt build**

Run:

```powershell
rg -n "megastructure-raster|technical-graphics-raster" qt-frontend/qml/TechnicalBackdrop.qml
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt
```

Expected: `rg` reports both references and Qt verification exits 0.

### Task 4: Native Visual QA and Final Hygiene

**Files:**
- Modify: `design-qa.md`
- Create: a final screenshot and side-by-side comparison under the existing writable visualization directory.

**Interfaces:**
- Consumes: the built Qt application and approved visual source.
- Produces: native screenshot evidence, comparison evidence, and an updated QA result.

- [ ] **Step 1: Run the native Qt application at the reference viewport**

Launch `qt-frontend/build/qt-frontend/arklight_qt.exe`, dismiss only the runtime doctor overlay, and capture the full window at 1320 x 820 logical pixels.

Expected: generated structures are visible on close inspection while chat controls remain dominant.

- [ ] **Step 2: Compare reference, previous implementation, and new implementation**

Create a side-by-side comparison using the approved source, `qt-redesign-pass3-full.png`, and the new capture. Inspect overall density and focused right-background crops.

- [ ] **Step 3: Adjust only opacity or alignment if needed**

Allowed final tuning:
- Megastructure opacity: 0.035 to 0.070.
- Technical graphics opacity: 0.025 to 0.055.
- Horizontal alignment and shallow edge cropping.
- No regeneration unless content violates the asset constraints.

- [ ] **Step 4: Run final verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt
git diff --check
$matches = rg -n "\?{2,}" qt-frontend/qml qt-frontend/CMakeLists.txt design-qa.md
if ($LASTEXITCODE -eq 1) { exit 0 }
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$matches
```

Expected: Qt verification exits 0, `git diff --check` exits 0, and no encoding-damage matches are printed.

- [ ] **Step 5: Update `design-qa.md`**

Record the new screenshot, comparison paths, viewport, state, final opacity values, remaining source-asset limitations, and final result `passed` or `blocked` based on actual evidence.
