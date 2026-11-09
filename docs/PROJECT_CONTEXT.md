# Wait a Second — complete project context

Source-reviewed on 5 October 2026. This document describes the Android app and Chrome extension currently present in the local source trees. It separates implemented behavior, product intent, and possible future work. It is suitable for collaborators, hackathon presentations, design discussions, and AI context. The original review inspected source. The Chrome extension was subsequently updated with retyping, DM dwell check-ins, presets and schedules; see Section 24 for the implementation and verification record. Android now also implements these three features; see Section 25 for Android behavior and verification.

## 1. The project in one paragraph

Wait a Second is a focus tool that helps a person stay aligned with a goal they choose before a work or study session. Instead of blocking an entire app or website, it observes supported contexts inside YouTube and Instagram and intervenes when rules identify a likely distraction. Relevant learning content and useful messaging can remain available. A brief intervention asks the user to return to their intention; Light Mode uses a sentence-retyping step on both platforms, while Strict Mode offers no per-distraction Continue button and introduces a ten-second focus reset. An Android app and Chrome extension implement this idea independently, with local storage and no cloud classification service.

## 2. Problem, purpose, and intended audience

A single platform can support a task and undermine it. A student studying Database Management Systems (DBMS) may need a YouTube lecture on normalization, then drift into unrelated entertainment or Shorts. A teammate may need Instagram DMs, then switch into Reels. Blocking the entire service removes both useful and distracting activity; measuring only total screen time also misses the difference.

Wait a Second adds the user's stated goal to the decision. Its central question is: “Is this activity aligned with what I intended to do?” The current software approximates that question with rules, screen/route detection, and selected title/channel signals. It does not know the user's thoughts or reliably understand every video.

The clearest initial audience is students doing focused academic work, especially the controlled DBMS demonstration. The broader concept can also serve people doing research, coding, or project communication, but current classification coverage is narrower than that ambition.

Product lines:

- “Don’t block the app. Block the distraction.”
- “Don’t block the website. Block the distraction.”
- “The problem isn’t screen time. It’s lost intention.”

The ten-second interruption is a deliberate friction mechanism: it creates a moment to reconsider an automatic action. Ten seconds is a product choice in this prototype, not a scientifically validated promise that attention has been restored.

## 3. Two implementations, one concept

| Area | Android app | Chrome extension |
| --- | --- | --- |
| Environment | Native Android app | Desktop Chrome Manifest V3 extension |
| Technology | Kotlin, XML layouts, Android Views, ViewModel/LiveData | React, TypeScript, Vite, CSS |
| Supported targets | Native YouTube and Instagram apps | YouTube and Instagram HTTPS web pages |
| Observation | User-enabled AccessibilityService | Restricted-host content scripts, URL and relevant DOM inspection |
| Focus duration | 1–999 minutes | 1–480 minutes; 25/45/60 presets |
| Session controls | Start and End; no pause/resume | Start, Pause, Resume and End |
| Ordinary YouTube relevance | DBMS-oriented title/channel rules | Topic token matching, small abbreviation groups, entertainment signals |
| Known-video ID catalog | Not implemented | Three topic-aware demo video entries |
| Instagram policy | Reels interrupted; DMs safe; feed/search/profile not interrupted | Reels, Explore and feed interrupted; DMs safe; profiles/unknown left alone |
| Light continuation | Retype a goal-specific sentence, then Continue | Retype a goal-specific sentence, then Continue |
| Go Back destination | Wait a Second app | Topic search on YouTube or Instagram inbox |
| Strict reset | Inside app; countdown advances while reset screen is resumed | Extension tab; persisted wall-clock deadline |
| Local storage | SQLite `focus.db` | `chrome.storage.local` |
| Measured supported-app exposure | Yes, from observed foreground intervals | No equivalent foreground-exposure measurement |
| Synchronization | None with the extension | None with Android |

These are separate codebases and separate stores. Starting a phone session does not start a browser session. Their histories are not combined, and there is no shared account or backend.

## 4. Shared user journey and decision model

1. Open Wait a Second.
2. Specify a topic or goal, such as “Study DBMS.”
3. Choose a duration and Light or Strict mode.
4. Start the session and use supported apps/sites normally.
5. The implementation detects the current high-level context.
6. Rules produce ALLOW, DISTRACTING, or UNKNOWN.
7. Only a confident DISTRACTING result can initiate an intervention.
8. The user returns to focus or completes Light Mode’s friction step (sentence retyping) to continue.
9. Local records support statistics and later review.

ALLOW means available evidence satisfies an implemented allowance. UNKNOWN means there is insufficient evidence; it does not mean the activity is confirmed productive. Both leave the user uninterrupted. When a session is absent or, on Chrome, paused, monitoring must not initiate interventions.

A recognized short-form section is handled by a structural rule: Shorts/Reels are distracting during a session regardless of the individual clip's subject. This is a deliberate simplification. The current product therefore does not semantically assess every short video.

## 5. Android app: screens and session behavior

Home contains the topic, duration, explicit mode selection, timer, Start/End controls, today's totals, tracked-app descriptions, and the monitoring enablement flow. The default topic is DBMS and default duration is 25 minutes. The topic UI accepts 2–80 characters. Topic, duration and mode are fixed for a running session; end it to start with different choices.

The user enables the accessibility service through Android Settings after reading the disclosure. Without access, the timer can still run, but detection is unavailable and Home indicates that monitoring is needed.

