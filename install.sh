#!/usr/bin/env bash
# Build AI Relay and drop the `airelay` launcher onto your PATH.
set -euo pipefail
cd "$(dirname "$0")"

echo "Building…"
./gradlew --quiet installDist

BIN_DIR="${AIRELAY_BIN_DIR:-$HOME/.local/bin}"
mkdir -p "$BIN_DIR"
ln -sf "$PWD/build/install/airelay/bin/airelay" "$BIN_DIR/airelay"

echo "Installed: $BIN_DIR/airelay -> $PWD/build/install/airelay/bin/airelay"
if ! command -v airelay >/dev/null 2>&1; then
  echo "Note: $BIN_DIR is not on your PATH. Add it with:"
  echo "  export PATH=\"$BIN_DIR:\$PATH\""
fi
