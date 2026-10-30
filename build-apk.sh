#!/usr/bin/env bash
# =============================================================
# APK derleme betigi
# Bir Gradle Android projesini derler ve uretilen .apk yolunu yazar.
#
# Kullanim:
#   ./build-apk.sh [proje-dizini] [debug|release]
#
#   proje-dizini : gradlew dosyasinin bulundugu dizin (varsayilan: .)
#   varyant      : debug (varsayilan) veya release
#
# Gereksinim: android-sdk-setup.sh daha once calismis olmali
# (ANDROID_HOME dolu ve SDK paketleri kurulu).
# =============================================================
set -euo pipefail

PROJECT_DIR="${1:-.}"
VARIANT="${2:-debug}"

# --- Ortam degiskenleri ---
# ANDROID_HOME mutlak olmali; set edilmemis ya da goreli ise mutlak varsayilana dus.
DEFAULT_ANDROID_HOME="$HOME/android-sdk"
case "${ANDROID_HOME:-}" in
  /*) : ;;
  "") ANDROID_HOME="$DEFAULT_ANDROID_HOME" ;;
  *)
    echo "==> UYARI: ANDROID_HOME goreli ('$ANDROID_HOME'); " \
         "mutlak varsayilana ayarlaniyor: $DEFAULT_ANDROID_HOME" >&2
    ANDROID_HOME="$DEFAULT_ANDROID_HOME"
    ;;
esac
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# --- On kontroller ---
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "HATA: Android SDK bulunamadi ($ANDROID_HOME)." >&2
  echo "       Once ./android-sdk-setup.sh calistirin." >&2
  exit 1
fi

cd "$PROJECT_DIR"

if [ ! -x "./gradlew" ]; then
  if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
  else
    echo "HATA: '$PROJECT_DIR' icinde gradlew bulunamadi." >&2
    echo "       Bir Gradle Android projesinin kok dizinini verin." >&2
    exit 1
  fi
fi

# --- local.properties: Gradle'a SDK yolunu bildir ---
if ! grep -qs '^sdk.dir=' local.properties 2>/dev/null; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  echo "==> local.properties yazildi (sdk.dir=$ANDROID_HOME)"
fi

# --- Gradle gorevini sec ---
case "$VARIANT" in
  debug)   TASK="assembleDebug" ;;
  release) TASK="assembleRelease" ;;
  *) echo "HATA: Gecersiz varyant '$VARIANT' (debug|release bekleniyor)." >&2; exit 1 ;;
esac

echo "==> Derleniyor: ./gradlew $TASK  (dizin: $PROJECT_DIR)"
./gradlew --no-daemon "$TASK"

# --- Uretilen APK'lari listele ---
echo "==> Derleme tamam. Uretilen APK dosyalari:"
find . -path '*/build/outputs/apk/*.apk' -newermt '-5 minutes' 2>/dev/null \
  | sort || true
find . -path '*/build/outputs/apk/*.apk' 2>/dev/null | sort