Home and Statistics share observable application state. The app does not keep independent timer counters on different screens. Session state is owned by SessionTracker and published through the application repository/SessionManager and FocusViewModel.

The current Android status model includes IDLE, ACTIVE, COMPLETED and CANCELLED. Natural expiry completes a session; manually ending it cancels the session while preserving elapsed progress. Earlier documents describe pause/resume, but that feature is absent in the current code. Old persisted PAUSED sessions are read as CANCELLED to avoid unexpectedly restarting them.

## 6. Android observation and extraction

The supported packages are `com.google.android.youtube` and `com.instagram.android`. The app's package is `com.example.waitasecond`.

The service reacts to relevant accessibility window/content/scroll/selection events and coalesces scans with a 250 ms delay. Other applications can provide coarse window-transition information so the app knows when to stop accounting for YouTube/Instagram, but their content trees are not traversed.

Extraction is bounded: at most 1,500 nodes, depth 60, and 200 characters per collected value. It considers visible node text/descriptions, view IDs, selected state and ancestor relationships. Editable node text is omitted. Incomplete or stale trees fail safely rather than becoming misleading partial evidence.

For YouTube, the extractor looks for an actual watch/player context and an unambiguous current-video title. Recommendation/feed/search-result ancestors are excluded. This prevents an unrelated suggested video from being treated as the video being watched. It can also use a recognized current channel-owner row when the title is unavailable. Shorts requires specific viewer/control evidence; the presence of a Shorts navigation button alone is insufficient.

For Instagram, inboxes and conversations normalize to DIRECT_MESSAGES. Message/composer/thread evidence takes precedence over stale Reels navigation. Reels requires a combination of viewer/selected-tab and engagement/audio controls. Feed, search and profile detection does not itself produce an intervention. Explore exists in the normalized model but is not confidently extracted as a separate Android blocking context.

## 7. Android classification

The classifier receives the active session and normalized AppContext, independently of extraction and UI.

- Missing/unknown context: UNKNOWN.
- Confirmed Reels or Shorts: DISTRACTING.
- Other recognized non-video contexts: ALLOW under current policy.
- Ordinary video with a goal outside the supported DBMS rule vocabulary: UNKNOWN.
- Ordinary video with a DBMS-related goal: evaluate its current title/channel with DbmsRelevanceScorer.

Strong DBMS terms include DBMS, database management system, normalization/normalisation, 1NF/2NF/3NF/BCNF, relational model/database, functional dependency, ER diagram and entity relationship. Other relevant terms include database, SQL, transactions, ACID, concurrency, indexing, keys, schema, queries and joins. Entertainment/game/music/sports keywords and recognized MrBeast channel variants can identify off-topic material.

The scorer uses fixed relevance scores such as 0.9 for strong DBMS matches, 0.7/0.8 for other matches, 0.1 for distraction signals and 0.5 for uncertainty. These are heuristic values, not probabilities from a trained model. Relevant keywords are evaluated before entertainment signals, so a video discussing a relevant technical concept can remain allowed.

A DriftDetector helper examines recent distinct YouTube relevance scores, and process-local title history exists. These helpers do not currently drive an additional predictive intervention system. Do not present them as production behavioral prediction or advanced AI.

## 8. Android intervention flow

The service owns a full-screen `TYPE_ACCESSIBILITY_OVERLAY` window. Showing the initial intervention does not open an Activity, send HOME/BACK, restart YouTube, or inject code into another app. Go Back opens Wait a Second; Light Continue opens its dedicated typing Activity.

### Light Mode

Go Back opens Wait a Second immediately. A successful return is counted when MainActivity resumes within the expected return window. Continue Anyway opens a separate typing Activity. The sentence is `I am choosing {Instagram Reels | YouTube Shorts | this video} over my goal: {real session goal}.` Matching trims and collapses whitespace and ignores case. Live feedback marks each normalized character green/red, and Continue stays disabled until a match. SessionTracker independently validates the submitted text, session and token before recording CONTINUED_AFTER_RETYPE; it never stores the sentence or draft. Back follows this same typing path. Leaving typing returns to the existing intervention, which reappears over the supported app without another attempt. The typing task is excluded from Recents; recreation clears the unpersisted draft.

### Strict Mode

The overlay exposes only Go Back. That action opens the app's dedicated reset screen with the focus topic and a ten-second countdown. There is no Skip or per-distraction Continue. The full session can still be ended from the reset screen.

The reset counts down only while its screen is resumed. If the user leaves and returns to a supported app before finishing, the existing intervention can be shown again with its existing token/countdown, without adding a second attempt. Screen recreation preserves remaining time while the process survives. Process death does not preserve an attached overlay: unresolved events become INTERRUPTED, and current context is evaluated again.

### Repeated-event control

Only one intervention flow is active at once. An episode key distinguishes meaningful contexts/videos from repeated UI updates. An unresolved or already-handled episode is suppressed until a safe context, app departure or distinct video re-arms detection. A 15-second cooldown limits rapid interruptions. A failed attachment does not count as a shown attempt; attachment acknowledgement and launch timeout protect statistics.

## 9. Android timing, persistence and recovery

Countdown durations use Android's monotonic elapsed clock. Calendar grouping uses wall-clock timestamps. This means ordinary wall-clock changes do not directly change the running countdown, though they can affect which day receives history.

