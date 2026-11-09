> Historical prototype report. Current architecture and setup are documented in [README](README.md) and the [foundation report](docs/FOUNDATION_REPORT.md).

# Detection regression fix — 2026-09-07

This supersedes the detector limitations/rules described in the original PROTOTYPE_V2_REPORT.md where they differ.

## Confirmed causes

- The node inspector discarded every collected node if even one remote child disappeared during traversal. It now retains valid siblings and uses larger bounded traversal limits (1,500 nodes / depth 60).
- The service required the active root to also appear in Android's window list. It now accepts a valid active root when the list temporarily lacks that entry, and uses a focused application window when the active root is a keyboard/system window. System UI/current keyboard packages are not assigned as foreground apps.
- On the actual Nord CE4 Reels hierarchy, the audio label is `Audio`, not `Original audio`. The previous mandatory audio rule rejected an otherwise definite Reels screen. The corrected rule accepts viewer + multiple engagement controls, or selected Reels + multiple controls, without demanding an audio label. DM evidence still takes priority.
- YouTube's captured current owner row is a merged description: `MrBeast 516m subscribers`, without a channel resource ID. In the captured layout, the current title was not exposed at all. The extractor now supports that owner-row pattern within the watch metadata, with player and owner controls required for a title-unavailable fallback.
- MrBeast was missing from the prototype's distraction vocabulary. Current title/channel evidence can now identify it; unrelated recommendation text is still excluded. Relevant DBMS title keywords retain precedence. Unknown videos on other channels remain allowed, rather than treating every non-DBMS string as irrelevant.

## Changes

- AccessibilityNodeInspector.kt: preserve partial valid trees and increase traversal bounds.
- WaitaSecondAccessibilityService.kt: tolerate missing window-list entries; focused-app fallback; keyboard/system-package filtering.
- InstagramReelsDetector.kt: remove mandatory original-audio signal; support additional viewer IDs and labels with accessibility suffixes; retain negative DM/Home evidence.
- YouTubeClassifier.kt: expanded player/title layouts, contextual Expand-title action, current owner-row/channel extraction, structured channel-only VIDEO fallback.
- DbmsRelevanceScorer.kt: optional current channel; MrBeast vocabulary/channel rule.
- PrototypePipeline.kt: score the extracted current channel together with the current title; explicitly display `Video by MrBeast (title unavailable)` when title text is absent; distinguish unrecognized Instagram screen from Other app.
- SessionEvent.kt and SessionManager.kt: content-group metadata prevents a channel-only observation later acquiring its title from counting as a second attempt; re-evaluates relevance when channel evidence arrives after an unchanged title.
- ScreenClassifierTest.kt, SessionManagerTest.kt: additional layout, recommendation exclusion, no-audio Reels, and fallback-refinement cases.
- DeviceSnapshotTest.kt and four resources under app/src/test/resources/device/: sanitized actual Nord CE4 Reels, DM, YouTube owner-row and scrolled-player fixtures. DM names/messages are not stored.
- DETECTION_FIX_REPORT.md: this report.

## Validation

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`

BUILD SUCCESSFUL. 68 tests, zero failures/errors. Lint passed with dependency-version warnings only.

Updated APK installed successfully on the connected Nord CE4 using `adb install -r`.

Live device log verification at 08:50:44–08:50:46:

- DM_CONVERSATION, confidence 0.92; allowed.
- HOME, confidence 0.90; allowed.
- REELS, confidence 0.96; viewer ID + selected tab + three engagement controls.
- NEW DISTRACTION ATTEMPT #1, reason REELS.

The captured YouTube owner-row fixture also classifies VIDEO, extracts MrBeast, and scores IRRELEVANT without inventing a video title. When both current title and owner row are offscreen, the extractor remains uncertain and does not score recommendations. A title-unavailable fallback cannot distinguish multiple videos by the same channel until more current-title evidence appears.

Final build also includes a regression test for delayed current-channel metadata (68 total passing tests). Live YouTube intervention confirmation is still pending; it has been verified against the captured device hierarchy in JVM tests.
