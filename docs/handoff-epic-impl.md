# Handoff: paused `/dev-workflow:epic-impl` for epics #2 / #3 / #4

An orchestration of WhenVivoMeetsGoogle phases 2–4 (epics #2, #3, #4) was
prepared on a host without the Android SDK and stopped at preflight. Pick
up the run on a host that has the SDK + ADB configured.

## What is already in place (on GitHub, no local checkout needed)

1. **Epic bodies restructured.** Issues #2, #3, #4 each now use explicit
   `### Phase N` sub-headings and `(depends on …)` annotations so the
   `/epic-impl` DAG parser produces the intended graph. Edits are visible
   on GitHub; nothing else was changed.
2. **Sub-issue audit complete.** All 24 sub-issues (#31–#54) were read and
   classified as implementation-ready — bodies have what + why, code
   outlines, and measurable acceptance criteria. No content edits needed.
3. **Non-default dependency edges encoded:**
   - `#36 → #32, #34, #35` — Phase 2 interop test gates the rest of epic #2.
   - `#34 → #33` only (not `#32`) — mDNS gating only needs the receiver scanner.
   - `#43 → #44` — resume support needs random-access writes.
   - `#42 → #39` — custom save location and folder receive both touch
     `FileDestinationFactory`.
   - `#54 → #49, #50, #51, #52, #53` — integration suite gates on every
     medium being implemented.

No branches, worktrees, commits, or PRs from `/epic-impl` itself were
created. Only the three epic bodies were edited.

## Why the previous run stopped

Android SDK is not installed on the host where the run started:

```text
ANDROID_HOME           = (unset)
ANDROID_SDK_ROOT       = (unset)
~/Android/Sdk          missing
local.properties       missing (gitignored, not synced)
sdkmanager / adb       not on PATH
JDK 21                 present (fine)
```

Without `sdk.dir`, the Android Gradle plugin fails at configure time, so
`./gradlew :app:assembleDebug`, `:app:testDebugUnitTest`, and
`./gradlew staticAnalysis` all fail. Only `:core-protocol:test` (pure JVM)
runs.

Phase impact on the previous host:

| Epic | Android-touching | Pure-JVM-only | Locally runnable |
|---|---|---|---|
| #2 (BLE) | #31–#36 | — | 0 / 6 |
| #3 (gaps) | #38, #39, #41, #42, #46, #47 | #37, #40, #44, #45 | 4 / 11 |
| #4 (mediums) | #48–#54 | — | 0 / 7 |

## Preflight on the new machine

Run these in order. Each must succeed before kickoff.

```bash
# 1. Confirm tooling
echo "$ANDROID_HOME"          # must print a path
sdkmanager --list_installed | head
adb version
java -version                  # JDK 17 or newer
gh auth status                 # gh CLI authenticated

# 2. Write local.properties (gitignored)
cd /path/to/WhenVivoMeetsGoogle
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Smoke-test the build (must all pass on origin/main)
./gradlew :core-protocol:test
./gradlew :app:assembleDebug
./gradlew staticAnalysis

# 4. Repo state
git fetch origin
git checkout main && git pull --ff-only origin main
git status                                                 # clean
git worktree list                                          # only primary tree
git branch --list 'feature/issue-3*-*' 'feature/issue-4*-*' 'feature/issue-5*-*'   # must be empty
```

If `staticAnalysis` lints fail on a clean `main` they were never run on
the previous host — investigate before proceeding.

## Resume sequence

```text
/dev-workflow:epic-impl 2
#   pause for human review of merged PRs, then
/dev-workflow:epic-impl 3 --max-parallel 4
#   pause for human review, then
/dev-workflow:epic-impl 4
```

The user explicitly wants pauses between epics. Do not chain them.

`--max-parallel 4` on epic #3 is intentional: its first level has 9
independent sub-issues; 9 concurrent Gradle daemons will thrash one box.
Phases 2 and 4 never exceed 5 parallel units — leave the default in place.

## Per-epic level summary (already encoded in the issue bodies)

**Epic #2 — BLE auto-discovery (4 levels):**
- L0: #31
- L1: #32 ‖ #33 (both gated on #31)
- L2: #34 ‖ #35 (both gated on #33)
- L3: #36 (gated on #32, #34, #35)

**Epic #3 — Protocol gaps (2 levels after chain extraction):**
- L0 super-nodes (all parallel): #37, #38, #40, #41, #45, #46, #47, plus
  chains `[#44 → #43]` and `[#39 → #42]`
- L1: empty after chain collapse — the two chains carry their tail issues
  internally

**Epic #4 — Alternative bandwidth-upgrade mediums (3 levels):**
- L0: #48
- L1: #49 ‖ #50 ‖ #51 ‖ #52 ‖ #53
- L2: #54 (gated on all of L1)

## Soft notes for the operator (not encoded as edges)

- #39 (folder receive), #41 (preserve mtime), and #44 (random-access
  writes) all touch `FileDestinationFactory`. Sequential merges in those
  areas will require rebases; `merge-resolver` should handle routine
  conflicts but may need supervision.
- #38 (folder send) and #39 (folder receive) can each be tested against
  stock Quick Share alone, so they are not edge-linked, but the cleanest
  round-trip test is to merge #39 first and validate #38 against it.
- Project branch convention is `feature/issue-<N>-<slug>` (per
  `CLAUDE.md`). The `feat/epic-<E>-issue-<N>-…` pattern in
  `/epic-impl`'s failure-policy examples does not match this repo. If
  the 3-strike retry path triggers, adjust the cleanup grep accordingly.
- All commits, PR titles, PR bodies, and issue comments are English-only
  with no AI attribution lines.

## File pointers

- This handoff: `docs/handoff-epic-impl.md`
- Project conventions: `CLAUDE.md` (repo root)
- Slash-command specs (host-local plugins cache):
  - `~/.claude/plugins/marketplaces/lablup-marketplace/plugins/dev-workflow/commands/epic-impl.md`
  - `~/.claude/plugins/marketplaces/lablup-marketplace/plugins/dev-workflow/commands/impl.md`
  - `~/.claude/plugins/marketplaces/lablup-marketplace/plugins/dev-workflow/commands/chain-impl.md`