`focus.db` contains `sessions`, `events`, `presets`, `schedules` and `schedule_runs` tables with stable IDs and JSON payloads. Session records include timing, mode/status, study intervals, observed supported-app intervals and recovery checkpoints. Events include the session/topic, app/section/category, mode, choice/outcome and timestamps. Database schema version is 2. The 1→2 migration creates planning tables and two editable presets without replacing existing sessions/events. Defaults seed only at creation/migration, so deleting them does not recreate them. Session/event payloads retain backward-compatible optional fields for scheduled duration and event kind.

Writes are serialized on a worker instead of doing SQLite I/O on the UI thread. State transitions and active checkpoints at approximately 30 seconds save progress. Valid rows are retained when other records cannot be read; failures surface in UI instead of silently erasing the database.

Same-boot process recovery includes the elapsed gap in the focus timer but does not fabricate foreground-app exposure. A reboot invalidates the previous monotonic origin, so recovery continues from the durable checkpoint with a new origin. Abrupt termination can lose queued writes or uncheckpointed exposure. Expiry math clamps completed time to the planned deadline, but there is no alarm guaranteeing an immediate expiry UI callback while the Android process is absent.

History currently has no retention, export or delete UI. Android app-storage clearing removes it. Backup and device transfer are disabled/exclude the database.

## 10. Chrome extension: popup and session behavior

The popup is roughly 410 pixels wide, with Focus, Presets, Schedules and Statistics tabs. Setup contains a goal input, duration presets of 25/45/60 minutes plus a custom duration, a Light/Strict selector, and Start. Goals may contain up to 120 characters. Active state shows the topic, remaining time, mode, Pause/Resume and End. A badge shows ON or the pause symbol.

Timing uses stored timestamps and accumulated paused time, not a decrementing popup counter. Closing the popup does not stop a session. Pause closes the current focus interval and removes interventions; Resume opens a new interval. Chrome alarms and worker request reconciliation resolve expired sessions. Unpaused browser/sleep time can count toward session time; this is not proof of attention.

The type model includes CANCELLED, but the current END handler records COMPLETED for manually ended sessions as well as natural completion. Do not assume Chrome provides Android's manual-cancellation distinction.

## 11. Chrome YouTube detection and classification

The adapter uses the route to distinguish HOME, WATCH, SHORTS, SEARCH and UNKNOWN. On watch pages it tries recognized title elements, metadata and document title, with a current-player video-ID check. It obtains the video ID from `URL.searchParams.get('v')`, never from a full-string URL comparison.

The normal classifier lowercases/tokenizes text, removes generic focus words and expands small related-word groups such as DBMS/database/SQL/normalization, DSA/algorithms/structures, AI/artificial/intelligence, ML/machine/learning and selected academic subjects. Title overlap can allow relevant material. Strong entertainment signals can interrupt academic goals. Missing or ambiguous evidence usually returns UNKNOWN.

This is intentionally simple. A matching word can be too broad; a relevant title may use vocabulary outside the map. The extension does not inspect the actual audiovisual content, transcripts, or the user's true intention.

Search is allowed. Home and unsupported routes remain UNKNOWN. Shorts is distracting during active focus.

## 12. The three known YouTube demo examples

The extension centralizes these in `src/shared/knownDemoVideos.ts`:

| ID | Label supplied for the demo | Category |
| --- | --- | --- |
| `gTKS8SAwUzE` | MrBeast | entertainment |
| `5fhcklZe-qE` | AI Coding | ai-coding |
| `jSluxDD3IFg` | Fortnite | gaming |

For DBMS, Study DBMS, or Database Management Systems, all three take the DISTRACTING safeguard path, including when title extraction is unavailable. Extra URL parameters such as `t=157s` do not affect ID extraction.

Each entry also has related-topic vocabulary. A goal such as “learning AI coding” bypasses that video's academic-mismatch safeguard and reaches normal classification. A matching title may then allow it; a missing title can remain UNKNOWN. The catalog is not an unconditional blacklist.

The safeguard runs before general title matching for a confident academic mismatch. Noncatalog videos continue through the general classifier. This is a controlled reliability feature, not evidence that the system understands every topic. Category compatibility is still keyword-based and can be wrong for nuanced or multidisciplinary goals.

The Android app does not currently share this three-ID catalog. Its MrBeast support is title/channel based. Do not promise identical behavior for the three exact URLs inside the native Android app.

## 13. Chrome Instagram and SPA handling

Routes determine Instagram contexts:

- `/direct/...`: DIRECT_MESSAGES, allowed by the classifier, including switching conversations. A separate route-only visible-dwell check-in can appear after ten minutes; it is not a distraction classification.
- `/reel/...` and `/reels/...`: REELS, distracting.
- `/explore/...`: EXPLORE, distracting.
- `/`: FEED, distracting.
- Recognizable profile and unknown routes: no intervention.

The Instagram adapter reads routes, not message DOM text.

YouTube and Instagram can navigate without full page reloads. The content runtime polls while a session is active at roughly one-second intervals, debounces reevaluation by 200 ms, and also listens for navigation/visibility signals such as popstate and YouTube navigation events. It avoids heavy full-document mutation processing. With no active session, the polling interval is cleared and classification is idle.

The runtime tracks URL, session, overlay state, cooldown and temporary bypass. A four-second cooldown and existing-overlay guard reduce repeated interventions. Light Continue bypass is in-memory for the current URL visit; navigation, session-state changes or reloading can clear it.

## 14. Chrome overlays, modes and safe navigation

