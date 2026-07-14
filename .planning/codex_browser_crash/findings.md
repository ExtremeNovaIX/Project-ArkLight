# Findings

## Reported symptom
Opening Codex's built-in browser causes Codex itself to crash.

## Evidence
- Pending.
- Installed Codex executable resolves to C:\Program Files\WindowsApps\OpenAI.Codex_26.707.8168.0_x64__2p2nqsd0c76g0\app\resources\codex.exe.
- The package version encoded in the path is 26.707.8168.0.- Codex desktop is currently running as PID 7996. The package includes a Chromium 150.0.7871.115 runtime (chrome.dll) and a code-mode host.
- Windows Application log returned no Codex/OpenAI crash event in the last 14 days.
- Many Edge/WebView2 processes are active, but passive process listing does not yet establish which belong to Codex.- Official Codex manual helper could not start because the sandbox denied Node realpath access to C:\Users\PC; local evidence remains the primary source.- Active config enables browser@openai-bundled and declares BROWSER_USE_AVAILABLE_BACKENDS=chrome,iab.
- Active browser runtime metadata reports BROWSER_USE_CODEX_APP_VERSION=26.707.61608 and the browser plugin cache uses the same version, while the installed MSIX package is 26.707.8168.0. This is a candidate version-skew condition, not yet confirmed as causal.
- Codex desktop text logs exist under C:\Users\PC\AppData\Local\Codex\Logs and are the next primary evidence source.- Version-skew hypothesis discarded: the same reported IAB release completed backend startup repeatedly in a multi-hour app session, and logs show successful browser-use host creation.
- Passive log comparison shows IAB backend startup occurs in both short and long-lived app sessions, so backend startup alone does not explain app exits.
- No Crashpad/Windows dump was found.
- Next discriminator: IAB binding/documentation handshake, then about:blank tab creation if the app survives.- Controlled reproduction stage 1 passed: the browser runtime connected and returned complete IAB documentation without app exit.
- Controlled reproduction stage 2 passed: a real about:blank IAB tab was created and its URL/title/DOM were read without app exit.
- Remaining discriminator: make the browser panel visible. If that fails, the fault is in visible panel composition rather than browser backend or WebView host creation.- Confirmed root cause hypothesis: persisted tab browser-use:a992b22b-d3c8-403c-a3f4-5e990c47d471 for thread 019f4f54-b76a-7ab0-8441-3878774828c5 hit mcp_app_sandbox.attach_unmatched with url=undefined; the app log ended immediately after that guest reached dom-ready, and a fresh app session began 13 seconds later.
- Independent control: a new tab on the same app build attached without attach_unmatched and the app remained alive.
- Persistent-state check: the exact failed tab ID is still stored in electron-persisted-atom-state, while iab.user.openTabs() reports no live user tab.- Hypothesis 1 DISCARDED: removing the stale persisted browser tab did not eliminate mcp_app_sandbox.attach_unmatched. A fresh post-repair tab logged the same warning but Codex remained alive, proving the warning/state entry is not a sufficient crash cause.
- The state repair is reversible via C:\Users\PC\.codex\.codex-global-state.json.pre-browser-repair-20260713-202549.bak.- Exact historical crash call recovered from the thread rollout: plugin load/documentation and iab.tabs.list() completed; the next call ran globalThis.tab = await iab.tabs.new(); await tab.goto(http://127.0.0.1:8765/production); domSnapshot(), and never produced a tool result.
- The Codex main log ended during that call immediately after the new about:blank guest became dom-ready; a new app session began 13 seconds later.
- Therefore blank tab creation and plugin version skew are ruled out. The remaining boundary is navigation/rendering of the localhost production page after a fresh IAB tab.
## Success/failure control (2026-07-13)
- Same target URL succeeded at 07:04:25 in a visible `right-panel` tab whose `initialUrl` was already `/production`; DOM ready completed and Codex stayed alive.
- The 09:08:49 failure used `hidden-browser-use`: create `about:blank`, then call `goto(/production)` immediately in the same Node REPL evaluation. The process ended after blank DOM-ready, before any navigation result.
- Therefore the target page, Chromium/GPU, and `attach_unmatched` warning are not sufficient causes. The strongest remaining hypothesis is an IAB route/tab lifecycle race during blank-tab creation plus immediate navigation.
- At 09:08:01 the same browser-use session route was transiently unavailable (fail/succeed/fail getInfo), strengthening the lifecycle/routing hypothesis.
## Applied local hotfix
- Patched exactly one occurrence in browser-client.mjs: `tabs.new()` now waits 500 ms after createTab returns and before exposing the new Tab to callers.
- The change is an insertion only (40 bytes); the original backup hash is F8B28403A0497B2E0BED024969FA474BB1F564509A59993449924C4ECAB9DB0.
- Node `--check` passes. Current Codex PID 19280 remains alive and responding.
- Full regression execution requires a Codex restart because the current Node REPL already imported the old module, and browser skill cleanup forbids more browser actions after finalize in this turn.
## Restart result
- Codex restart regenerated `browser-client.mjs`: guard count returned to 0 and SHA-256 returned exactly to the original F8B28403A0497B2E0BED024969FA474BB1F564509A59993449924C4ECAB9DB0.
- Therefore the cache-file edit is not durable across restart. Runtime verification must reapply it before Browser import, then a durable upstream/workflow guard must be found.
- The target page still returns HTTP 200 after restart.
## Restarted-session runtime stage 1
- Reapplied the guard before the first Browser module import; SHA-256 D79470B38443AFE0D0718E039DD96B26C021CC65030EE7186183D17DB6A93264 and Node syntax check passed.
- The fresh Browser runtime selected IAB and returned its complete documentation without app exit.
- Next check is the exact historical sequence: `tabs.new()` -> immediate `goto(/production)` -> DOM snapshot in one evaluation.
## Restarted-session runtime failure
- The exact one-evaluation path (`tabs.new()` -> immediate `goto(/production)` -> DOM snapshot) still caused the Codex app to terminate with the 500 ms guard loaded.
- A new Codex main process/session (PID 29364) started immediately afterward; current process is responding.
- This disproves a short post-create timing window as the root cause. Do not increase the timeout or stack another timing patch.
## Corrected fault boundary
- The failing app log ends at 12:36:38.632 with the hidden tab's `about:blank` DOM-ready event.
- `createTab` mapped at 12:36:38.600, but the patched client could not return until 500 ms later. The process exited only 32 ms later, so the delay never ran to completion and `goto()` was never sent.
- Root boundary is therefore hidden IAB tab creation/host completion itself, not navigation or target content.
- Browser plugin uninstall/reinstall reconciliation also occurred in sessions that later created tabs successfully, so it is not a sufficient cause.
## Healthy/failing hidden-tab comparison
- Healthy and failing runs match through hidden WebView creation, unmatched sandbox attach, runtime attach, page mapping, and `about:blank` DOM-ready.
- Healthy run continues to debugger-listener registration and later tool output; failing run ends exactly at DOM-ready with no Electron error or JS exception.
- Focus is not sufficient: healthy runs exist both focused and unfocused. `attach_unmatched` and startup Browser uninstall/reinstall also occur in healthy runs.
- The remaining failure is inside Codex's closed-source Electron/main-process post-DOM-ready path. The browser client never regains control, so a client-side delay cannot repair it.
- Multiple falsified hypotheses reached the Hunt stop condition: stale persisted tab, app/plugin version skew, client timing delay, and plugin reconciliation as sufficient cause.
## Handoff boundary
- Fresh verification shows the Browser client is back to the exact original SHA-256 and contains no timing guard; plugin startup also removed the temporary backup files.
- Current Codex process set is alive/responding after automatic restart.
- Official Codex manual helper was unavailable because approval review timed out; Docs MCP is not installed; official developer-domain search did not surface a documented recovery for this native IAB crash.
- The next meaningful experiment is a clean removal/reinstallation of only the bundled Browser plugin cache while Codex is closed. This is a broader destructive local-state action and requires explicit user approval.
## First clean-reinstall helper result
- The helper exited with status helper_failed before the move boundary because PowerShell 5.1 rejected Measure-Object -Property length on the generated inventory shape.
- The original Browser cache is still present and unchanged in aggregate: 374 files, 7,819,719 bytes.
- No backup browser directory, manifest, or verification record was created; retry requires correcting and sandbox-testing the helper, then another full Codex exit.
## Helper integration environment boundary
- The source-inventory bug has a minimal loop-sum fix and a proven RED case.
- Workspace ACL/policy allows copying test files but denies directory rename/move through both Move-Item and System.IO.Directory.Move, even when the command request is escalated.
- This prevents a meaningful GREEN move test inside E:\PersonalProject\Project-1; the same exact test must use an isolated tree under the authorized C:\Users\PC\.codex\backups root.
## Corrected helper verification
- RED: exact integration test failed on the original inventory sum with helper_failed / GenericMeasurePropertyNotFound.
- GREEN: replacing the two Measure-Object sums with an explicit Int64 loop made the same production helper complete a real directory move and pre/post SHA-256 verification under the authorized C: backup root.
- Test result: backup_complete, verified=true, 3 files, 14 bytes, no failures.
## Real clean reinstall result
- Backup path: C:\Users\PC\.codex\backups\browser-clean-reinstall-20260713-210801-pid18200\browser.
- Helper and independent verifier agree on 374 files, 7,819,719 bytes, and zero mismatches. Manifest SHA-256: 1D5142BEBC650E9D7F50778022E90EC9E1988EC0DA36AB744E74EFA00DCE8318.
- The post-restart app log records bundled_plugin_install_requested with reason=missing followed by plugin_install_succeeded.
- The rebuilt cache exists at the original path with version 26.707.61608 and exact original browser-client SHA-256 F8B28403A0497B2E0BED024969FA474BB1F564509A59993449924C4ECAB9DB0D.
## First post-reinstall runtime check
- A fresh hidden Browser Use tab successfully crossed the historical crash boundary and returned id=1, url=about:blank, title=New tab.
- The original post-restart main PID 23332 remained alive/responding, with no new main log/session.
- This is positive runtime evidence but not yet proof of permanence because the old installation also had intermittent healthy tab creations.
## Post-reinstall stability check
- Three consecutive hidden IAB Browser Use tabs crossed the previous post-DOM-ready crash boundary and returned normally.
- Each test tab was closed. App-log counts match exactly: opened=3, dom-ready=3, closed=3.
- Codex remained in the same main process (PID 23332) and responsive across all three cycles.
## Final outcome
- Status: resolved with caveats.
- Confirmed boundary: Codex's closed-source IAB host-completion path immediately after about:blank dom-ready. Page content, goto, client delay, stale tab state, attach_unmatched alone, and plugin version skew were ruled out as sufficient causes.
- Remediation: atomically moved the Browser plugin cache into a hash-verified backup, then let startup reinstall the bundled Browser plugin from missing state.
- Confirmation: 3/3 fresh hidden-tab create-close cycles passed without PID change; app log contains matching opened/dom-ready/closed lifecycle counts and no IAB failure or fatal entry.
- Caveat: old sessions also had healthy controls, so the reinstall's causal mechanism cannot be proven from three passes. If the crash recurs, the remaining defect is upstream in Codex's closed-source Electron lifecycle code and should be reported with the preserved failing/healthy logs and backup manifest.
## 2026-07-14 recurrence
- This recurrence is not the same logged boundary as the prior post-dom-ready crash. The session ended five seconds after an IAB owner-route rebind, with no createTab/dom-ready evidence.
- A dense burst of renderer errors reported item events for unknown conversations and missing conversation state for two concurrent agents immediately before process exit.
- New testable hypothesis: the Codex renderer conversation/route registry becomes inconsistent under concurrent active agents, and selecting/invoking Browser forces a thread route transition that reaches the inconsistent registry before IAB creation.
- Alternative hypothesis: the apparent crash may have coincided with an automatic Codex package update. The updater launched immediately after session termination and observed a newer Store manifest version. Current installed package version is the next passive discriminator.
- The renderer's unknown-conversation flood is persistent in healthy operation and therefore not independently causal. The stronger discriminator is now the exact Browser tool call record and whether it returned before the app-level exit.
## 2026-07-14 passive recurrence evidence, continued
- Selective parsing of `rollout-2026-07-14T09-57-57-019f5e58-49b5-71f3-be8c-7ca7de3182fc.jsonl` shows the 02:10:55 task was an approval-review assessment for another agent's requested PowerShell file edit, not a Browser request.
- Exact sequence: task_started at 02:10:55.933Z, approval-review transcript/user payload at 02:10:55.959Z, synthetic `<turn_aborted>` message at 02:10:55.974Z, and turn_aborted(reason=interrupted) at 02:10:55.984Z.
- Therefore the earlier correlation `immediate abort -> BrowserUseThreadConfig` is not evidence that this rollout initiated Browser. The Browser runtime selection at 02:10:57.199Z belongs to another UI/thread/runtime path that did not persist a Browser tool call in the inspected sessions.
- Windows Application log query for 10:10:30-10:13:00 returned no Application Error, Windows Error Reporting, AppModel-Runtime, Application Hang, ChatGPT, Codex, or OpenAI event.
- `Get-CimInstance Win32_Process` was denied by WMI permissions; switched to non-WMI `Get-Process`. Current accessible Codex process family started at 10:11:59-10:12:01. PIDs 14632 and 4336 are no longer present, so there is no live PID-reuse ambiguity for those IDs.
## 2026-07-14 provider and persisted-tab evidence
- Official Codex manual was fetched through the command-line helper, without using the in-app browser. It documents that the built-in browser uses a separate profile and exposes Browser data settings, but contains no documented recovery for an Electron/IAB process exit, provider/session mismatch, or orphaned Browser tab lifecycle state.
- Cockpit Tools v1.3.0 release notes explicitly fix startup behavior that took over or restored Codex profiles and rewrote `config.toml` and `auth.json`. Writes are now limited to explicit service/account/binding/instance actions. This directly matches the local Cockpit 1.1.4 provider/profile rewrite observed after the recurrence.
- Current Codex config still selects `model_provider = "codex_local_access"` with `base_url = "http://localhost:57485/v1"`; passive provider/session comparison remains in progress.
- No Browser or Node Browser runtime was invoked during this phase.
## 2026-07-14 confirmed provider-repair sequence
- The pre-repair config generation created at 10:11:33 already selected `codex_local_access` at `http://localhost:57485/v1`.
- The Cockpit repair backup was created at 10:11:33-10:11:34 with `targetProvider = codex_local_access`, 201 rollout entries, and `state_5.sqlite`.
- All 173 active-session JSONL files present in the backup had `model_provider = openai`; every corresponding live file now has `model_provider = codex_local_access`. The three threads directly relevant to the recurrence show the same openai-to-local rewrite.
- Cockpit Tools 1.1.4 contains the exact implementation strings for `.provider-repair`, `SELECT DISTINCT model_provider FROM threads`, `backup-<timestamp>-session-visibility-repair`, rollout files, and SQLite backup. This attributes the backup and provider rewrite to Cockpit rather than Codex.
- Cockpit's sidecar process started at 10:11:39, after the failed Codex session and immediately before `auth.json`, `.cockpit_codex_auth.json`, and the next config generations were written.
- The strongest current trigger hypothesis is a provider split-brain: Cockpit had set the active Codex provider to `codex_local_access` while persisted sessions still declared `openai`; a Browser sidebar owner-route transition reached Codex's inconsistent conversation/session registry before any IAB tab was created.
## 2026-07-14 orphan Browser tab sibling defect
- Global state still contains active tab `browser-use:299112e2-3add-4ce4-b2f6-59df31246d16` while `rightPanelOpen=false`.
- The tab was originally created as `isBrowserUseTab=true` in the historical 12:36:38 DOM-ready crash, but every later startup restores it as `kind=browser`, `isBrowserUseTab=false`, `appBrowserUseTabCount=0`.
- Current logs repeatedly owner-sync this tab during route changes and contain no normal `action=closed` for it. It is therefore live lifecycle input, not inert serialized debris.
- This orphan is a confirmed sibling defect but is not sufficient by itself to explain the 10:11 recurrence, which switched between other thread IDs.
## 2026-07-14 upstream corroboration
- OpenAI issue #27349 reports the Codex Desktop main process crashing when Browser opens in one thread while several non-Browser threads remain active. This matches the current multi-agent condition and confirms an upstream IAB lifecycle failure class independent of Cockpit.
- OpenAI issue #25094 reports Windows freezes when switching into a Browser-associated thread because IAB/sidebar startup and stale streaming/session state are reconciled during the thread switch.
- OpenAI issue #32040 reports Windows Codex 26.707-series closing after Browser lifecycle failure. It is not the same exact final log line as this recurrence, but confirms the release family still contains whole-app Browser failure paths.
- No public issue found names Cockpit provider mismatch as the direct Browser trigger. The local provider split-brain is therefore the strongest environment-specific trigger layered on an acknowledged Codex sidebar/session lifecycle defect.
## Root-cause statement before repair
- I believe the recurrence is an upstream Codex Browser sidebar/session-route lifecycle crash triggered by Cockpit 1.1.4 leaving the active profile on `codex_local_access` while persisted threads still declared `openai`: the app exits during owner-route rebinding before tab creation, the 10:11:33 backup proves the provider split, Cockpit immediately repairs exactly that split, and OpenAI has open reports for Browser crashes under concurrent-thread and thread-switch lifecycle pressure.
