# Changelog

All notable changes to Manga Loud. Versions follow [Semantic Versioning](https://semver.org).

## Unreleased

## 0.1.0

The first public release.

### Reading

- A browser for manga, manhwa and webtoon sites that reads speech bubbles aloud and glides the
  page to each bubble as it is read, with a highlight on the line.
- Text is read by Claude, either with your own Claude API key or through the PC relay (your
  Claude plan, via Claude Code on your computer). Each bubble comes back with its text, type,
  speaker, tone and how strong the tone is. Sound effects are skipped.
- Tells dialogue, thoughts, narration, game windows and reactions (a cough, a gasp, a sigh)
  apart, and uses bubble shapes as tone clues.
- Keeps a cast list for each series, so the same character gets the same label, and the same
  voice, across chapters. The main character is always "hero".
- Reads ahead of the voice and caches results on the phone, so a chapter is only sent once. The
  start of the next chapter is read early, so Read starts at once.
- Optional: start reading when a chapter opens, and go on to the next chapter when one ends.
- Pauses for calls and when the app goes to the background; scroll by hand and reading picks
  up from what is on screen. Skip, pause and stop from the reading bar.
- Right-to-left reading for Japanese manga, and pauses on panels with no text.

### Voices

- Phone voice (free, offline), with pitch, speed and volume that follow each line's tone.
- Cloud voices straight from the phone with your own key: Fish Audio, ElevenLabs and Gemini.
  Tones and sounds are acted with each service's own cues.
- Your usual voice for each kind of speaker (narrator, game windows, man, woman, boy, girl, old
  man, old woman, creature), picked from each provider's library with samples.
- Voices per series: a series can use another provider, change any speaker's voice, and give
  its characters their own voice.
- Optional PC voice server (OmniVoice, Qwen3-TTS, VibeVoice, or Fish Audio through the relay).
- When a voice fails, the phone voice reads that line, or reading stops with the reason.

### App

- Home with bookmarks, Continue reading and history; a slim browser bar; settings in groups.
- Light and dark themes, and wallpaper colours on Android 12+.
- Built-in ad and pop-up blocker, with a downloadable public blocklist.
- API keys and relay tokens are stored encrypted with the Android Keystore.

### PC relay

- Runs on Windows, macOS and Linux, and starts at sign-in with the included scripts.
- Listens only on the computer's Tailscale address by default; every request needs its token.
- A setup link adds the relay to the phone without typing.