The intervention is a dark, full-page dimmed layer with a centered card, explicit goal, site/context, and buttons. Closed Shadow DOM isolates CSS; keyboard focus handling keeps interaction in the overlay. Escape does not dismiss it. The content script pauses page audio/video when creating the intervention; playback is not automatically resumed after Continue.

Light: Continue Anyway opens a sentence-retyping step. The target names the current section and actual session goal. Matching trims/collapses whitespace and ignores case; punctuation still matters. Per-character feedback accompanies a text input with paste, drop and autocomplete prevention. The Continue button enables only after a match. The worker receives only the retype-start/action and completion outcome (`continued_after_retype`), never the input text. The existing URL-visit bypass applies. The old Light deadline is no longer used. Go Back still navigates to the existing focus destination.

Strict: Go Back opens an extension-owned `reset.html` page in the same tab. Per-tab reset state includes the session/event, topic, deadline and return URL. The state survives a reset-page reload and worker restart. After the deadline and a brief completion message, the page requests completion and opens the focus destination. Unlike Android's visible-reset counter, this is a wall-clock deadline and is not paused merely because the tab is hidden.

For YouTube, the destination is a search for the focus topic. For Instagram, it is the Direct inbox. The implementation uses these safe routes rather than calling browser history.back(), because the previous entry may itself be distracting or outside the intended context. Tabs are not closed.

Pausing/ending from the popup removes active interventions and clears resets. Strict Mode is a product interaction rule, not tamper-proof browser control: disabling the extension, manipulating the page, closing the tab or manually navigating away can defeat it.

## 15. What statistics mean

Both platforms derive totals from real local records; they do not populate demo numbers.

- Focus/study time: elapsed time in a running session, excluding Chrome pauses. It is not measured learning, productivity, or verified attention. Intervention/reset time is not separately subtracted as a distraction penalty.
- Distraction attempt: a recorded intervention episode, not every scroll, DOM update or second of entertainment.
- Successful redirect: a recorded return/reset outcome under each implementation's rules, not proof the user remained productive afterward.
- Per-app/site attempts: attribution of intervention events to YouTube or Instagram.

Android additionally measures supported-app foreground exposure during sessions, shows per-app totals, records Light/Strict session usage, and separately counts messaging check-ins, work confirmations and messaging returns. DM_CHECKIN events never enter distraction attempts, per-app distraction counts or successful redirects. CONTINUED_AFTER_RETYPE remains a distraction attempt, never a redirect. The chart covers today plus six prior days; the period selector can expose today and seven prior calendar days. Events group by intervention timestamp and mode usage by session start. Time intervals are split at local day boundaries.

Chrome shows Today and the rolling last seven local dates, daily focus bars, attempts, redirects and site totals. It also separately shows messaging check-ins, work confirmations, messaging returns, and distractions continued after retyping. `dm_checkin` events never increase distraction attempts, successful redirects or site distraction totals. It does not currently measure per-site foreground duration. History/events are pruned to approximately 90 days during worker maintenance; the displayed window is seven days.

Accounting precision differs. Android records an attempt after overlay attachment and confirms Light return through Activity resumption. Chrome creates the event when its worker accepts INTERVENE, before final DOM attachment; a navigation race can therefore leave a counted attempt without a fully displayed overlay. Chrome records Light return before the navigation request finishes, rather than confirming destination visibility. Strict success requires the reset completion request. These implementation limits matter when presenting analytics as evidence.

## 16. Architecture and source map

Shared conceptual pipeline:

```text
User goal + active session
            |
Supported app/site observation
            |
Normalized context (section, optional current title/channel/video ID)
            |
Replaceable rule-based classifier
            |
ALLOW / UNKNOWN --> no interruption
DISTRACTING ------> episode/cooldown checks --> intervention
                                               |
                                  user choice + mode/countdown
                                               |
                                   local event/session storage
                                               |
                                       UI and statistics
```

Android production source is under `app/src/main/java/com/example/waitasecond/`:

- `core/session`: FocusSession and SessionTracker, timer/recovery/episode ownership.
- `core/monitoring`: supported-app registry, AppContext, classifier interface and pipeline.
- `core/distraction`: DBMS scorer, explicit InterventionFlow state machine, decision and historical drift helpers.
- `service/accessibility`: Android events, bounded node inspection, platform extractors.
- `service/overlay`: service-owned window and explicit navigation back to the app.
- `data/model`: events, intervals, session records and statistics.
- `data/local`: SQLite cache/load/save and payload compatibility.
- `data/repository`: application state, ticker and calendar aggregation.
- `ui/home`, `ui/statistics`, `ui/components`: ViewModel, XML screen binding and reusable Views.

Chrome source:

- `src/popup`: React setup, active session and statistics components.
- `src/content/youtube` and `src/content/instagram`: platform adapters and section detectors.
- `src/content/index.ts`: active-session monitoring, route handling and intervention coordination.
- `src/content/overlay`: isolated visual/keyboard interaction layer.
- `src/background/serviceWorker.ts`: serialized state changes, deadline checks, alarms/badge and navigation.
- `src/reset`: Strict reset page.
- `src/shared`: typed models/messages, timing, matcher, classifier and centralized demo catalog.
- `src/storage`: state validation and date-based aggregation.
- `manifest.json`, `vite.config.ts`, `scripts/build.mjs`: packaging.

Chrome uses a single worker write queue to avoid competing tabs overwriting one another's changes. Runtime checks reject stale session/event actions. Storage is authoritative because Manifest V3 workers can stop and restart.

