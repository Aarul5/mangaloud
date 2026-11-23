# Contributing to Manga Loud

Thanks for helping. Bug reports, fixes, new voice providers and translations are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Be kind. This project follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- Security problems go through a private report, not an issue. See [SECURITY.md](SECURITY.md).

## The parts of the project

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) walks through what happens when you press Read and
which file does what. Start there.

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
A debug build installs as "Manga Loud dev", next to the released app rather than over it, so you
can test a change without losing your settings.

## Run the relay

```bash
cd relay
node server.mjs
```

See the README for setting up the token and connecting the phone.

## Translations

On-screen text lives in `android/app/src/main/res/values/strings.xml`. To translate the app,
copy that file to `values-xx/strings.xml` (for example `values-es` for Spanish) and translate
the text between the tags. Keep placeholders such as `%1$s` and `%1$d` as they are.

Some screens still have their text written in the code. Moving it is a good first contribution.
Home and the browser bar show how:

- Add the text to `strings.xml` with a short, lower-case name: `<string name="bookmarks">Bookmarks</string>`.
- In a Compose screen, use `stringResource(R.string.bookmarks)` in place of `"Bookmarks"`.
- For text with a value in it, write `%1$s` (text) or `%1$d` (a number) in `strings.xml`, and
  pass the value: `stringResource(R.string.next_chapter_in, seconds)`.
- Outside Compose (messages from `ReadAloud`, for example), use `context.getString(...)`.

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
