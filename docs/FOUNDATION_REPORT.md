# Foundation development report

## Outcome

The experimental Kotlin/XML app now has explicit session transitions, durable offline history, observable UI state, normalized context, a replaceable classifier, event-based statistics, and a service-owned intervention window. Supported scope remains YouTube and Instagram only. No commit or staging operation was performed. The pre-existing untracked `.idea/` directory was not changed.

## Changes and architecture

- Reorganized the flat package into UI, core/session, core/distraction, core/monitoring, data, service/accessibility, service/overlay, and util boundaries. Retained the single Gradle app module and working toolchain.
- Added session ID, epoch start, ACTIVE/PAUSED/COMPLETED/CANCELLED states, accumulated active time and monotonic resume timestamps. Start/Pause/Resume/End are exposed through FocusViewModel. Home and Statistics consume the same application LiveData snapshots.
- Added SQLite-backed records, completed/cancelled session history, coarse distraction events and serialized worker I/O. Same-boot process recovery preserves countdown math; reboot recovery pauses at the checkpoint. Read/write failure is visible and does not delete the database.
- Added daily interval aggregation across multiple sessions, midnight and pauses. Attempt/return counts derive from stored events rather than separate mutable counters. Foreground-app exposure is not fabricated during process gaps.
- Centralized YouTube/Instagram names and package IDs. Normalized Instagram inbox/chat contexts into DIRECT_MESSAGES. Preserved DM-over-Reels precedence and current-video/recommendation isolation. Added conservative Shorts inputs.
- Separated extraction, deterministic classification, orchestration, overlay and storage. Arbitrary study subjects can start a session; DBMS video rules run only for matching goals. Unknown relevance/context is allowed and cannot re-arm an existing intervention through transient metadata loss.
- Replaced InterventionActivity and target-task restarting with a TYPE_ACCESSIBILITY_OVERLAY controller. Return opens Wait a Second; Continue dismisses. Recorded outcomes distinguish RETURN_TO_SESSION, CONTINUE_ANYWAY, AUTO_DISMISSED and UNKNOWN. Confirmed returns require MainActivity resumption after the choice.
- Kept episode suppression and unique launch acknowledgement; added a centralized 15-second cooldown. Pause/end, Home/app switching, screen lock, service interruption and destruction have dismissal paths. Failed attachment does not count an attempt.
- Removed permanent service polling, all-event subscription, production node-tree logs, obsolete intervention resources/countdown, empty example tests, and the unbacked checklist. Accessibility scans are coalesced and active-session-only; unsupported app content is never traversed. Missing/truncated/stale trees fail open; pooled nodes are released on older Android.
- Added permission disclosure and graceful settings fallback. Usage Access, SYSTEM_ALERT_WINDOW, notifications, foreground services, Internet and cloud services are not required. Local history is excluded from backup/transfer.

## File inventory

New production components:

- `FocusApplication.kt`
- `core/monitoring/SupportedApp.kt`, `AppContext.kt`, `OverlayController.kt`
- `data/model/DistractionEvent.kt` (events, intervals, session records and store contract)
- `data/local/LocalSessionStore.kt`
- `data/repository/SessionRepository.kt`, `StatisticsRepository.kt`
- `service/accessibility/ContextExtractor.kt`
- `service/overlay/AccessibilityOverlayController.kt`
- `ui/home/FocusViewModel.kt`

Major moved/refactored components:

- `SessionManager.kt` → `core/session/SessionTracker.kt`; Android singleton publication/persistence wiring lives in `data/repository/SessionRepository.kt`.
- `FocusSession.kt` → `core/session/FocusSession.kt`.
- `PrototypePipeline.kt` → `core/monitoring/MonitoringPipeline.kt`.
- `MainActivity.kt` → `ui/home/MainActivity.kt`.
- Service, inspector and YouTube/Instagram detectors → `service/accessibility/`.
- Existing scoring/drift/decision rules → `core/distraction/`; Views → `ui/components/`; inset helper → `util/`; observation/statistics models → `data/model/`.
- `activity_intervention.xml` → `overlay_intervention.xml`; manifest, Home/Statistics layouts, strings, themes, accessibility and backup XML updated.
- Gradle dependencies now explicitly declare existing-compatible ViewModel/LiveData through the version catalog.