The architectural goal is to replace or improve classification without rewriting detection, session ownership, persistence or UI. Adding an interface for a future model does not mean a model exists today.

## 17. Privacy and permissions: precise description

There is no Firebase/Supabase/Django/Node backend, external classification API, OpenAI/Gemini integration, advertising SDK or account system in the inspected implementations. Core classification and record keeping are local. YouTube and Instagram themselves still use the network normally.

Android requires the user-enabled accessibility service. It does not request Usage Access, SYSTEM_ALERT_WINDOW/draw-over-apps, Internet, or a foreground service. Optional POST_NOTIFICATIONS supports scheduled-start notifications; SCHEDULE_EXACT_ALARM special access permits precise starts; RECEIVE_BOOT_COMPLETED restores schedules. WorkManager supplies its standard wake/network-state permissions, without adding an Internet permission or remote backend. The accessibility service is protected with BIND_ACCESSIBILITY_SERVICE. Its overlay is an accessibility window.

Android does not persist video titles, channels, message text, typed sentences or raw node trees. The Instagram inspector never reads node text, thread previews or message bodies; it uses structural IDs and a whitelist of navigation/control descriptions outside private messaging hierarchies. YouTube titles/channels remain transient context input. Accessibility necessarily traverses structural nodes, but message text is neither requested nor copied. Debug logging uses coarse operational tags and omits raw title/channel/message values.

Chrome's manifest requests storage and alarms, with content-script matches limited to the four supported YouTube/Instagram hosts. There is no all-URLs match or broad browser-history collection. The Instagram adapter uses only route context. Event storage can contain a bounded YouTube title, video ID/sanitized URL, session goal, classification reason, mode, choice/outcome and timestamps.

The extension makes no cloud-classifier requests. But its YouTube Go Back destination includes the focus topic as a search query; navigating there sends that query to YouTube through normal browsing. Therefore “no information ever leaves the device” would be inaccurate. “Classification and history are local; no project backend or analytics transmission” is the more precise claim.

Local storage is not a separate encryption feature. Neither implementation should be presented as end-to-end encrypted or enterprise security software.

## 18. Design and tone

Both implementations use near-black/navy surfaces, blue-purple accents, rounded cards, subtle borders, readable typography and restrained glow. The visual aim is a calm, intentional pause rather than punishment. The goal is displayed prominently so the interruption reconnects with the user's own choice. Statistics support awareness rather than a competitive score.

The core UI includes session setup/status, intervention, Strict reset and statistics. Both platforms also have preset and recurring-schedule management. There are no leaderboards, social feeds, authentication screens, or fake demo/simulation controls in the normal user flow. Accessibility and layout robustness still need device/browser validation; polished styling alone is not an accessibility certification.

## 19. Build and installation context

Local source locations:

- Android: `/Users/massgethu/AndroidStudioProjects/WaitaSecond`
- Extension: `/Users/massgethu/Wait a Second_extension`

Android's checked-in configuration currently declares minSdk 24, compile/target SDK 37, Gradle 9.6.0, Android Gradle Plugin 9.4.0, a JDK 25 daemon toolchain, and Java source/target compatibility 11. These are repository settings, not a recommendation to upgrade another project. Use Android Studio with the required SDK/toolchain, or `./gradlew :app:assembleDebug`. The expected debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install/open it and enable its accessibility service to use monitoring.

Chrome uses React 19, TypeScript 5 and Vite 7 package ranges. Run `npm install`, then `npm run build`. The build command includes TypeScript checking, builds popup/reset assets, creates a standalone IIFE content bundle, creates the module worker, and copies the manifest. Open `chrome://extensions`, enable Developer Mode, choose Load unpacked and select `/Users/massgethu/Wait a Second_extension/dist`. Reload existing supported-site tabs after installation/reloading.

There is no claim of Play Store or Chrome Web Store publication here. Source inspection also does not establish that a currently installed phone/Chrome copy matches the source on disk.

## 20. Demo narrative

Begin with the problem: “I need YouTube for a lecture. Blocking YouTube would block my work too.” Set Study DBMS and start a session. Show an identifiable DBMS lecture remaining available, then a confidently unrelated example causing an intervention.

On Chrome, the three catalog examples provide reliable DBMS mismatches. In Chrome Light, demonstrate the generated sentence, mismatching/matching feedback, and Continue unlocking only after successful retyping. Start a separate Strict session to show Go Back only, the reset, and return to topic search. Show Instagram DMs allowed and Reels interrupted, then view actual recorded statistics.

On Android, use current watch/title or channel evidence for YouTube rather than assuming the Chrome catalog exists. Show DMs versus Reels, then demonstrate that Go Back opens the app. Strict uses the app's visible reset; Light Continue requires retyping the generated choice. Use a supported, validated device/app layout for a live presentation.

Explain to judges that the demonstration validates the workflow and deterministic rules. Do not claim general semantic understanding, clinical benefit, reduced screen time percentages, or improved academic performance without separate evidence.

## 21. Current limitations and development direction

Both projects are hackathon/prototype implementations. The current reliability boundary is supported, recognizable app/site contexts and implemented rules.

Limitations include third-party layout changes, English-label dependence in parts of Android extraction, incomplete accessibility trees, title timing/staleness on web navigation, false positives/negatives from keywords, and limited topic vocabulary. OS/service/process termination and browser navigation can interrupt flows. Neither system can prove that a person is paying attention. Strict Mode is voluntary session protection, not parental-control or enterprise lock enforcement. Android and web policies differ and are not synchronized.

