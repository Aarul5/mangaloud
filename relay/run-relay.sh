#!/usr/bin/env bash
# Runs the Manga Loud relay in this terminal (macOS and Linux). Ctrl+C stops it.
# To start it at sign-in instead, use install-autostart.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec node server.mjs "$@"
