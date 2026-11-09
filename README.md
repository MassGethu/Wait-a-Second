# Wait a Second

An offline Android prototype for the iQOO Hackathon 2026 Productivity Track.

**“Don’t block the app. Block the distraction.”** YouTube can support studying, and Instagram messaging can support communication. The prototype observes coarse context during a user-started focus session and intervenes when supported rules identify a distraction.

Only **YouTube** and **Instagram** are supported. No other app content is inspected. This repository is a foundation for further development, not a finished competition release.

The longer-term project explores on-device intent classification and closer integration with Android focus tools. See the [Android feature handoff](docs/FOUR_FEATURES_HANDOFF.md) for the current build and its device-verification limits.

## Current behavior

- Home combines topic selection, duration (1–999 minutes), an explicit Light/Strict choice, timer, Start/End, and today's real totals.
- Statistics derives a seven-day focus chart and selectable Today, Yesterday, and seven prior calendar days from real records. Each period includes focus time, measured supported-app foreground time, attempts, successful redirects, per-app counts and Light/Strict session usage. Empty data is zero.
- Instagram inbox and all conversations normalize to `DIRECT_MESSAGES`. DM evidence overrides Reels signals. Feed, search, profile, and unknown screens do not intervene.
- YouTube current-video titles are extracted only from recognized watch context; recommendation titles are excluded. Shorts needs a Shorts-specific viewer and controls.
- Reels/Shorts trigger the deterministic short-form rule. Existing keyword/channel rules apply only to DBMS-related goals. Other subjects' ordinary videos remain UNKNOWN pending future classification work.
- A shown intervention records one attempt. **Light:** Go Back opens Wait a Second immediately; Continue Anyway opens a dedicated Activity and requires retyping a goal-specific sentence. **Strict:** only Go Back is available, followed by a dedicated ten-second focus reset inside Wait a Second. End Entire Session remains available during reset.
- Light returns count only when MainActivity resumes promptly. Strict returns count only after the visible reset completes. These are UI outcomes, not proof of subsequent productive behavior. Back cannot silently dismiss a Strict intervention; Light Back uses the same retyping flow.
- Only Strict uses the monotonic ten-second countdown. Strict reset pauses while its screen is not resumed; recreation preserves remaining time. Returning to a supported app before completing reset re-shows the existing intervention without another attempt. Process death restores session mode/timing but marks unresolved events interrupted and reevaluates context.
- One intervention is allowed at a time. The same episode remains suppressed until a known safe context, app departure, or distinct video. A 15-second cooldown suppresses rapidly changing episodes; it does not queue a delayed interruption of the same suppressed screen.

- Confirmed Instagram conversations receive a check-in after ten minutes. Work confirmation snoozes ten minutes; No returns directly to Home in both modes. An inbox does not accrue dwell. Switching chats stays one coarse visit; leaving messaging for 30 seconds resets it. DM check-ins, work confirmations and returns have separate Statistics counts and do not inflate distraction attempts/redirects.
- Home → Presets and schedules provides create/edit/delete/start for goal/duration/mode presets, seeded with Study DBMS and Deep Work. Recurring local-time schedules can be edited/enabled/disabled. Exact alarms are used when allowed; otherwise WorkManager timing may be approximate. A delayed start runs only the remaining scheduled duration; active or expired occurrences are skipped and recorded. Started notifications have a Skip / End action scoped to that session.

## Architecture

Under `app/src/main/java/com/example/waitasecond/`:

| Package | Responsibility |
| --- | --- |
| `ui/home` | XML screen binding, navigation between Home and Statistics, FocusViewModel actions |
| `ui/statistics` | Period selection and weekly chart presentation |
| `ui/components` | Existing reusable timer, chart and app row Views |
| `core/session` | Immutable status model and clock-injected session orchestration |
| `core/monitoring` | Supported-app registry, normalized context, replaceable classifier interface, monitoring pipeline, overlay contract |
| `core/distraction` | Preserved deterministic classification plus explicit intervention/countdown state machine |
| `data/model` | Session records, coarse distraction events, intervals and metrics |
| `data/repository` | Application session owner, observable snapshots, calendar history aggregation |
| `data/local` | SQLite persistence with serialized worker I/O |
| `service/accessibility` | Android observation, bounded extraction, YouTube/Instagram context detection |
| `service/overlay` | One service-owned intervention window and dismissal lifecycle |
| `core/planning`, `service/scheduling`, `ui/planning` | Calendar/missed-start policy, Android alarms/work/notifications, preset and schedule management |
| `ui/retype` | Dedicated typing task with matching feedback and no saved drafts |
| `util` | Window/inset support |