Possible future work, not implemented commitments:

- Expand subject vocabulary and contextual relevance confidence.
- Add an optional local classifier/model behind the existing interface.
- Improve device fixtures, localization and accessibility/layout compatibility.
- Refine measurement acknowledgements and interrupted-event handling on Chrome.
- Offer deliberate privacy controls for history deletion/export/retention.
- Align platform policy where that improves the product without hiding useful platform differences.
- Add more adapters only when deliberately scoped.

Cloud sync, accounts, additional social networks, recommendations, gamification and predictive analytics are not current features and should not be assumed requirements.

## 22. Source/documentation caveats

The current Android source and README supersede historical reports that describe pause/resume, immediate Continue or an InterventionActivity. Current source has no Android session pause/resume, uses Light sentence retyping, and uses a service-owned overlay. Some referenced modes/history documentation was not present in the inspected file inventory.

The extension README currently contains unresolved Git conflict markers around introductory content. Its source files were used to verify functionality rather than treating that README as authoritative. This explanation does not repair those markers or modify either implementation.

Earlier work in this conversation reported successful extension builds and lightweight checks. The new Chrome feature verification is recorded below. Android contains JVM tests, instrumentation tests and sanitized device fixtures. That original documentation review only inspected files. Fresh Android verification now includes the full build, 124 JVM tests and 12 device tests; see Section 25 and THREE_FEATURES_REPORT.md.

## 23. Copy-ready context for another AI

> Wait a Second is a local, context-aware focus prototype with separate Android and Chrome implementations. Its core principle is “Don’t block the app/website. Block the distraction.” Users choose a goal, duration and Light/Strict mode, then the software detects supported contexts inside YouTube and Instagram. Classification is deterministic rules/keywords, not an LLM or trained model. Decisions are ALLOW, DISTRACTING and UNKNOWN; uncertainty generally means no intervention.
>
> Android uses Kotlin/XML, ViewModel/LiveData, a user-enabled AccessibilityService, a service-owned TYPE_ACCESSIBILITY_OVERLAY and SQLite. It supports Start/End, not session pause/resume; duration is 1–999 minutes. Ordinary YouTube title/channel relevance is DBMS-oriented. Reels/Shorts are distracting, DMs are safe, and Instagram feed/search/profile are not blocked by current rules. Light Continue requires retyping a goal-specific sentence; Go Back opens the app. Strict Go Back opens a ten-second reset that advances while the app reset screen is resumed. Android tracks real local sessions, intervention outcomes and supported-app foreground intervals. It does not persist titles or message bodies, Instagram observation uses structural controls, without message text.
>
> Chrome uses React/TypeScript/Vite, Manifest V3, modular content-script adapters, a Shadow DOM intervention, a background service worker and chrome.storage.local. It supports Start/Pause/Resume/End and 1–480 minutes. YouTube uses route/video-ID/title extraction, topic overlap and entertainment signals. A centralized topic-aware catalog contains gTKS8SAwUzE (MrBeast), 5fhcklZe-qE (AI Coding), and jSluxDD3IFg (Fortnite). All three are distracting for DBMS goals without needing a title, but category-related goals fall through to general classification. IDs come from the v query parameter. Instagram DMs are allowed; Reels, Explore and feed are distracting. Light requires retyping a goal-specific sentence before Continue; input is never stored or sent to the worker. Strict opens a persistent ten-second reset tab, then topic search or DMs. Chrome also provides route-only visible DM dwell check-ins with separate statistics, editable presets and local-time recurring schedules using alarms. Schedules skip when a session is open and start only remaining time after a missed start; they require Chrome to be running. Chrome reset timing is wall-clock based, unlike Android's visible reset countdown. The extension has real daily/weekly statistics but no per-site foreground-time measurement.
>
> The two platforms do not sync. Neither uses a cloud classifier/backend, authentication or external analytics. Chrome's topic-search navigation does send the goal as a normal YouTube search query. Do not claim advanced AI, tamper-proof blocking, verified productivity, universal app support, or identical Android/browser behavior. Preserve separate detection, classification, session state, intervention state, persistence and UI responsibilities. For development, inspect current source and applicable repository instructions rather than relying on historical reports. Treat proposed future features as proposals, not existing capabilities.


## 24. Chrome update: retyping, DM check-ins, presets and schedules (5 October 2026)

### Light Mode retyping and input privacy

Continue Anyway now reveals `I am choosing {context} over my goal: {goal}.` Context is Instagram Reels, Instagram Explore, YouTube Shorts or this video. For the already-supported Instagram feed, the wording is Instagram Feed rather than calling the feed a video. The real goal is inserted as text, not HTML. Whitespace is normalized and case ignored; punctuation and all other characters must match. Correct/incorrect/pending characters receive live visual feedback and a textual progress status.

Paste/drop and replacement/autocomplete input paths are blocked where browser APIs allow it. Keyboard capture is installed at `document_start` on window for keydown/keyup/keypress; closed-root retargeting is handled by checking the owned shadow root’s active input and the event’s host path. Normal editing defaults remain available while propagation to later page shortcut listeners is stopped. Tab/Shift+Tab remain trapped correctly. The input exists only inside the overlay; no typed sentence is stored, logged, sent in an extension message or added to statistics. The worker stores a retype-start flag and `continued_after_retype` outcome, not a copy/hash of the typed sentence. This outcome remains a distraction attempt but is not a successful redirect.

