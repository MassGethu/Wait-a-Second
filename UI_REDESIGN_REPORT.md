# Integrated UI redesign

Implemented in the existing Kotlin/XML app. Home and the active timer now share one screen; Statistics is the only other navigation destination. The intervention remains a separate translucent Activity with a large card and dimmed underlying app. No reference images were supplied, so the design follows the written navy, blue-purple, rounded-card specification.

## Functional integration

- Circular timer uses the existing monotonic session clock. Default duration is 25 minutes; duration is editable before starting. End session flushes existing timers. No pause state was introduced.
- Topic editing supports DBMS-related topics, preserving the controlled relevance scorer's scope. The overlay reads the actual session topic and remaining time.
- Statistics use real current/last-session elapsed time, monitored-app durations, attempts and app percentages. There is no persistent daily history, so labels explicitly say session rather than presenting invented daily totals.
- Per-app attempt totals are independent of the bounded recent-event list. Continue retains the counted attempt and existing episode suppression.
- Return to session launches the distracting app's launcher with its task cleared, preserving WaitaSecond's session and timer. It does not send the user to WaitaSecond. A successful redirect is counted only when the detector subsequently confirms the target app's Home state within 15 seconds; tapping the button alone is not confirmation.
- YouTube/Instagram detectors, accessibility collection, relevance scoring, event deduplication, foreground timing and drift processing remain in place.
- Three optional checklist items and exactly two tracked-app rows are shown. Home/Statistics are the only navigation destinations. Enable monitoring opens Android's accessibility permission screen when required; there is no Settings tab.
- Scrollable layouts, weighted rows, compact navigation and system-bar insets support smaller viewports. The device layouts were visually inspected.

## Files changed in this UI pass

Modified:
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/example/waitasecond/MainActivity.kt
- app/src/main/java/com/example/waitasecond/InterventionActivity.kt
- app/src/main/java/com/example/waitasecond/SessionManager.kt
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/layout/activity_intervention.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/themes.xml

Created:
- app/src/main/java/com/example/waitasecond/FocusViews.kt
- app/src/main/java/com/example/waitasecond/FocusWindow.kt
- app/src/main/java/com/example/waitasecond/SessionStatistics.kt
- app/src/main/res/layout/screen_home.xml
- app/src/main/res/layout/screen_statistics.xml
- app/src/main/res/values/styles.xml
- app/src/main/res/drawable/bg_card.xml
- app/src/main/res/drawable/bg_inset.xml
- app/src/main/res/drawable/bg_primary_button.xml
- app/src/main/res/drawable/bg_accent_card.xml
- app/src/main/res/drawable/bg_nav_item.xml
- app/src/main/res/drawable/bg_icon.xml
- app/src/main/res/drawable/ic_focus.xml
- app/src/main/res/drawable/ic_home.xml
- app/src/main/res/drawable/ic_chart.xml
- app/src/main/res/drawable/ic_youtube.xml
- app/src/main/res/drawable/ic_instagram.xml
- app/src/test/java/com/example/waitasecond/SessionStatisticsTest.kt
- app/src/androidTest/java/com/example/waitasecond/FocusFlowTest.kt
- ui-review/home.png, ui-review/statistics.png, ui-review/overlay.png
- UI_REDESIGN_REPORT.md

Removed: app/src/main/res/values-night/themes.xml; the shared theme intentionally stays dark in both system modes.

## Validation

- Gradle assembleDebug and assembleDebugAndroidTest: passed.
- testDebugUnitTest: 79 tests passed, including existing screen classifiers, sanitized OnePlus fixtures, timing, deduplication and nine new statistics tests.
- lintDebug: passed with no errors; non-blocking warnings remain.
- connectedDebugAndroidTest on OnePlus Nord CE4 (CPH2613, Android 16): five tests passed, zero failures/skips.
- Device flow checks cover Home/Statistics navigation, Activity recreation and timer continuity, editable DBMS topic/duration, overlay topic, Continue/attempt statistics, and Return launching YouTube while retaining the session.
- Screenshot fixtures seed the tracker only inside instrumentation tests. There is no simulation UI or mock analytics in the production app. The overlay screenshot uses a test fixture over Home to verify transparency and layout; it is not evidence of live third-party detection.

YouTube and Instagram may alter their accessibility hierarchies or launcher behavior. Existing fixture tests pass, but continued live-device retesting remains necessary, especially for Instagram Home confirmation and manufacturer background restrictions. Data remains in memory and does not survive process death.
