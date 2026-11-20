# Contributing to Manga Loud

Thanks for helping. Bug reports, fixes, new voice providers and translations are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Be kind. This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- Security problems go through a private report, not an issue. See [SECURITY.md](SECURITY.md).

## The parts of the project

| Folder | What it is | Built with |
|---|---|---|
| `android/` | The Android app: browser, reader, read-aloud, settings | Kotlin, Jetpack Compose, Material 3 |
| `relay/` | Optional PC relay: reads pages with Claude and makes PC or cloud voices | Node.js 20+, no npm dependencies |
| `relay/skills/` | The reading guide Claude follows to find and label speech bubbles | Markdown |

## Build and test the app

You need JDK 17 and the Android SDK (Android Studio installs both).

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`. Pull requests run the same command in CI.

## Run the relay

```bash
cd relay
node server.mjs
```

See the README for setting up the token and connecting the phone.

## Pull requests

- Keep each pull request to one change, and say what it fixes and how you tested it.
- Add or update unit tests for logic changes (`android/app/src/test/`).
- Match the style of the code around your change.
- Never commit keys, tokens, relay logs, saved pages or voice clips. The `.gitignore` covers the
  usual places; check `git status` before you commit.
- Don't attach pages from comics you don't have the rights to share. Describe the problem, or
  use a page you made yourself.

## Licence

By contributing, you agree that your work is released under the [GPL-3.0 licence](LICENSE).