Retyping is voluntary friction, not a security boundary. The worker cannot independently prove what was typed without receiving input, which this implementation deliberately avoids. Page/extension tampering, earlier-installed window listeners and browser/OS input features cannot be universally controlled. Strict’s dedicated reset page and ten-second deadline remain unchanged. Android now also uses sentence retyping (Section 25).

### Route-only Instagram messaging dwell

The Instagram adapter remains route-only. The new runtime reports only whether the route is `/direct/`, document visibility, and session identity; it never reads message text or sends conversation IDs. Worker-persisted dwell state is shared across tabs per session. It accumulates the union of visible messaging time, avoiding double-counting multiple visible windows. Hidden tabs and paused sessions do not accumulate. Switching conversations remains within the same visit. Leaving all direct routes for at least 30 seconds resets the visit; hiding a direct tab alone does not.

Defaults are named constants in `src/shared/constants.ts`: `DM_DWELL_THRESHOLD_MS` (10 minutes), `DM_SNOOZE_MS` (10 minutes), `DM_LEAVE_RESET_MS` (30 seconds), `DM_HEARTBEAT_MS` (5 seconds), and `DM_LEASE_MS` (15 seconds). No settings page was added. Visibility heartbeats bound stale/suspended-tab overcount to a lease; browser throttling can undercount. This is visible-tab dwell, not proof that the user is reading or typing. Pending check-ins pause dwell accumulation.

At the threshold, one tab owns the prompt. Other tabs do not create additional events. Ownership is released on hiding/leaving or stale heartbeat; another visible DM tab can resume the same pending event. “Yes, it’s for work (+10 min)” adds ten more visible messaging minutes before another check-in. “No, take me back” uses the existing Instagram inbox destination in BOTH modes, without a Strict reset. Because the inbox is itself a direct route, that response suppresses additional check-ins until the visit resets, preventing an immediate return loop.

A distinct `dm_checkin` record stores session ID, timestamp, elapsed minutes and optional `work_confirmed` / `returned` outcome. It contains no message text or thread URL. These counts are separate from distraction attempts and successful redirects. An unanswered/interrupted prompt can remain without an outcome. Browser startup clears stale tab visibility claims instead of assuming browser-closed time was visible messaging time.

### Presets, recurring schedules and migration

Schema version 2 preserves valid legacy active session, history, distraction events and Strict reset records. Two defaults are seeded once: Study DBMS / 25 minutes / Light, and Deep Work / 60 minutes / Strict. Users can create, edit, delete and start presets from the popup. Deleted defaults are not recreated. Deleting a preset also deletes its associated schedules, as stated in the UI. Editing a preset affects future starts, not an already-running session.

Schedules refer to a preset and specify weekdays, local `HH:mm` and enabled state. The Schedules view supports creation, editing, toggling, deletion, next-run preview and recent start/skip logs. Creating/editing/enabling a schedule starts considering future occurrences from that action, rather than launching an earlier slot immediately. All writes remain serialized through the worker.

The worker uses one-minute `chrome.alarms` ticks plus per-schedule next-fire alarms; it does not use worker setTimeout to schedule sessions. Alarms are reconstructed on worker startup, runtime startup/install and schedule changes, and reconciled on ticks. Each schedule tracks its last handled time. State and logs are persisted together with session changes so repeated wakeups do not create duplicate starts.

Scheduled sessions only run while Chrome’s process is running. If Chrome starts after a scheduled time but before its original end, only the remaining duration is started; closed-browser time is not added to focus statistics. If the slot has expired, it is skipped and logged. An active OR paused session prevents a scheduled session from replacing it. A session that occupied a scheduled slot but expired before delayed processing also causes a skip. Overlapping due schedules are ordered by time, then schedule ID: the first eligible start wins. Logs retain at most the latest 200 entries (also subject to the existing 90-day retention).

Dates are constructed in the device’s local timezone, not by adding fixed 24-hour intervals. JavaScript’s local calendar behavior moves nonexistent spring-forward times ahead by the DST gap and selects the first occurrence of ambiguous fall-back times; a schedule runs only once for that date/time. Timezone changes are reconciled at the next tick. Chrome alarms can be delayed by sleep or throttling, so exact-second execution is not guaranteed. No notifications permission or start notification was added; session status/badge and schedule logs expose starts.

### Files and verification

New modules: `shared/retyping.ts`, `shared/dmDwell.ts`, `shared/scheduling.ts`, `storage/migration.ts`, `background/sessionState.ts`, `background/scheduler.ts`, `content/overlay/keyboardGuard.ts`, and popup Presets/Schedules components. Existing worker, content runtime, overlay, types/messages/constants, storage/statistics, popup app/styles and manifest integrate them. General YouTube/Instagram classifiers/adapters and Strict reset UI remain unchanged.

`npm test` covers sentence matching, dwell/visibility/pause/visit reset, threshold/snooze, weekday/DST scheduling, missed-start/overlap policy, migration preservation and separate statistics. A worker API mock exercises the actual bundled worker queue, preset/schedule actions, startup recovery, tab prompt ownership, retype outcomes and Strict deadline rejection/completion. Existing classifier/timing checks are retained.

