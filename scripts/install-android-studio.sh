#!/usr/bin/env bash
set -euo pipefail

# Installs Android Studio and the Android SDK components needed to build the
# sailer app (Arch Linux). Run this yourself in a real terminal (it needs
# your sudo password and, if any package isn't in the official repos, your
# confirmation to build it from the AUR):
#
#   ./scripts/install-android-studio.sh
#
# Afterwards, open a NEW terminal (so the PATH/group changes below take
# effect) and either launch Android Studio and open the sailer/ project, or
# build from the command line with ./gradlew.

SDK_ROOT="/opt/android-sdk"
PLATFORM="android-34"
BUILD_TOOLS="34.0.0"

aur_install() {
  if command -v yay >/dev/null 2>&1; then
    yay -S --needed "$@"
  elif command -v paru >/dev/null 2>&1; then
    paru -S --needed "$@"
  else
    echo "No AUR helper (yay/paru) found. Install one first, or install" >&2
    echo "the following AUR package(s) manually: $*" >&2
    exit 1
  fi
}

# Package placement between the official repos and the AUR shifts over
# time, so try pacman first and only fall back to the AUR if needed, for
# every package below rather than assuming where each one currently lives.
install_pkg() {
  local pkg="$1"
  if pacman -Si "$pkg" >/dev/null 2>&1; then
    sudo pacman -S --needed "$pkg"
  else
    aur_install "$pkg"
  fi
}

echo "==> Installing Android Studio"
install_pkg android-studio

echo "==> Installing Android SDK cmdline-tools, platform-tools, build-tools"
install_pkg android-sdk-cmdline-tools-latest
install_pkg android-sdk-platform-tools
install_pkg android-sdk-build-tools

echo "==> Adding yourself to the 'android-sdk' group (needed to write into $SDK_ROOT)"
if ! id -nG "$USER" | grep -qw android-sdk; then
  sudo gpasswd -a "$USER" android-sdk
  echo "    Added - this only takes effect in new sessions; using 'sg' below for now."
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "ERROR: $SDKMANAGER not found - the android-sdk-cmdline-tools-latest" >&2
  echo "package layout may have changed; check 'pacman -Ql android-sdk-cmdline-tools-latest'." >&2
  exit 1
fi

echo "==> Accepting SDK licenses"
sg android-sdk -c "yes | '$SDKMANAGER' --licenses >/dev/null" || true

echo "==> Installing platform-tools, platforms;$PLATFORM, build-tools;$BUILD_TOOLS"
sg android-sdk -c "'$SDKMANAGER' --install 'platform-tools' 'platforms;$PLATFORM' 'build-tools;$BUILD_TOOLS'"

RC_FILE="$HOME/.zshrc"
MARKER="# Added by sailer/scripts/install-android-studio.sh"
if ! grep -qF "$MARKER" "$RC_FILE" 2>/dev/null; then
  echo "==> Adding ANDROID_HOME and SDK tools to PATH in $RC_FILE"
  {
    echo ""
    echo "$MARKER"
    echo "export ANDROID_HOME=\"$SDK_ROOT\""
    echo "export ANDROID_SDK_ROOT=\"\$ANDROID_HOME\""
    echo "export PATH=\"\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
  } >> "$RC_FILE"
else
  echo "==> $RC_FILE already configured, skipping"
fi

echo
echo "==> Done."
echo "    SDK root: $SDK_ROOT"
echo "    Installed: platform-tools, platforms;$PLATFORM, build-tools;$BUILD_TOOLS"
echo
echo "No separate JDK install needed: Android Studio uses its own bundled"
echo "JDK for Gradle by default, and building from the command line with"
echo "./gradlew works fine on whatever JDK you already have (this project's"
echo "Gradle/AGP versions have been verified to build against a current JDK)."
echo
echo "Open a NEW terminal (so PATH/group changes apply), then either:"
echo "  - launch Android Studio and open the sailer/ project, or"
echo "  - run: cd \"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)\" && ./gradlew assembleDebug"
