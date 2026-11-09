# Foundation audit — 10 September 2026

## Starting point and implementation plan

The initial audit covered the sole `app` module, every production Kotlin source, XML layouts/resources, manifest and accessibility configuration, Gradle configuration, JVM/instrumentation tests, sanitized device fixtures, and the three historical implementation reports. No AGENTS.md was present. The only pre-existing worktree change was untracked `.idea/`; it was left untouched.

- Build: AGP 9.4.0 with built-in Kotlin, Gradle 9.6.0, daemon JDK 25, compile/target SDK 37, minimum SDK 24. Kotlin + XML/custom Views; no Java, Compose, Fragments, navigation framework, backend, UsageStats, or database.
- UI: MainActivity renders Home/timer and Statistics; a separate translucent InterventionActivity showed interventions. No Settings destination or production simulation buttons remained. The checklist had no backing feature/state.
- Session: clock-injected SessionTracker and a process singleton already had good monotonic countdown and transition accounting. State vanished on process death; no pause/resume or durable history existed. Boolean session status and DBMS-only goal validation limited extension.
- Monitoring: an accessibility service coalesced events, but also scanned every second while active and kept a heartbeat scheduled forever while enabled. It requested all accessibility events. Node traversal was bounded but could classify partial trees, and old Android node pooling was not released. Production hierarchy logs were unnecessary.
- Detection: conservative YouTube current-title extraction, recommendation exclusion, Instagram DM precedence, and sanitized Nord CE4 fixtures were worth preserving. DMs were split into list/conversation states. No normalized shared context or replaceable goal-aware classification boundary existed. DBMS scoring was unconditional.
- Intervention: unique launch acknowledgements and episode suppression were useful. Activity launch/task lifecycle, forced target-task restart, pending launch flags, and unavailable Home confirmation were risks. Continue had an extra UI countdown. No persistent user-choice model existed.
- Statistics: accurate current/last-session in-memory counts, but no real daily data. UI read singleton state on a separate recurring Handler.
- Privacy/permissions: only the user-enabled accessibility binding was actually required. No Usage Access, overlay permission, notification permission, or foreground-service declaration was needed by the existing observation mechanism. Empty backup templates would be inappropriate once private history was introduced.

Plan before editing: retain Kotlin/XML, visuals, tested extraction rules, monotonic calculations and bounded episode semantics; introduce explicit session status, pause/resume, repository-owned observable state, local persistence, coarse context and classifier boundaries, separate service-owned intervention, real daily aggregation, and targeted recovery/lifecycle tests. Do not add AI, network dependencies, other supported apps, or final design polish.

## Resulting decisions

1. Keep a clock-injected session core; use application-owned LiveData and a ViewModel for the XML UI.
2. Keep elapsed realtime as timer authority. Store epoch intervals only for daily history. Recover on the same boot using the persisted monotonic checkpoint; pause after reboot instead of comparing clocks from different boots.
3. Use SQLiteOpenHelper behind a small store interface. Load on a worker; serialize writes on that worker; keep immutable cached records for the main thread. No remote backend or additional database framework.
4. Persist session history and coarse distraction outcomes, never accessibility trees, video titles, channels, or message contents.
5. Keep the service system-bound but do no observation work while paused/inactive. Use window/content events and screen broadcasts, not periodic tree polling.
6. Show a TYPE_ACCESSIBILITY_OVERLAY window owned by Wait a Second. Return opens Wait a Second; Continue simply dismisses. Neither modifies or restarts a third-party app.
7. Apply existing DBMS rules only to DBMS-related sessions. Other normal-video topics remain UNKNOWN; confidently detected Reels/Shorts use a simple deterministic rule.
8. Preserve historical reports as history. README and the foundation report supersede their runtime instructions.
