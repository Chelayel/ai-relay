#!/bin/sh
# AI Relay installer for macOS and Linux. No Java, no Homebrew, no sudo:
#
#   curl -fsSL https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.sh | sh
#
# Downloads the self-contained build for this machine (it carries its own Java
# runtime) into ~/.local/share/airelay and links `airelay` into ~/.local/bin.
# Re-run it to upgrade. AIRELAY_VERSION=v1.2.3 pins a release.
set -eu

REPO="Chelayel/ai-relay"
HOME_DIR="${AIRELAY_HOME:-$HOME/.local/share/airelay}"
BIN_DIR="${AIRELAY_BIN_DIR:-$HOME/.local/bin}"

case "$(uname -s)" in
    Darwin) os=macos ;;
    Linux)  os=linux ;;
    *) echo "Unsupported system: $(uname -s). On Windows use install.ps1 or the .msi." >&2; exit 1 ;;
esac
case "$(uname -m)" in
    arm64|aarch64) arch=arm64 ;;
    x86_64|amd64)  arch=x64 ;;
    *) echo "Unsupported processor: $(uname -m)." >&2; exit 1 ;;
esac

asset="airelay-$os-$arch.tar.gz"
if [ -n "${AIRELAY_URL:-}" ]; then
    url="$AIRELAY_URL"  # a mirror, or a local file:// build
elif [ -n "${AIRELAY_VERSION:-}" ]; then
    url="https://github.com/$REPO/releases/download/$AIRELAY_VERSION/$asset"
else
    url="https://github.com/$REPO/releases/latest/download/$asset"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading ${asset}..."
if ! curl -fSL --progress-bar "$url" -o "$tmp/$asset"; then
    echo "Could not download $url" >&2
    echo "There may be no build for $os-$arch in that release: https://github.com/$REPO/releases" >&2
    exit 1
fi

# Unpack beside the old install and swap, so a failed download or a full disk
# never leaves half an airelay behind.
mkdir -p "$HOME_DIR" "$BIN_DIR"
rm -rf "$HOME_DIR.new"
mkdir -p "$HOME_DIR.new"
tar -xzf "$tmp/$asset" -C "$HOME_DIR.new"
rm -rf "$HOME_DIR"
mv "$HOME_DIR.new" "$HOME_DIR"

if [ "$os" = macos ]; then
    launcher="$HOME_DIR/airelay.app/Contents/MacOS/airelay"
else
    launcher="$HOME_DIR/airelay/bin/airelay"
fi
[ -x "$launcher" ] || { echo "The download did not contain a launcher at $launcher." >&2; exit 1; }
ln -sf "$launcher" "$BIN_DIR/airelay"

echo "Installed: $BIN_DIR/airelay"

# An airelay from another route (Homebrew, the .pkg, the .deb) stays where it
# is — this script cannot uninstall what a package manager owns — and if it
# comes earlier on PATH it is the one that keeps running. Name it and say how
# to remove it, rather than let "the upgrade did nothing" be discovered later.
old_ifs=$IFS; IFS=:
for d in $PATH; do
    IFS=$old_ifs
    [ -n "$d" ] && [ -f "$d/airelay" ] && [ "$d/airelay" != "$BIN_DIR/airelay" ] || continue
    if [ -d /Applications/airelay.app ] && [ "$d" = /usr/local/bin ] && ! grep -qs Cellar "$d/airelay"; then
        how="the macOS installer (.pkg). Remove it with:
      sudo rm -rf /Applications/airelay.app /usr/local/bin/airelay && sudo pkgutil --forget com.chelayel.airelay"
    elif grep -qs "Cellar/airelay" "$d/airelay" || [ "$d" = /opt/homebrew/bin ] || case "$d" in */.linuxbrew/*) true ;; *) false ;; esac; then
        how="Homebrew. Remove it with:
      brew uninstall airelay"
    elif [ -d /opt/airelay ] && [ "$d" = /usr/bin ]; then
        how="the .deb package. Remove it with:
      sudo apt remove airelay"
    else
        how="something else. Remove it by hand if you no longer want it."
    fi
    echo
    echo "Note: another airelay is installed at $d/airelay, from $how"
done
IFS=$old_ifs
case ":$PATH:" in
    *":$BIN_DIR:"*) echo "Run: airelay" ;;
    *)
        echo
        echo "$BIN_DIR is not on your PATH yet. Add it, then open a new terminal:"
        case "${SHELL:-}" in
            */zsh)  echo "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.zshrc" ;;
            */fish) echo "  fish_add_path $BIN_DIR" ;;
            *)      echo "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.profile" ;;
        esac
        ;;
esac