New verification/documentation:

- `FoundationTest.kt`: 16 foundation regressions.
- `LocalSessionStoreTest.kt`: Android SQLite reopen/upsert and malformed-row checks.
- Existing detector, tracker/statistics and UI flow tests updated; sanitized device fixtures retained.
- `README.md`, `docs/FOUNDATION_AUDIT.md`, this report, and `docs/GIT_WORKTREE.txt`.

Production Kotlin paths above are relative to `app/src/main/java/com/example/waitasecond/`; tests live under the corresponding `app/src/test` or `app/src/androidTest` source set. Full added-file inventory is in the Git snapshot.

## Preserved behavior

Kotlin/XML visual structure, Home/Statistics navigation, editable duration, monotonic countdown, safe Instagram messaging, conservative current YouTube title extraction, DBMS relevance rules, episode suppression, visible-intervention counting, per-app exposure timing, and useful sanitized device fixtures remain. No simulation UI, fake analytics, network feature or additional supported app was introduced.

Intentional behavioral changes: pause/resume exists; all valid topics can start; daily persisted history replaces current-session-only UI metrics; Continue is immediate; Return opens Wait a Second instead of clearing the target app's task.

## Verification

Final command:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew build :app:assembleDebugAndroidTest --console=plain
```

**BUILD SUCCESSFUL**. The checked-in daemon configuration selected its required JDK. Debug and release compile/package tasks, the configured unit-test task, lint and the instrumentation APK build completed.

- JVM tests: **94 passed; 0 failures, 0 errors, 0 skipped**. Includes 16 new foundation regressions and preserved classifier/device-fixture coverage.
- Lint: **0 errors, 46 warnings**. Categories: dependency/Gradle upgrade suggestions (8), existing draw allocations (7), optional monochrome icons (2), KTX suggestions (28), locale-format suggestion (1). No unused-resource warning remains. No toolchain upgrade or cosmetic rendering rewrite was made just to silence these warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Release APK (unsigned): `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- Merged manifest: Activity/service names resolve to real Kotlin classes; accessibility XML reference is valid; Home/Statistics are the only navigation controls. The only merged uses-permission is AndroidX's app-private dynamic-receiver signature permission.
- `git diff --check`: passed.

ADB found **no connected device**; the emulator tool listed **no configured AVD**. Device instrumentation and live third-party interaction were not executed. Historical reports' device results apply to earlier builds, not this refactor.

## Deferred scope and remaining limitations

No AI/ML, embeddings, cloud APIs/backend, additional apps, behavioral prediction, recommendations, final animations or final visual design. Explore remains UNKNOWN unless a future extractor can identify it; Shorts has synthetic rather than current-device evidence.

Live accessibility overlay behavior and OEM background restrictions require a phone. Upgrading changes the service component name, so enable the new Accessibility entry. Android can stop the process; monitoring gaps cannot be reconstructed. Queued asynchronous writes and up to the checkpoint interval of app exposure can be lost on abrupt termination; a reboot restores paused rather than inventing elapsed study. Accessibility layouts/English labels may change, and incomplete trees intentionally produce false negatives. Wall-clock changes can affect calendar attribution, not countdown math. History currently loads into memory and has no retention/export/delete screen; revisit this before long-term use.

## Next development step

Validate the complete real-device loop on an iQOO phone: permission enable/deny/revoke, session start/pause/resume/end, Instagram inbox/chat switching/Reels, YouTube lecture/Shorts/unknown title, Continue/Return, lock/Home/rotation, force-stop/reopen, and persisted daily counts. Capture sanitized hierarchy fixtures for the installed app versions. Then expand relevance behind DistractionClassifier without reworking monitoring, session ownership, storage or UI.

## Git

See [exact git diff --stat, git status and added-file inventory](GIT_WORKTREE.txt). Because files were moved without staging, plain `git diff --stat` lists old-path deletions and omits untracked new paths; `git status` and the added-file inventory supply those paths. This is an unstaged worktree, not a deletion of the application.
