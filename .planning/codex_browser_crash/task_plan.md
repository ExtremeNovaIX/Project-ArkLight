# Task Plan: Codex in-app browser crash

## Goal
Identify the exact cause of Codex crashing when the in-app browser opens, apply the smallest reversible fix, and verify with runtime evidence.

## Phases
- [complete] Collect the historical post-DOM-ready crash evidence and falsify stale-tab, version-skew, target-page, and client-delay hypotheses.
- [complete] Perform a hash-verified Browser plugin cache reinstall and record the temporary 3/3 pass.
- [complete] Analyze the recurrence passively and distinguish the pre-tab route-rebind failure from the historical post-DOM-ready failure.
- [complete] Confirm the Cockpit provider split-brain and the persistent orphan Browser tab as separate state defects.
- [in_progress] Keep the live multi-agent Codex process untouched and prepare a reversible two-stage repair.
- [pending] After other agents finish, back up current Codex/Cockpit state, upgrade Cockpit 1.1.4 to 1.3.0, and remove only the confirmed orphan Browser tab entry.
- [pending] Restart once and run the minimum Browser runtime verification with PID/log checks.

## Guardrails
- Do not call Browser, Node browser runtime, restart Codex, or mutate global/plugin state while other agents are running.
- Do not launch the in-app browser until passive evidence has been inspected.
- Do not change project source code unless evidence points there.
- Do not inspect browser cookies, local storage, passwords, or session stores.
- Stop and reconfirm if the fix requires more than five files or destructive cleanup.

## Errors Encountered
- rg found no planning-related ignore rule (exit 1); no retry needed.| Packaged codex.exe access denied | 1 | Treat package path as evidence only; inspect app/event logs instead of retrying execution. || PowerShell parser error after foreach block | 1 | Remove invalid pipeline after the statement block and query directories separately. || Codex manual helper EPERM on C:\Users\PC | 1 | Continue with local evidence; use only official web fallback if product documentation becomes material. |
| Win32_Process query returned exit 1 without output | 1 | Use Get-Process and application-owned logs; do not retry the blocked CIM path. || Post-repair regression check reproduced attach_unmatched | 1 | Discarded stale-tab/attach_unmatched as sufficient root cause; inspect exact tool event and OS events. |
| data: probe URL blocked by browser policy | 1 | Did not bypass; verified the original about:blank path instead. || System/diagnostic event queries returned no matching events | 1 | Treat as negative evidence: no GPU/resource/WER event in the crash window. |
| WER archive foreach pipeline parser error | 1 | Do not retry the malformed form; existing WER and event checks already cover the crash window. |
| Node syntax check could not access C:\Users\PC inside sandbox | 1 | Re-ran the identical read-only check with required escalation; it passed. |

| Restart restored browser-client.mjs to the original hash | 1 | Do not test yet; reapply before first import, then locate the cache source for durability. |
| Get-Process StartTime was access-denied for some ChatGPT child processes | 1 | Use PID presence/responding plus the newest Codex log; no retry needed. |

| Original crash reproduced with the 500 ms guard | 1 | Discard timing-delay hypothesis; inspect the pre-crash app log before any further fix. |

| Official Codex manual helper failed in sandbox, then escalation review timed out | 1 | Follow the openai-docs route to Docs MCP; do not repeat the same approval request. |

| Original-hash comparison used a one-character-short expected string | 1 | Corrected the expected SHA-256 and reran; file is restored exactly. |
| Direct test Move-Item diagnostic used an invalid pipeline after try/catch | 1 | Stored the try/catch result first, then serialized it; do not repeat the malformed form. |
| Sandboxed integration test denied directory Move-Item inside the synthetic profile | 1 | Re-run the exact isolated test with escalation; production helper also requires unsandboxed access to the real Codex cache. |
| rg secondary filter did not match Browser plugin instruction paths on Windows | 1 | Used exact Get-ChildItem enumeration; located skills\\control-in-app-browser\\SKILL.md. |
| Combined final verification exceeded 60 seconds | 1 | Split hash/process/log verification from git status; do not repeat the combined command. |
| Full live target-vs-backup SHA-256 comparison timed out | 2 | Stop retrying while the plugin is active; use verified backup, install log, representative client hash, lifecycle counts, and process state for completion. |
| Recent-log inventory serialized PowerShell extended properties and truncated output | 1 | Cast first/last log lines to plain strings and emit only compact timestamps/metadata. |
| Multi-pass Get-Content session metrics timed out at 30 seconds | 1 | Use single-pass Select-String queries and compact counts; do not rescan full logs through multiple PowerShell pipelines. |
| Codex manual helper hit sandbox EPERM on `C:\Users\PC` | 1 | Re-ran the same read-only helper with scoped escalation; it succeeded and wrote only its temp cache. |
| PowerShell `$home` assignment collided with read-only `$HOME` | 1 | Renamed the local variable to `$codexRoot`; no state was changed. |
| Provider/backup query printed complete session-meta lines and truncated output | 1 | Parse only the first JSONL record and emit selected metadata fields; do not print session instructions. |
| PowerShell `foreach` output was piped directly and produced `EmptyPipeElement` | 2 | Accumulate objects in an array before piping to `Format-List`; the compact retry succeeded. |
| Home-directory enumeration was denied | 1 | Stop broad home enumeration; all required Cockpit paths are already known and targeted reads succeed. |
| Exact phase-block replacement did not match file newline shape | 1 | No write occurred; switched to a bounded regex replacement. |
| PowerShell here-string header was malformed in the bounded replacement command | 1 | No write occurred; used string arrays joined with the platform newline instead. |
