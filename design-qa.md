# Design QA

## Evidence

- Source visual truth: `C:\Users\PC\.codex\generated_images\019f4bca-6f56-79b0-91a1-3e03bf2153be\exec-e59bc1ed-541e-4aac-91c6-91472cd03400.png`
- Implementation screenshot: `C:\Users\PC\.codex\visualizations\2026\07\10\019f4bca-6f56-79b0-91a1-3e03bf2153be\qt-generated-overlays-pass3-left-visible.png`
- Viewport: 1320 x 820 logical pixels, captured at 2002 x 1286 physical pixels with DPI-aware window bounds
- State: native Qt application, runtime doctor dialog dismissed, empty chat session
- Full-view comparison: `C:\Users\PC\.codex\visualizations\2026\07\10\019f4bca-6f56-79b0-91a1-3e03bf2153be\design-compare-generated-overlays-pass3.png`
- Previous focused controls comparison: `C:\Users\PC\.codex\visualizations\2026\07\10\019f4bca-6f56-79b0-91a1-3e03bf2153be\design-compare-controls-pass3.png`
- Generated megastructure asset: `qt-frontend/assets/ui/technical-overlays/megastructure-raster.png`, runtime opacity `0.032`
- Generated technical graphics asset: `qt-frontend/assets/ui/technical-overlays/technical-graphics-raster.png`, right runtime opacity `0.022`, left runtime opacity `0.10` with pale blue-gray colorization

## Findings

| Severity | Area | Finding | Disposition |
| --- | --- | --- | --- |
| P0 | Overall | No blocking layout, rendering, or interaction defect found. | Passed |
| P1 | Overall | No major visual mismatch affecting the approved composition or hierarchy. | Passed |
| P2 | Header and composer | Settings returned to a compact warm-white plastic key with orange outline. The normal chat input remains fully inside the right column and the send control is visually restrained. | Passed |
| P2 | Background | The cream surface keeps the image-derived square-cell megastructure and edge-weighted technical imagery at reduced opacities `0.032` and `0.022`. They read as hidden archival information and remain below chat content. | Passed |
| P2 | Character stage | The left stage now reuses the generated mechanical sections, sensor arrays, and engineering structures as a pale blue-gray `0.10` layer behind the character. Edge clusters are visible in the negative space while the character remains dominant. | Passed |
| P3 | Character crop | The available runtime character image is a square bust, so the native crop is closer than the taller half-body figure in the design source. | Accepted source-asset constraint |
| P3 | Populated message state | The final native screenshot is an empty runtime session. Message delegates compile successfully and chat behavior is unchanged, but a separate populated-state screenshot was not captured because the standalone QML preview could not be completed within the available GUI runner constraints. | Residual visual test gap |
| P3 | Generated color | The source imagery carries a faint warm olive cast after chroma-key removal. At the approved low runtime opacities it reads as cream-adjacent archival imaging rather than a new accent color. | Accepted |

## Comparison history

1. Pass 1 exposed the runtime doctor overlay and was not used as final evidence.
2. Pass 2 captured the full native window after dismissing the overlay and established the correct split, palette, and controls.
3. Pass 3 reduced the technical raster contrast, softened the hidden PIONEER layer, enlarged the character stage treatment, and produced the previous full-view and focused-control comparisons.
4. Generated-overlay pass 1 replaced the missing code-only impression with two alpha-backed image layers.
5. Generated-overlay pass 3 reduced right-side opacity from `0.055 / 0.040` to `0.032 / 0.022` and reused the technical graphics on the left at `0.10` with pale blue-gray runtime colorization. Direct window capture confirmed lower right-side contrast and visible left-side edge structures.

## Final result

passed