Flow: Android accessibility observation → normalized AppContext → DistractionClassifier → session episode/cooldown decision → OverlayController → persisted attempt/choice → repository state → ViewModel/UI.

The session is the single source of truth. The single application ticker publishes derived time; it never decrements a UI counter. Pure logic is testable with injected monotonic and epoch clocks. Existing drift/title-history helpers remain process-local for future development; they do not drive additional interventions or persist content.

## Persistence and time semantics

`focus.db` contains versioned session records and distraction events with stable IDs. SQLite schema version 2 preserves sessions/events and adds presets, schedules and scheduled-run history. The migration seeds two editable defaults only once. JSON payload version 2 adds session mode/end timestamp and event mode/action/outcome timestamps. Missing legacy modes default to Light; legacy choices/return flags map to equivalent outcomes. Existing rows upgrade on normal writes, without dropping tables or erasing history. SQLite writes are serialized off the UI thread. Startup loads before Start becomes available. Read/write failures surface in Home, keep valid rows where possible, and do not delete a damaged database.

Remaining time derives from accumulated active time plus the current monotonic interval. Activity recreation and navigation cannot reset it. A started session runs until it ends or expires; there is no pause/resume state. Expiry clamps history to the planned deadline, even when checked late. Same-boot process recovery includes the unobserved gap in the focus timer but does **not** invent foreground-app exposure. After reboot the session continues from its durable checkpoint with a new monotonic origin. State changes and a 30-second active checkpoint save progress; abrupt process termination can lose queued writes or the last uncheckpointed app exposure interval.

Daily totals clip active intervals at local calendar-day boundaries, including midnight and daylight-saving changes. Events group by their original intervention timestamp; mode usage groups by session start date. The chart covers today plus six prior days; the selector also exposes the seventh prior day. Focus time means an active session, not verified attentive study; app time means observed foreground exposure, not media playback. Changing the wall clock can affect calendar attribution but not the monotonic countdown. History currently has no retention/export/delete UI; Android app-storage clearing removes it. Backup and device transfer exclude the database.

## Permissions and enabling monitoring

1. Install and open Wait a Second.
2. Tap **Enable activity monitoring** and read the disclosure.
3. Open Android Settings → Accessibility → downloaded/installed apps → Wait a Second activity detection, then enable it. OEM menu names vary. For sideloaded APKs Android may require allowing restricted settings from the app-info menu first.
4. Return, choose a topic, duration and mode, and start. Without access, the timer still works and Home explains that monitoring is unavailable.

The service's `BIND_ACCESSIBILITY_SERVICE` protection means only Android can bind it. Only supported-app content is traversed; other apps supply coarse window-transition hints to stop accounting and clear stale state. The system may keep an enabled accessibility service bound while no session is active; scan callbacks and intervention windows are stopped, not the user's accessibility setting.

**Usage Access is not required or requested.** There is no UsageStats polling path. Do not grant it for this build; the app intentionally does not appear as a requester on the Usage Access page. A future UsageStats fallback would need an explicit implementation and permission flow.

No draw-over-other-apps permission, foreground service, Internet permission, or remote backend is needed. Optional notification permission announces scheduled starts, exact-alarm special access allows precise starts, and boot access restores schedules. The overlay uses the enabled service's [TYPE_ACCESSIBILITY_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY), not SYSTEM_ALERT_WINDOW. No third-party APK, process, file, code, or private API is modified or injected into. No automatic clicks or target-task restarts occur.

