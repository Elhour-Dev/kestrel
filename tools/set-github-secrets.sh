#!/usr/bin/env bash
# Uploads the release signing key (kept outside the repo in ~/.kestrel) as GitHub Actions secrets.
# Run once from Git Bash:  bash tools/set-github-secrets.sh
set -euo pipefail
DIR="$HOME/.kestrel"
PW=$(grep '^storePassword=' "$DIR/keystore.properties" | cut -d= -f2-)
base64 -w0 "$DIR/kestrel-release.jks" | gh secret set KESTREL_KEYSTORE_B64
printf '%s' "$PW" | gh secret set KESTREL_KEYSTORE_PASSWORD
printf '%s' "$PW" | gh secret set KESTREL_KEY_PASSWORD
printf 'kestrel' | gh secret set KESTREL_KEY_ALIAS
gh secret list
