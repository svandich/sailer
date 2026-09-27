#!/usr/bin/env bash
set -euo pipefail

# Sets up everything needed to build sailer from the command line and
# install/debug it on a USB-connected phone (Arch Linux), without Android
# Studio. Run it yourself in a real terminal (it needs your sudo password,
# and confirmation for the one AUR package):
#
#   ./scripts/setup-cli-build.sh
#
# What it installs:
#   - jdk21-openjdk (pacman): Gradle and AGP need a JDK (17+).
#   - android-udev (pacman): udev rules so adb can talk to a USB phone
#     without root.
#   - android-sdk-cmdline-tools-latest (AUR): only used to bootstrap
#     sdkmanager. The SDK itself goes in a user-owned directory ($SDK_ROOT)
#     so Gradle can auto-download any extra components it wants without
#     sudo or group changes.
#   - SDK packages: platform-tools (adb), platforms;$PLATFORM,
#     build-tools;$BUILD_TOOLS, cmdline-tools;latest.
# It also writes local.properties (git-ignored) pointing Gradle at the SDK.

SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
PLATFORM="android-34"     # keep in sync with compileSdk in app/build.gradle.kts
BUILD_TOOLS="36.0.0"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Installing JDK 21 and Android udev rules"
sudo pacman -S --needed jdk21-openjdk android-udev

# Make sure some JDK is the system default, so `java` is on PATH for ./gradlew.
if [ -z "$(archlinux-java get 2>/dev/null)" ]; then
  sudo archlinux-java set java-21-openjdk
fi

echo "==> Reloading udev rules (so a phone plugged in now is picked up)"
sudo udevadm control --reload-rules
sudo udevadm trigger

BOOTSTRAP_SDKMANAGER="/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ] && [ ! -x "$BOOTSTRAP_SDKMANAGER" ]; then
  echo "==> Installing Android SDK command-line tools (AUR, used to bootstrap sdkmanager)"
  if command -v yay >/dev/null 2>&1; then
    yay -S --needed android-sdk-cmdline-tools-latest
  elif command -v paru >/dev/null 2>&1; then
    paru -S --needed android-sdk-cmdline-tools-latest
  else
    echo "No AUR helper (yay/paru) found; install android-sdk-cmdline-tools-latest manually." >&2
    exit 1
  fi
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || SDKMANAGER="$BOOTSTRAP_SDKMANAGER"

mkdir -p "$SDK_ROOT"
echo "==> Accepting SDK licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true

echo "==> Installing SDK packages into $SDK_ROOT"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --install \
  "cmdline-tools;latest" "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS"

echo "==> Writing $PROJECT_DIR/local.properties"
echo "sdk.dir=$SDK_ROOT" > "$PROJECT_DIR/local.properties"

RC_FILE="$HOME/.zshrc"
MARKER="# Added by sailer/scripts/setup-cli-build.sh"
if ! grep -qF "$MARKER" "$RC_FILE" 2>/dev/null; then
  echo "==> Adding ANDROID_HOME and SDK tools to PATH in $RC_FILE"
  {
    echo ""
    echo "$MARKER"
    echo "export ANDROID_HOME=\"$SDK_ROOT\""
    echo "export PATH=\"\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
  } >> "$RC_FILE"
fi

echo
echo "==> Done. java: $(java -version 2>&1 | head -1)"
echo
echo "To use a phone:"
echo "  1. On the phone: Settings > About phone > tap 'Build number' 7 times,"
echo "     then Settings > System > Developer options > enable 'USB debugging'."
echo "  2. Plug it in via USB and accept the 'Allow USB debugging?' prompt."
echo "  3. Check it shows up as 'device': $SDK_ROOT/platform-tools/adb devices"
echo
echo "Then build and install with: cd \"$PROJECT_DIR\" && ./gradlew installDebug"
