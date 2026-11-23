# How Manga Loud works

A map of the code for people who want to change it. Start with "What happens when you press
Read", then jump to the part you care about.

## The pieces

```
Phone (android/)                                         Your computer (relay/, optional)
┌──────────────────────────────────────────────┐         ┌──────────────────────────────┐
│ Browser (WebView) ── reader.js finds images  │         │ server.mjs                   │
│        │                                     │  HTTP   │  /ocr   → claude -p + guide  │
│ ReadAloud ── TextReader ── Claude API ───────┼────or───┼─▶/speak → voice server / Fish│
│        │                  or PC relay        │ Tailnet │  /cast  → character voices   │
│        └── Speaker ── phone voice            │         └──────────────────────────────┘
│                   └── cloud voice (Fish,     │
│                       ElevenLabs, Gemini)    │
└──────────────────────────────────────────────┘
```

## What happens when you press Read

1. **Find the chapter.** `PageScript` runs `assets/reader.js` in the page. It finds the comic
   images (wide images, at least three) and the "Next chapter" link.
2. **Slice the images.** `TextReader.fetchImage` downloads each image with the page's cookies.
   `ImageSlicer` (in `Slices.kt`) cuts tall webtoon strips into overlapping, screen-sized slices,
   and `PanelFinder` (in `Panels.kt`) finds the gaps between panels.
3. **Read the text.** `TextReader.recognize` sends a batch of slices to an `OcrProvider`:
   `ClaudeApiOcr` with your API key, or `RelayOcr` to your computer. The answer follows the JSON
   schema in `OcrPrompt` (`Ocr.kt`): each bubble's text, position, kind (dialogue, thought,
   narration, game window, reaction, sound effect), speaker, character, tone and strength.
   Answers are kept in `OcrCache`, so no slice is sent twice.
4. **Keep the cast straight.** `SeriesCast` keeps each series' characters on the phone and sends
   the list with every batch, so the same person keeps the same label, and the same voice.
5. **Order the bubbles.** `ChapterStore` holds every bubble read, in reading order, and merges
   a bubble cut across two slices (`SeamTwins`).
6. **Say each line.** `ReadAloud` glides the page to the next bubble and highlights it, then
   `Speaker` says it: the phone's voice with a pitch and speed for its tone (`VoiceStyle`), or a
   clip from a cloud voice (`CloudVoice`) or your computer (`PcVoice`). Clips are fetched a few
   bubbles ahead.
7. **Carry on.** A worker thread in `ReadAloud` keeps reading text a little ahead of the voice.
   At the end it can open the next chapter and keep reading.

## Files, by area

All app code is in `android/app/src/main/java/io/github/aarul5/mangaloud/`.

**Screens** (Jetpack Compose, Material 3)
- `MainActivity.kt`: holds the screen state and connects the screens to everything else.
- `HomeScreen.kt`, `BrowserScreen.kt`, `SettingsScreens.kt`, `VoiceScreens.kt`: one file per area.
- `UiTheme.kt`: colours and theme. `CastDialog.kt`: the older dialog for the PC's character voices.

**Browser**
- `BrowserClients.kt`: the WebView clients (blocking ads, jumps and pop-ups).
- `AdBlocker.kt`, `assets/adblock_hosts.txt`, `assets/cosmetic.css`, `assets/overlay_guard.js`.
- `PageScript.kt` and `assets/reader.js`: everything done inside the page, such as finding images,
  scrolling and highlighting.
- `Library.kt`: bookmarks, history and Continue reading. `NextChapter.kt`: the next chapter's images.

**Reading**
- `ReadAloud.kt`: the coordinator. It handles position, the worker thread, scrolling by hand,
  pausing for calls, and the end of a chapter.
- `TextReader.kt`: images in, bubbles out.
- `Ocr.kt` (the prompt and schema, the cache), `ClaudeApiOcr.kt`, `RelayOcr.kt`.
- `Slices.kt` (bubbles, slices, kinds of text), `Panels.kt`, `ChapterStore.kt`, `SeriesCast.kt`.

**Voices**
- `Speaker.kt`: says one line with whichever voice is chosen, falling back to the phone voice.
- `VoiceStyle.kt` (phone voice tone) and `SpeechText.kt` (tidying text for the phone voice).
- `CloudVoices.kt`: the cloud providers (`FishTts`, `ElevenLabsTts`, `GeminiTts`) behind one
  `CloudTts` interface. `VoiceText.kt` writes each line the way each provider wants it: cues,
  audio tags or a style.
- `CloudVoice.kt` (clips from the phone) and `PcVoice.kt` (clips from the relay).
- `VoiceSetup.kt`: usual voices and per-series voices, and which voice a line gets.

**Settings and keys**
- `Settings.kt`: everyday settings.
- `SecretStore.kt`: API keys and relay tokens, encrypted with the Android Keystore.
- `SetupLink.kt`, `RelayPicker.kt`, `RelayCast.kt`: relays.

**The relay** (`relay/`, Node.js 20, no npm dependencies)
- `server.mjs`: the HTTP server, its token and choosing the Tailscale address. `/ocr` runs
  `claude -p` on the slices, with the reading guide.
- `skills/read-comic-basic.md` and `skills/read-comic.md`: the reading guide, telling Claude how
  to read a page. `read-comic.md` is also built into the app for the API key mode.
- `voice.mjs`: the PC voice, through an OmniVoice voice server or Fish Audio.
- `install-autostart.*` and `run-relay.*`: starting the relay on Windows, macOS and Linux.

**Website** (`docs/`): the GitHub Pages site, the demo chapter and the README screenshots.

## Common changes

- **Add a cloud voice:** implement `CloudTts` in `CloudVoices.kt` (key test, voice library,
  speech), add it to `VoiceProviderId`, and give it a format in `VoiceText.kt`. The settings
  screens pick it up from there. Add tests in `VoiceTextTest.kt`.
- **Change how pages are read:** edit the reading guide in `relay/skills/`. When the JSON the
  app expects changes, update `OcrPrompt` in `Ocr.kt` and raise `OcrPrompt.VERSION`, so cached
  pages are read again.
- **A site whose images are not found:** see `collectChapter` in `assets/reader.js`.
- **Translate the app:** see "Translations" in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Tests

Unit tests are in `android/app/src/test/` and run on your computer:

```bash
cd android
./gradlew testDebugUnitTest
```

CI runs them on every pull request, along with a debug build and the relay's checks on Ubuntu and
macOS. Anything that needs Android itself (WebView, text-to-speech, the Keystore) is tested by
hand on a phone. The [demo chapter](https://aarul5.github.io/mangaloud/demo/chapter-1/) is a good
page to try a change on.