A small isolated real-Chrome fixture (`scripts/verify-overlay.mjs`) verified real typing through a closed Shadow DOM input, capture/bubble shortcut suppression for keydown/keyup/keypress, match gating, paste/drop prevention, Tab/Shift+Tab navigation, Strict-only Go Back and both check-in buttons. It does not log input. It uses synthetic page shortcut listeners, not logged-in YouTube or Instagram. Actual site layouts/shortcut versions, OS sleep/wake, browser-closed scheduling and a live ten-minute multi-tab DM session have not been verified end-to-end on real accounts.

Verification completed: production build and TypeScript checking passed; all 9 focused tests plus the existing logic checks passed. Package paths and `git diff --check` passed. The isolated Chrome content fixture also verified route-only DM reporting without message DOM access and no repeated reporting loop while paused. Reload the extension and existing supported-site tabs so old content scripts cannot keep using the obsolete Light countdown protocol.

API references: [Chrome alarms](https://developer.chrome.com/docs/extensions/reference/api/alarms), [closed Shadow DOM event paths](https://developer.mozilla.org/en-US/docs/Web/API/Event/composedPath).


## 25. Android update: retyping, DM check-ins, presets and schedules (5 October 2026)

All three features use the existing application session owner and offline SQLite store. Observation still produces AppContext; rules decide distractions; the service-owned overlay intervenes; the store records coarse events. No third-party APK/code/files are modified.

**Light retyping:** Section 8 now describes the dedicated Activity and session-owner validation. Exact normalized matching is required. The field rejects platform paste, long-press menus, drag/drop and autofill, disables suggestions/personalized learning where supported, and never saves drafts across recreation. Retyping is voluntary friction, not a tamper-proof barrier: keyboard and operating-system input facilities cannot be universally controlled. Successful completion preserves the current distraction episode suppression. Leaving the Activity retains the same token/event and restores its overlay on return to the supported app. Strict’s visible ten-second reset is unchanged.

**Messaging dwell:** `DmDwellPolicy.THRESHOLD_MS` is ten minutes and `EXIT_GRACE_MS` is thirty seconds. `AppContext.isDirectConversation` distinguishes confirmed conversation hierarchy/composer IDs from inbox-only evidence. Unknown/missing context never initiates a check-in. Individual chat identities are deliberately unavailable: A→B remains one continuous coarse conversation visit. A temporary departure under thirty seconds keeps that visit; a longer departure resets it. Screen-off ends confirmed foreground observation. Dwell is transient and resets after process/service observation loss.

The prompt asks whether the messaging visit is for the real goal. Work confirmation snoozes another ten minutes from the action. No returns directly to Home using the normal Go Back navigator in both Light and Strict, with no Strict reset for this check-in. At most one prompt is shown per visit unless work confirmation grants another. DM_CHECKIN events record only coarse app/section, goal/session, timestamps and WORK_CONFIRMED/RETURNED_TO_FOCUS/INTERRUPTED outcomes. They have separate period-aware Statistics counts and never increase distraction attempts or successful redirects. No message text, preview or chat identity is read/stored/transmitted.

**Planning:** Home links to Presets and schedules. Users can create, edit, delete and start named goal/duration/mode bundles, with 1–999-minute validation. Study DBMS/25/Light and Deep Work/60/Strict seed once and remain editable/deletable. Preset deletion also removes its schedules; history remains. Editing a preset affects future sessions only. Schedules select a preset, ISO weekdays, local start time and enabled state.

`SchedulePolicy.nextFire` uses local calendar dates through desugared java.time. A DST spring gap advances to the valid time after the gap; a fall overlap chooses the first occurrence only. AlarmManager.setExactAndAllowWhileIdle is used with precise-alarm access; otherwise a unique one-off WorkManager request supplies approximate timing. Pending intents use stable schedule URIs and immutable flags. Durable next occurrence and last-handled timestamps prevent duplicate/stale deliveries. Boot, package replacement and ordinary process startup catch up a persisted due occurrence; manual clock/timezone changes recalculate the next local occurrence. Disabled/deleted/edited schedules cancel old requests. If multiple schedules become due together, the first eligible delivered occurrence starts; the others see an active session and skip.

A due start skips when a session is currently active or local history shows that a session occupied its original start time. A late delivery starts only `preset duration − lateness`; a fully expired slot is recorded as skipped. Missing time is not invented as study time. Multiple missed weeks collapse to one catch-up/skip record and a future local occurrence. Scheduled-run history is durable and visible on the planning screen. A started notification links to Home and has a Skip / End action scoped to its specific session ID, so an old notification cannot end a later session.

POST_NOTIFICATIONS is optional on Android 13+ and requested from planning; denial suppresses the notification/action, not the timer. SCHEDULE_EXACT_ALARM is special access and has a linked settings action. RECEIVE_BOOT_COMPLETED restores scheduling. Battery optimization has a non-blocking warning/settings link. There is no new polling or foreground service. OEM restrictions, force-stop, exact-alarm revocation, clock changes and abrupt termination can delay/prevent starts or lose queued writes; precise or guaranteed scheduling is not claimed. Restoring a previously running focus session after reboot retains the existing checkpoint semantics from Section 9.

**Verification:** JVM coverage includes sentence normalization/authoritative completion, abandoned typing and episode reuse, dwell threshold/snooze/departure/inbox behavior, both modes’ check-in actions/statistics, local weekday wrap, DST gap/overlap, missed starts and actual study accounting. Device SQLite tests migrate genuine schema-1 files while preserving history and verify planning/event round trips and deletion without re-seeding defaults. Device/UI results and remaining limits are recorded in docs/THREE_FEATURES_REPORT.md.