Upgrading from the experimental build changes the service component's package. Re-enable the new service entry after installation if Android no longer shows it as enabled.

## Build and run

Use Android Studio with Android SDK 37 installed. The checked-in wrapper uses Gradle 9.6.0 and AGP 9.4.0 with built-in Kotlin. `gradle/gradle-daemon-jvm.properties` selects JDK 25; Java source/target compatibility remains 11. Keep the working toolchain unless a specific compatibility problem requires an upgrade.

```sh
./gradlew build :app:assembleDebugAndroidTest --console=plain
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Install with Android Studio Run or:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For targeted verification:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

The last command requires an authorized connected phone/emulator. JVM tests cover timer transitions, process/reboot recovery, daily interval aggregation, persisted-choice semantics, cooldowns, classifier rules, DM normalization, and sanitized Nord device fixtures. Instrumentation covers active-timer recreation and SQLite serialization/reopening/error handling. No production simulation controls are present.

Debug builds use `WAS/SESSION`, `WAS/MONITOR`, `WAS/CLASSIFIER`, `WAS/OVERLAY`, `WAS/EVENT`, and `WAS/STORAGE` tags. Logs omit titles, channels and messages. No node dumps or per-event production logging is enabled.

## Known limits and next work

- Device-specific overlay and third-party-layout checks are documented in the [modes/history report](docs/MODES_HISTORY_REPORT.md). Coverage on one phone does not guarantee identical OEM behavior.
- Accessibility IDs/layouts and English labels can change; incomplete, ambiguous, unavailable, localized or unsupported contexts fail open. Explore is reserved in the normalized type but is not guessed from search evidence. Shorts currently has synthetic regression coverage, not captured live-device evidence.
- Android can stop the process/service. Observation gaps cannot be reconstructed. Foreground accounting precision depends on delivered accessibility events and the 250 ms coalescing interval. There is no alarm/notification guaranteeing an expiry callback while the process is absent; restored state immediately resolves the mathematical deadline.
- Existing keyword relevance is deliberately limited. Full AI, on-device models, embeddings, cloud APIs, additional apps, prediction, recommendations, final visual design and animation are deferred.

Next: validate the supported-context → overlay → choice → persisted-statistics loop on an iQOO/Android device and capture sanitized fixtures for its current YouTube/Instagram versions. Then extend topic classification through DistractionClassifier without changing monitoring, storage or UI ownership.

See [modes/history report](docs/MODES_HISTORY_REPORT.md), [foundation audit](docs/FOUNDATION_AUDIT.md) and [development report](docs/FOUNDATION_REPORT.md). Earlier prototype/UI reports are historical.

## YouTube task behavior

Initial detection attaches a service-owned accessibility window. It never opens an Activity or sends HOME/BACK. Only the user's Go Back action opens Wait a Second, using `FLAG_ACTIVITY_NO_USER_ACTION` to avoid requesting the ordinary user-leave callback. This does not disable YouTube's PiP setting or guarantee that YouTube/OEM policies never enter PiP on an intentional app transition. The old installed prototype used `InterventionActivity`; that Activity is absent from this build. See the report for the verified trace and remaining limits.

## Scheduling and privacy limits

Open Presets and schedules to grant optional precise-alarm and notification access. Battery optimization settings are linked with a non-blocking warning. Android/OEM battery restrictions, force-stop and permission revocation can delay or prevent starts; scheduling is not guaranteed. A timezone/manual-clock change recalculates the next local occurrence; reboot and process recovery catch up persisted due occurrences. DST gaps move a missing local time forward; overlaps use the first occurrence only. Multiple missed weeks collapse to one catch-up decision before the next future occurrence.

Retyping is voluntary friction, not tamper-proof enforcement. Paste/context menus, drops and autofill are disabled; keyboards, accessibility tools and OS input facilities cannot be universally controlled. No typed draft or sentence is stored/logged/transmitted. Instagram extraction uses IDs and whitelisted control descriptions, without reading message bodies or thread previews. Dwell is a process-local observation; reboot/process death starts a new visit. See docs/PROJECT_CONTEXT.md Section 25.
