# Contributing to Fityah

Thank you for your interest in contributing to Fityah! Fityah is an advanced screentime management tool for Android that utilizes accessibility services to help users manage their digital well-being.

## Index
1. [Quick Contribute](#quick-contribute) 
2. [Core Principles](#core-principles)
3. [Project Architecture](#project-architecture)
4. [Code Style & Standards](#code-style--standards)
5. [UI/UX Guidelines](#uiux-guidelines)
6. [Data Storage](#data-storage)
7. [Contribution Workflow](#contribution-workflow)
8. [Development Tips](#development-tips)

---

## Quick Contribute

If you're new to the project, here are some easy ways to get started:

### 1. Translating the App
Help make Fityah accessible to more people by translating it into your language.
- Find the base strings file at `app/src/main/res/values/strings.xml`.
- Create a new directory `values-<language-code>` (e.g., `values-fr` for French) in `app/src/main/res/`.
- Copy `strings.xml` to your new directory and translate the text within the `<string>` tags.
- Please do not open a pull request if you're just going to use AI. I can do that too and perhaps better.

### 2. Adding Support for a New Browser
Fityah needs to know the ID of the URL bar to track website usage.
- Open `app/src/main/java/com/refayatul/fityah/hardcoded/BrowserUrlBarIds.kt`.
- Add a new entry to `URL_BAR_ID_LIST` with the package name and the `displayUrlBarId`.
- **Tip:** Chromium-based browsers usually use `com.android.chrome:id/url_bar` (prefixed with their package name), and Firefox-based ones often use `ADDRESSBAR_URL_BOX`. Simply copy the config from firefox or chrome, replace the packagename with the mods package name.

### 3. Adding Support for App Mods (Instagram/YouTube)
If you use a modded version of Instagram or YouTube, you can add support for reel tracking/counting.
- Open `app/src/main/java/com/refayatul/fityah/hardcoded/ReelAppConfig.kt`.
- Find the entry for the original app (e.g., `com.google.android.youtube`).
- Copy that entry and create a new one with the mod's package name replaced (e.g., `app.revanced.android.youtube`).
- Usually, mods keep the same internal IDs, so you just need to update the package name keys.

**Workflow for Quick Contributions:**
1. Open a Pull Request with your changes.
2. Wait for the CI to build the APK.
3. Download the build, try it out on your device.
4. Comment on the PR confirming if it works as expected!

---

## Core Principles

- **Readability Over Cleverness:** Prioritize clean, understandable code.
- **Safety First:** The `AppBlockingService` and `UsageTrackingService` must never crash.
- **AI Policy:** **Never open a pull request if you use AI agents and don't know what the code does.**

## Project Architecture

Fityah follows a compartmentalization style centered around two primary accessibility services:

1.  **Usage Tracking (`UsageTrackingService`):** Features that track usage (e.g., `AppUsageTracker`, `ReelsCountTracker`).
2.  **App Blocker (`AppBlockerService`):** Features that perform actions like blocking apps, reels, or keywords (runs in a separate Android process).

### Feature Implementation

Each feature is a compartmentalized class that receives a service instance in `onServiceConnected()`. Setup logic, including loading configurations and registering broadcast receivers, should occur within this method.

- **Blockers:** Located in `app/src/main/java/com/refayatul/fityah/blockers`.
- **Trackers:** Located in `app/src/main/java/com/refayatul/fityah/trackers`.
- **Performance:** High-memory tasks (like reel/view blocking) that traverse the UI tree should run in background workers, while low-memory tasks run directly in `onAccessibilityEvent`.

## Code Style & Standards

- **Minimal Comments:** Only use comments when absolutely necessary or for documenting complex logic.
- **Single Module:** The project strictly uses a single `:app` module.
- **Hardcoded Values:** View IDs for blocking must be stored in the `hardcoded` folder.

## UI/UX Guidelines

### Design Philosophy
- **Calming & Peaceful:** Avoid overwhelming the user.
- **Minimalist & Material UI:** Use a combination of ASCII art, typography, and Material Design.
- **Typography:** 
    - **Coolvetica:** For high-impact screens (onboarding).
    - **Inter:** For general app UI.
- **Colors:** Use default values for dynamic colors where possible.

### Writing for Users
- **Crisp & Concise:** Speak in the first person using simple language (6th-grade level).
- **No Dashes:** Never use dashes (`-`) in user-facing text.
- **Accessibility:** Explain complex concepts with real-world examples for non-tech-savvy users.

## Data Storage

- **Room DB:** For large datasets (logs, usage analytics) in `app/src/main/java/com/refayatul/fityah/data/db`.
- **DataStore:** For user configurations and simple settings.
- **Models:** Raw data classes are kept in `app/src/main/java/com/refayatul/fityah/data/models`.

## Contribution Workflow

1.  **Discuss Changes:** For architectural changes, please open an issue or ask clarifying questions first.
2.  **Follow Style:** Adhere to the existing directory structure and naming conventions.
3.  **Verify AI Code:** If you use AI tools to assist your development, you are responsible for fully understanding every line of code you submit.
4.  **No internet:** Open up a issue if you're adding any feature that connects to a internet before you work on it

## Development Tips

### Automate Accessibility Granting
When developing accessibility services, Android often requires you to manually re-enable the service after every fresh install. To automate this, you can use the custom Gradle task:
- Run `./gradlew installAndGrantAccessibilityFdroidDebug` from your terminal.
- This will build the F-Droid debug variant, install it, automatically grant the necessary accessibility permissions via ADB, and launch the app.
