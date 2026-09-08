# WaitaSecond prototype version 2

Update 2026-09-07: see [the detection regression fix report](DETECTION_FIX_REPORT.md) for corrected rules, actual Nord CE4 fixtures, and device validation. The original implementation report below is historical.

Functional logic pass completed; existing layout/theme retained. Focus goal is locked to **Study DBMS**. No Room, database persistence, ML, or third-party tap automation was added.

## A. What was wrong

- Instagram accepted a selected Reel-labelled node, a single viewer-like ID (including `clips_tab`), or multiple Reel labels plus generic playback text. It did not prioritize DM evidence. Bottom navigation and message UI could therefore produce false positives.
- YouTube concatenated up to 4,000 characters from the entire accessibility tree and scored that text. Recommendations could affect the result independently of the current video.
- Event deduplication expired after eight seconds, making unchanged content eligible for another event/intervention. Ordinary Instagram screens did not produce meaningful safe transitions.
- App and video duration accumulators did not exist. The displayed session duration was static; no elapsed countdown or automatic expiry existed.
- The session manager and independent intervention gate maintained separate state; the gate used wall-clock cooldowns. Simulation buttons were connected to the production pipeline.

## B. Architecture and logic

Accessibility events now wake a coalesced scan after 250 ms. A one-second service heartbeat handles quiet screens and screen-off checks. Foreground authority comes from the current accessibility root and its application window, not the event package. Keyboard/system windows are ignored. Our Activities explicitly distinguish the normal app screen from the intervention window.

Visible nodes retain ancestor IDs, selected/focused state, class, and editability. Stale or truncated traversal yields an empty result, favoring uncertainty. Isolated Instagram and YouTube classifiers produce structured results. The session tracker compares normalized meaningful state keys before recording events or requesting intervention. Unknown trees do not re-arm an existing distraction.

`SessionTracker` is a clock-injected, synchronized core; `SessionManager` is its single Android instance using `SystemClock.elapsedRealtime()`. It owns the session, app/content timers, observations, bounded history, attempts, intervention tokens, and suppression state. Activities do not maintain separate metric copies. Final metrics remain in memory until the next session resets them.

Drift uses the most recent three distinct YouTube titles, retaining each title's latest observation. Two successive score drops of at least 0.1, ending below 0.6, indicate drifting. A latest Instagram Reels activity is a strong drift signal. Non-video YouTube screens do not contribute scores.

## C. Source files created, modified, or removed

Paths below are relative to the project root. Generated Gradle outputs are excluded.

| File | Change |
|---|---|
| `app/src/main/java/com/example/waitasecond/AccessibilityBackController.kt` | Modified: target-package verification, bounded wait for intervention dismissal, Back failure handling. |
| `app/src/main/java/com/example/waitasecond/AccessibilityNodeInspector.kt` | Modified: ancestor IDs, editability/focus, incomplete-tree protection; removed unused arbitrary-text ranking. |
| `app/src/main/java/com/example/waitasecond/DbmsRelevanceScorer.kt` | Modified: added `index`; retained existing DBMS/distraction vocabulary and uncertain-allow behavior. |
| `app/src/main/java/com/example/waitasecond/DriftDetector.kt` | Modified: recent unique video content and Reels signal. |
| `app/src/main/java/com/example/waitasecond/FocusSession.kt` | Modified: immutable session snapshot and monotonic start timestamp semantics. |
| `app/src/main/java/com/example/waitasecond/InstagramReelsDetector.kt` | Modified: isolated multi-state classifier and structured confidence/signals/reason result. |
| `app/src/main/java/com/example/waitasecond/InterventionActivity.kt` | Modified: visible-launch acknowledgement, episode suppression, expiry, lifecycle/token handling. |
| `app/src/main/java/com/example/waitasecond/MainActivity.kt` | Modified: removed simulation actions; real countdown, attempts, app/content durations, clean history; fixed DBMS goal. |
| `app/src/main/java/com/example/waitasecond/PrototypePipeline.kt` | Modified: classify before scoring, current-title-only relevance, session-managed intervention launches, change-based logs. |
| `app/src/main/java/com/example/waitasecond/SessionEvent.kt` | Modified: video/known-state metadata and intervention-shown history flag. |
| `app/src/main/java/com/example/waitasecond/SessionManager.kt` | Modified: centralized transition tracker, monotonic timing, expiry, episode/launch state and test clock injection. |
| `app/src/main/java/com/example/waitasecond/WaitaSecondAccessibilityService.kt` | Modified: root-confirmed collection, coalescing/heartbeat, application-window filtering, throttled hierarchy logs. |
| `app/src/main/java/com/example/waitasecond/YouTubeClassifier.kt` | Created: conservative current-video/title extractor. |
| `app/src/main/java/com/example/waitasecond/InterventionGate.kt` | Removed: obsolete independent wall-clock gate; responsibilities moved into SessionManager. |
| `app/src/main/res/layout/activity_main.xml` | Modified: simulation section replaced with metric TextViews using the existing layout/style. |
| `app/src/main/res/values/strings.xml` | Modified: real metric labels, Back failure message; removed simulation/obsolete formatting resources. |
| `app/src/test/java/com/example/waitasecond/DriftDetectorTest.kt` | Modified: stable sequence, career-to-gameplay drift, unique-title filtering, Reels. |
| `app/src/test/java/com/example/waitasecond/InterventionLogicTest.kt` | Modified: retained decision coverage; obsolete gate coverage migrated to session tests. |
| `app/src/test/java/com/example/waitasecond/SessionManagerTest.kt` | Modified: 25 deterministic timing, deduplication, lifecycle, counting and expiry tests. |
| `app/src/test/java/com/example/waitasecond/ScreenClassifierTest.kt` | Created: 18 Instagram/YouTube classification regression cases. |
| `PROTOTYPE_V2_REPORT.md` | Created: this implementation and device-retest report. |

