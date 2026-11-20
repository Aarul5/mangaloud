#!/usr/bin/env bash
# Makes the Manga Loud relay start when you sign in (macOS and Linux), and come back if it stops.
#
#   relay/install-autostart.sh            install and start it
#   relay/install-autostart.sh --dry-run  show what would be installed, change nothing
#
# Linux: a systemd user service (mangaloud-relay.service).
# macOS: a launchd agent (io.github.aarul5.mangaloud.relay).
# Run it again any time to update it. uninstall-autostart.sh removes it.
# On Windows, use install-autostart.ps1 instead.
set -euo pipefail

dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
logs="$here/logs"
log="$logs/relay.log"

# Full paths, so the service does not depend on your shell's PATH.
node_bin="$(command -v node || true)"
claude_bin="$(command -v claude || true)"
if [[ -z "$node_bin" ]]; then
  echo "node was not found. Install Node.js 20 or newer first." >&2
  exit 1
fi
if [[ -z "$claude_bin" ]]; then
  # The relay can still make voices without it, but it cannot read pages.
  echo "Warning: the claude command was not found, so the relay cannot read pages until it is installed." >&2
  claude_bin="claude"
fi
# claude runs on node, so both folders go on the service's PATH.
service_path="$(dirname "$node_bin"):$(dirname "$claude_bin"):/usr/local/bin:/usr/bin:/bin"
[[ "$(uname -s)" == "Darwin" ]] && service_path="$service_path:/opt/homebrew/bin"

if ! $dry_run; then
  # The log shows the relay token, so only you can read it.
  mkdir -p "$logs"
  chmod 700 "$logs"
fi

case "$(uname -s)" in
  Linux)
    unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
    unit="$unit_dir/mangaloud-relay.service"
    content="[Unit]
Description=Manga Loud relay (reads comic pages and makes voices for the phone app)
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=$here
ExecStart=\"$node_bin\" server.mjs
Environment=\"CLAUDE_BIN=$claude_bin\"
Environment=\"PATH=$service_path\"
# Tailscale may not be up yet at sign-in: the relay exits, and comes back 10 seconds later.
Restart=always
RestartSec=10
UMask=0077
StandardOutput=append:$log
StandardError=append:$log

[Install]
WantedBy=default.target"
    if $dry_run; then
      echo "Would write $unit:"
      echo "$content"
      exit 0
    fi
    mkdir -p "$unit_dir"
    printf '%s\n' "$content" > "$unit"
    systemctl --user daemon-reload
    systemctl --user enable mangaloud-relay.service
    systemctl --user restart mangaloud-relay.service
    echo "Installed and started mangaloud-relay.service."
    echo "To keep it running while you are signed out: loginctl enable-linger \"$USER\""
    ;;

  Darwin)
    label="io.github.aarul5.mangaloud.relay"
    plist="$HOME/Library/LaunchAgents/$label.plist"
    xml() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<< "$1"; }
    content="<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml "$node_bin")</string>
    <string>server.mjs</string>
  </array>
  <key>WorkingDirectory</key><string>$(xml "$here")</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>CLAUDE_BIN</key><string>$(xml "$claude_bin")</string>
    <key>PATH</key><string>$(xml "$service_path")</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>Umask</key><integer>63</integer>
  <key>StandardOutPath</key><string>$(xml "$log")</string>
  <key>StandardErrorPath</key><string>$(xml "$log")</string>
</dict>
</plist>"
    if $dry_run; then
      echo "Would write $plist:"
      echo "$content"
      exit 0
    fi
    mkdir -p "$(dirname "$plist")"
    printf '%s\n' "$content" > "$plist"
    domain="gui/$(id -u)"
    launchctl bootout "$domain/$label" 2>/dev/null || true
    launchctl bootstrap "$domain" "$plist"
    echo "Installed and started $label."
    ;;

  *)
    echo "This script is for macOS and Linux. On Windows, run install-autostart.ps1." >&2
    exit 1
    ;;
esac

echo "Relay log: $log"
echo "The phone setup link is in $here/setup-link.txt once the relay has started."
