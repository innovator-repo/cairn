# CLAUDE.md - cairn

## gstack

In Claude Code cloud sessions, `.claude/hooks/install-gstack.sh` installs
[gstack](https://github.com/garrytan/gstack) at session start (pinned commit,
browser download skipped). Its skills then live in `~/.claude/skills/`.

Useful skills: /office-hours, /plan-ceo-review, /plan-eng-review, /review,
/ship, /qa, /investigate, /retro, /cso, /careful, /freeze, /guard, /learn.

If a gstack skill is missing, check `$TMPDIR/gstack-install.log` (or
`/tmp/gstack-install.log`) and rerun
`CLAUDE_CODE_REMOTE=true bash .claude/hooks/install-gstack.sh`.
To use gstack locally, install it once:
`git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack && cd ~/.claude/skills/gstack && ./setup`


## App Overview & Key Packages

**Cairn** (`cairn-android/`) is a personal second brain application for Android providing interactive geographic map visualization, local notes, graph connections, GitHub sync, and encrypted offline backup.

- **Working Directory**: `cairn-android/`
- **Core Packages**: `app.cairn`
  - `data/`: `Model.kt` (core note entities), `NoteStore.kt` (local offline storage), `Seed.kt` (initial data bootstrapping).
  - `map/`: `MapCanvas.kt`, `MapController.kt`, `Geo.kt`, `Projection.kt`, `MapDecor.kt` — custom Jetpack Compose map canvas with geospatial projection and landmark rendering.
  - `graph/`: `Graph.kt` — bi-directional relationship graphs between thoughts, notes, and locations.
  - `github/`: `GitHubSync.kt` and `TokenVault.kt` — secure synchronization of markdown notes with remote GitHub repositories.
  - `backup/`: `Backup.kt` — local encrypted export and import.

## Build, Test & Lint Commands (Quiet Mode for Token Saving)

Execute all commands from the `cairn-android/` project folder:

```bash
cd cairn-android

# Run unit & Robolectric tests
./gradlew testDebugUnitTest -q --console=plain --no-scan

# Run a single test class
./gradlew testDebugUnitTest --tests "app.cairn.data.NoteStoreTest" -q --console=plain --no-scan

# Build debug APK
./gradlew assembleDebug -q --console=plain --no-scan

# Lint check
./gradlew lintDebug -q --console=plain --no-scan
```

## Android & Cloud Container Engineering Rules (from innovator-repo/memory)

- **No Android Emulator in Cloud Containers**: Cloud containers lack KVM virtualization; do NOT attempt to launch emulators. Use Robolectric (+ Roborazzi where configured) for fast, headless UI and screenshot verification.
- **Compose Performance & Phases**: Defer rapidly changing State reads (such as scroll offsets and animation values) to the Layout or Draw phases using lambda modifiers (`Modifier.offset { ... }`, `Modifier.graphicsLayer { ... }`, `Modifier.drawBehind { ... }`) to avoid re-triggering composition. Never annotate mutable non-snapshot types with `@Immutable` or `@Stable`.
- **Platform Gotchas**:
  - **AccessibilityEvent recycling**: Copy `AccessibilityEvent` fields (`packageName`, `eventType`, `className`) synchronously on API 26–32 before crossing coroutine or async boundaries to avoid recycled event crashes.
  - **Device Admin Policy XML**: Android `<device-admin>` XML must declare at least one active policy (e.g., `<force-lock />`) to be valid on newer Android releases.
  - **System Settings Intents**: Always wrap system settings intents (`com.android.vpndialogs`, Private DNS settings, Device Admin enrollment) with fallback intents and written in-app user directions to prevent `ActivityNotFoundException` on heavily customized OEM ROMs (Samsung OneUI / Android 16).
- **APK Delivery Pattern**: When pushing debug APKs to an `apk/*` orphan branch, use a separate git worktree (`/tmp/apkwt`) and `git add --force` to prevent binary bloat on source branches.

## Token Hygiene & Compaction Keep-List

- **Token Hygiene**: Never read `.gradle/`, `build/`, `captures/`, `*.apk`, `*.aab`, `*.keystore`, or `*.dex`. Use targeted `read_file` with specific line ranges.
- **Cross-Session Memory**: Long-term project memory and cross-session insights live in `innovator-repo/memory` (`MEMORY.md`).
- **Compaction Keep-List**: When running `/compact`, always preserve:
  1. Current task goal and acceptance criteria.
  2. Kotlin/XML files created or modified.
  3. Build, test, or crash stack traces and identified root causes.
  4. Architectural decisions and design trade-offs.
  5. Pending Gradle, test, or APK delivery commands.