## D. Instagram detection

States: HOME, DM_LIST, DM_CONVERSATION, SEARCH, PROFILE, REELS, OTHER, UNKNOWN.

- Message composer/editable message field or conversation-specific IDs take priority over all Reels evidence. Confidence is 0.98 with composer plus thread/send support, otherwise 0.92. Inbox hierarchy or combined inbox labels/controls identifies DM_LIST at 0.95.
- Reels requires audio-specific evidence plus either a viewer ID and selected Reels/two engagement controls, or selected Reels plus at least two independent Like/Comment/Share controls.
- Confidence is 0.96 with viewer ID support, or 0.91 with selected-tab/audio/controls support. The intervention threshold is 0.90. These are heuristic confidence levels, not calibrated probabilities.
- A bare Reels label, selected tab alone, or viewer ID alone cannot trigger. Selected Home overrides navigation-only Reels evidence. DM evidence wins even over an otherwise strong Reels combination.
- Unrecognized/incomplete states are allowed. The `InstagramClassifier` log includes state, confidence, signals and reason.

## E. YouTube extraction and relevance

VIDEO requires a recognized player ID and one distinct plausible current-title candidate. Candidates come from explicit watch-title IDs or title/video-title nodes inside watch/video metadata or player-overlay ancestors. Nodes under known recommendation/related/search/feed hierarchies are excluded. Navigation/control labels are excluded. Text and content descriptions are supported.

Candidates are never ranked by relevance keywords. Multiple competing titles, missing player evidence, or no usable title yield no scored video and are allowed. Only the extracted title reaches the existing keyword scorer. DBMS vocabulary takes precedence; clear distraction vocabulary yields IRRELEVANT; unmatched content remains UNCERTAIN and allowed.

`YouTubeClassifier` logs the state/title/candidates/reason and `Relevance` logs title/score/result when these change.

## F. Timing

For foreground app A changing to B at monotonic time `now`, add `now - foregroundSince` to A, then set B and `foregroundSince = now`. Repeated A events do nothing. UI snapshots include the still-running interval without adding it again to the stored total. Session end flushes once; expiry flushes at the session deadline, even if checked later.

YouTube titles use the same model. Title A changing to B flushes A and starts B. Identical normalized titles do not restart. Leaving YouTube, entering a non-video/unknown state, or showing the intervention stops video timing. Returning resumes a new interval, aggregated by displayed title. These values are foreground content exposure time, not media playback time: pause/buffering status is not tracked.

