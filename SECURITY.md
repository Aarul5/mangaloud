# Security

## Reporting a problem

Please don't open a public issue for a security problem. Report it privately through this
repository's **Security** tab (**Report a vulnerability**). You'll get a reply as soon as a
maintainer can look at it.

Include what you found, how to reproduce it, and what an attacker could do with it.

## What counts

- Ways to read the relay's token, or to use the relay without it.
- API keys saved by the app (Claude, voice providers) leaking from the phone or the relay.
- A web page in the app's browser reaching the app's own code, files or keys.
- The relay running anything other than the reading and voice jobs it is meant to.

## How keys are handled

- The relay keeps its token and provider keys in files next to it (`relay/.token`,
  `relay/*-key.txt`), which are git-ignored. Its log can contain the token, so don't share it.
- By default the relay only listens on its Tailscale address. `HOST=0.0.0.0` opens it to every
  network the PC is on and sends the token unencrypted, so use that only on a network you trust.
- Never post keys, tokens or relay logs in issues or pull requests.

## Supported versions

Only the latest release gets security fixes.
