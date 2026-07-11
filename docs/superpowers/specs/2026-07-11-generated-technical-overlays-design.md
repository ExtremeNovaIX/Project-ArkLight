# Generated Technical Overlay Design

Date: 2026-07-11
Status: Approved direction, pending written-spec review

## Goal

Restore the missing image-derived rasterized megastructure and complex technical graphics in the Qt chat background. The result must add information density without making the warm cream surface busy, dirty, or less readable.

## Visual Direction

The interface remains a clean retro-futurist system designed by a modern engineer. Generated imagery is treated as archival technical imaging that has been sampled into coarse square cells. It must appear discovered inside the negative space, not presented as foreground illustration.

## Asset Architecture

Create two independent 1024 x 1152 full-pane raster assets, matching the right chat surface at a 0.889 width-to-height ratio.

### 1. Megastructure layer

- Subject: one large orbital or ring megastructure with nested construction, radial supports, incomplete arcs, and asymmetrical engineering detail.
- Rendering: monochrome charcoal geometry converted into image-derived square-cell rasterization and ordered dither.
- Composition: the visual center sits right of center, with generous negative space around message content and the composer.
- No text, labels, logos, stars, paper texture, shadows, or colored accents.
- Intended runtime opacity: approximately 0.035 to 0.070.

### 2. Technical graphics layer

- Subject: sparse clusters of complex abstract engineering diagrams, section markers, sensor silhouettes, partial rings, calibration shapes, and cropped equipment-like forms.
- Rendering: the same square-cell rasterization and ordered dither language as the megastructure.
- Composition: separated clusters near peripheral areas, avoiding a repeated dot pattern and avoiding the main text column.
- No text, labels, logos, generic UI icons, paper texture, or colored accents.
- Intended runtime opacity: approximately 0.025 to 0.055.

Both generated sources use a uniform removable chroma-key background. After local key removal, the final project assets are alpha PNG files. Chroma-key intermediates remain outside the project and are removed after the alpha assets pass validation; the Qt application consumes only the alpha PNG files.

## Qt Integration

- Store final assets under `qt-frontend/assets/ui/technical-overlays/`.
- Register the assets in `qt-frontend/CMakeLists.txt`.
- Add image placement to `TechnicalBackdrop.qml`, behind chat content and existing QML labels.
- Use the assets at their designed full-pane ratio, with centered or explicitly anchored placement rather than extracting arbitrary sprite crops.
- Preserve the existing code-rendered cream hatch, contour marks, registration crosses, scale ticks, microtext, and red-yellow-cyan lines.
- Keep generated layers non-interactive and outside accessibility focus order.
- Keep the settings control, message list, and composer behavior unchanged.

## Responsive Behavior

At the current 1320 x 820 logical reference viewport, both overlays use the full right pane. At other desktop window sizes, preserve aspect ratio and allow only shallow peripheral cropping. The megastructure anchor remains biased toward the right side; the technical graphics layer remains edge-weighted. If the available right pane becomes narrow, reduce opacity before reducing message width.

## Failure Handling

- If chroma-key removal leaves visible color fringe, retry local removal once with a one-pixel edge contraction.
- If generated content contains fake text or recognizable logos, reject and regenerate instead of hiding it with opacity.
- If an asset becomes a visible focal point at normal viewing distance, lower opacity or mask its central region.
- If the image generator produces simple dots rather than image-derived rasterization, reject it and strengthen the prompt around square-cell sampling, ordered dither, and recognizable source geometry.

## Verification

1. Inspect each generated asset before integration for complexity, clean alpha, no text, no logos, and no key-color fringe.
2. Build with `powershell -ExecutionPolicy Bypass -File scripts/verify.ps1 -Scope qt`.
3. Capture the native Qt window at the 1320 x 820 logical reference viewport.
4. Compare the new capture with the approved mockup and the previous implementation side by side.
5. Confirm that the megastructure and technical graphics are visible on close inspection but remain subordinate to chat text and controls.
6. Confirm that settings, input, send, message scrolling, and character-stage behavior are unchanged.

## Scope

This pass changes only generated background assets, QML background composition, resource registration, and visual QA evidence. It does not change chat data flow, backend APIs, character assets, settings behavior, or composer behavior.
