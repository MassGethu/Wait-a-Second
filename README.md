Wait a Second

Wait a Second is an AI-powered productivity and focus app built for the iQOO Hackathon 2026.

The core idea is simple:

Don’t block the app. Block the distraction.

Most productivity tools treat an entire app as distracting. But in reality, the same app can be useful or distracting depending on the user’s intent.

For example:

YouTube can be used for a DBMS lecture, but also for unrelated videos or Shorts.
Instagram can be useful for messaging a teammate, but distracting through Reels or Explore.

Wait a Second tries to understand this difference and intervene only when the user starts drifting away from their intended task.

Problem

Traditional app blockers and screen-time tools mainly ask:

“Which app are you using?”

But that is often not enough.

A student may genuinely need YouTube while studying. Blocking the entire app can reduce productivity instead of improving it.

The real problem is often loss of intention.

A user may open YouTube for one useful video and slowly drift into unrelated content without realizing how much time has passed.

Wait a Second focuses on identifying and interrupting that distraction drift.

How It Works

The user starts a focus session and specifies what they want to work on.

Example:

Topic: DBMS
Focus Time: 25 minutes

This becomes the context for the study session.

The user can then leave Wait a Second and use the phone normally.

For the current prototype, the app focuses on:

YouTube
Instagram

When potentially distracting activity is detected, Wait a Second displays its own intervention overlay above the target app.

Example:

Hey, wait a second!

You're currently studying DBMS.
This looks off-topic.

[ Return to session ]

[ Continue anyway ]

The user still remains in control.

Choosing Return to session helps the user move away from the distraction.

Choosing Continue anyway dismisses the intervention and allows them to continue.

The distraction attempt is recorded for later analysis.

Core Philosophy

Wait a Second does not modify, inject code into, patch, or tamper with YouTube or Instagram.

The intended architecture is:

User opens YouTube / Instagram normally
                ↓
Wait a Second observes supported context
                ↓
Potential distraction detected
                ↓
Our own intervention overlay appears
                ↓
Return to Focus / Continue Anyway

The target application remains separate.

Current Prototype Features
Focus Session
Set a study topic
Start a focus timer
Track active study time
Maintain the current session context
Distraction Intervention
Monitor supported applications
Detect selected distracting behaviour
Display a large intervention overlay
Show the current study topic
Show remaining focus time
Allow the user to return or continue
Statistics

The prototype tracks:

Total study time
Screen-time information
Number of distraction attempts
Successful redirects
Distraction breakdown

Currently supported breakdown:

YouTube
Instagram

The goal is to show not only how long the phone was used, but also where the user actually lost focus.

Why AI?

Static blockers can follow rules like:

Block YouTube during study time.

But that creates a problem because useful YouTube content gets blocked too.

The long-term goal of Wait a Second is to use AI to understand context.

For example:

Current Goal:
Study DBMS

Current Content:
Normalization in DBMS

→ Relevant

Compared with:

Current Goal:
Study DBMS

Current Content:
Unrelated entertainment video

→ Potential Distraction

Future versions could consider signals such as:

Current focus topic
Visible context
Content relevance
App-switching behaviour
Session duration
Repeated distraction attempts
Historical usage patterns
Time of day

This could allow the system to identify behavioural drift, rather than relying only on fixed app blacklists.

On-Device AI

A major goal of the project is to keep behavioural intelligence on-device wherever possible.

This is especially relevant because the system may work with sensitive contextual information.

Potential advantages include:

Better privacy
Lower latency
Faster interventions
Offline operation
Reduced dependence on cloud servers
Better use of modern smartphone AI hardware

Instead of continuously uploading behaviour data to a server, the long-term vision is for the phone itself to determine whether the current activity still aligns with the user’s intention.

Android Architecture

The prototype is designed around Android-supported mechanisms rather than modification of third-party applications.

Relevant technologies may include:

Usage Access / UsageStatsManager
Accessibility Services
Overlay / window APIs
Foreground services
Local state storage
Android lifecycle and background monitoring
On-device AI models in future versions

The architecture is intentionally designed to keep the monitoring and intervention system separate from Instagram, YouTube, and other third-party apps.

Prototype Scope

The current version intentionally focuses on only two applications:

App	Prototype Role
YouTube	Context/distraction monitoring
Instagram	Context/distraction monitoring

The goal of the hackathon prototype is not to support every application.

Instead, the focus is on demonstrating the complete experience:

Start focus session
        ↓
Use phone normally
        ↓
Open YouTube / Instagram
        ↓
Distraction detected
        ↓
Intervention overlay
        ↓
Return or continue
        ↓
Record distraction
        ↓
View statistics
Future Scope

The concept can eventually expand beyond YouTube and Instagram.

Possible future platforms include:

Reddit
X
Snapchat
Streaming platforms
Browsers
Games
Other social and content applications

A deeper OEM-level implementation could make the system substantially more reliable.

For a smartphone manufacturer such as iQOO, the concept could potentially evolve into an intelligent device-wide attention layer integrated with:

Digital Wellbeing
System-level app activity
Launcher behaviour
On-device AI acceleration
Focus modes
Device-level contextual awareness

The long-term vision is not another app blocker.

It is a phone that understands whether technology is currently helping the user achieve their goal or pulling them away from it.

UI

The prototype uses a minimal dark interface with blue and purple accents.

The main UI consists of only three primary screens/states:

Focus / Home
Statistics
Distraction Overlay

The UI is intentionally kept simple for the hackathon prototype.

Target Users

The initial target audience is students who need access to useful online resources while studying but frequently get distracted by the same applications.

The concept could later expand to:

College students
Professionals
Remote workers
Competitive exam aspirants
Anyone trying to reduce digital distraction
Key Differentiator

Traditional productivity tools:

Which app are you using?

Wait a Second:

Why are you using it — and are you still doing what you intended to do?

That difference is the core of the project.

Built For

iQOO Hackathon 2026 — Productivity Track

Wait a Second explores how on-device AI and smartphone-level context can create a more intelligent approach to digital wellbeing and productivity.

Disclaimer

This project is currently a hackathon prototype.

Some AI-driven contextual understanding and wider cross-app support described in the future scope are not yet production-ready features.

The prototype is intended to demonstrate the feasibility and user experience of intent-aware distraction intervention while avoiding direct modification of third-party applications.

Project Status

Prototype under active development

Current focus:

Improving distraction detection
Stabilizing time tracking
Improving Instagram navigation handling
Recording distraction attempts correctly
Refining intervention overlay behaviour
Improving UI and statistics
Final Vision

The problem isn’t screen time. It’s lost intention.

Wait a Second aims to help users keep the useful parts of their phone without letting those same apps quietly pull them away from what they originally wanted to accomplish.

Don’t block the app. Block the distraction.