WaitaSecond, YouTube, Instagram and other observed packages are accumulated. The UI displays the three named apps and up to eight title totals. Screen-off/lock switches to an Other bucket on the next service check. Transition precision depends on accessibility delivery/collection, normally about 250 ms to one second; system overlays are not separate app-time buckets.

## G. Distraction Attempts, Continue Anyway and Go Back

A new confidently irrelevant state requests one unique intervention token. The counter increments only when InterventionActivity acknowledges that token in `onResume`. Repeated acknowledgements cannot count twice. Launch errors or a five-second visibility timeout do not count or mark history Blocked. Here, Blocked means an intervention was shown, not that the user necessarily chose Go Back.

Continued events for the same state do not request another launch. Continue Anyway suppresses the entire current episode, with no periodic expiry. A confirmed safe screen, a real app departure/return, or a different distracting YouTube title allows another attempt. Temporary unknown trees and the intervention's own foreground window do not re-arm it. Reels is intentionally one screen-level episode; swiping Reels without leaving stays one attempt.

Go Back closes the intervention and performs GLOBAL_ACTION_BACK only after the expected YouTube/Instagram package is foreground. It retries briefly while our window is closing, cancels if another app is foreground, logs the result, and shows a manual-Back message on failure. No arbitrary taps are used. Both choices preserve episode suppression until a meaningful change. Home/app switching finishes a hidden intervention; unique tokens prevent stale Activity callbacks affecting a newer one.

## H. Session countdown

`remaining = max(0, startElapsedRealtime + durationMinutes * 60,000 - elapsedRealtimeNow)`.

The UI displays this derived value; it does not decrement a variable. A process-level handler checks expiry independently of MainActivity, and every state read/write also checks expiry. Android sleep can delay callbacks, but subsequent reads immediately expire and clamp accumulated durations to the actual deadline. In-memory session state intentionally ends if Android kills the process.

## I. Validation

Executed with Android Studio's bundled JDK:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Result: **BUILD SUCCESSFUL**. **56 tests, zero failures/errors**:

- ScreenClassifierTest: 18
- SessionManagerTest: 25
- DriftDetectorTest: 8
- DbmsRelevanceScorerTest: 3
- InterventionLogicTest: 1
- Existing ExampleUnitTest: 1

Lint: **zero errors; six existing dependency/version-update warnings**. No dependency upgrades were needed.

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit test report: `app/build/reports/tests/testDebugUnitTest/index.html`

Lint report: `app/build/reports/lint-results-debug.html`

No physical-device instrumentation or installation was performed in this pass.

## J. OnePlus retest and remaining tuning

The project contained no captured current YouTube/Instagram hierarchy fixtures. Detector IDs and English label combinations therefore remain conservative prototype rules, validated with synthetic snapshots rather than actual OnePlus trees. Changes in app versions, language, missing IDs, fullscreen/miniplayer layouts, inaccessible titles, or large trees can cause allowed false negatives. Equal-title videos cannot be distinguished without an exposed video identifier. Very fast departures/returns between scans can be missed.

Validate on the OnePlus Nord CE4:

1. Start a Study DBMS session. Check that simulation controls are absent.
2. Instagram: home, search, profile, inbox, conversation, then switch conversations. No intervention/attempt increase should occur.
3. Enter actual Reels: one intervention. Continue Anyway and stay/swipe: no repeat. Return to DM, then Reels: second attempt.
4. YouTube: DBMS Normalization and SQL Joins should be relevant. Unrelated recommendation text must not override the current title. GTA Gameplay should intervene only when identified as the current video.
5. Go Back should affect only the underlying target app. Test system Back, Home, rotation and app switching while the intervention is visible.
6. Spend measured intervals in YouTube and Instagram, return to WaitaSecond, and compare app/title totals. Use a one-minute session to check expiry while outside WaitaSecond and across screen lock.

Inspect Logcat tags `InstagramClassifier`, `YouTubeClassifier`, `Relevance`, `ForegroundTracker`, `DistractionTracker`, `AccessibilityTree`, and `WaitaSecondAccessibility`. Hierarchy logs are limited to 60 nodes per app every ten seconds, retaining IDs/ancestor IDs/class/selection/editability without dumping private DM text. Tune the isolated classifiers from those observations; do not weaken the current-state evidence requirement merely to increase detection rate.

READY FOR REAL DEVICE RETEST
