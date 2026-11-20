#!/usr/bin/env bash
# Stops the Manga Loud relay started by install-autostart.sh and removes its autostart.
#
#   relay/uninstall-autostart.sh
set -euo pipefail

case "$(uname -s)" in
  Linux)
    unit="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/mangaloud-relay.service"
    if [[ -f "$unit" ]]; then
      systemctl --user disable --now mangaloud-relay.service || true
      rm -f "$unit"
      systemctl --user daemon-reload
      echo "Stopped and removed mangaloud-relay.service."
    else
      echo "No mangaloud-relay.service was installed."
    fi
    ;;

  Darwin)
    label="io.github.aarul5.mangaloud.relay"
    plist="$HOME/Library/LaunchAgents/$label.plist"
    if [[ -f "$plist" ]]; then
      launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
      rm -f "$plist"
      echo "Stopped and removed $label."
    else
      echo "No $label agent was installed."
    fi
    ;;

  *)
    echo "This script is for macOS and Linux. On Windows, run uninstall-autostart.ps1." >&2
    exit 1
    ;;
esac
