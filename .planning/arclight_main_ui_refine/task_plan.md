# Task plan: ArcLight main UI refinement

## Goal
Restore the earlier boot overlay and refine the main Qt/QML interface so decorative layers avoid content, the scrollbar sits near the outer edge, the clock reads as a mechanical jump counter, ambient light points flow subtly, and focus feedback uses glow rather than geometric motion.

## Phases
- [complete] Inspect current QML, visual references, dirty worktree, and preview/test tooling.
- [complete] Add failing source/render checks for the requested layout and interaction behavior.
- [complete] Implement layout zoning, ambient light flow, mechanical counter clock, edge scrollbar, and glow focus.
- [complete] Render and inspect representative viewport/scale states.
- [complete] Run Qt verification and review the final diff.

## Scope
Expected files: qt-frontend/qml/ChatSurface.qml, TechnicalBackdrop.qml, CharacterStage.qml, SettingsCategoryGlyph.qml, SettingsNavButton.qml, plus focused tests or preview tooling if present.
BootOverlay.qml should match the committed earlier version and should not be changed if already clean.

## Risks
- QML Canvas animation can consume CPU if the repaint cadence is too high.
- Decorative content can re-overlap at minimum size or non-100% scale.
- Font metrics can make a counter-style clock jitter unless each digit has fixed geometry.
- Glow must remain visible without increasing the overall contrast of the cream interface.

## Errors
- CodeGraph did not index the QML directory for this query, so literal file reads are used for QML.
- design skill durable-context reference is missing from the local installation.

## Revision: mechanical split-flap clock
- [complete] Add a failing source contract for a fast 3D split-flap transition and texture asset.
- [complete] Generate and integrate a low-contrast industrial dial texture.
- [complete] Implement and render the fast flip transition at representative scales.
- [in_progress] Verify, review, and stage the two newly tracked QML files.