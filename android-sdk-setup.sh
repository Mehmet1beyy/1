#!/usr/bin/env bash
# =============================================================
# Android SDK kurulum betiği (Claude Code cloud "Kurulum betiği")
# Her yeni oturumda calisir; zaten kuruluysa atlar (idempotent).
# =============================================================
set -euo pipefail

# --- Ayarlanabilir surumler ---
# ANDROID_HOME mutlak bir yol OLMALI. Ortam degiskeni olarak goreli bir deger
# (orn. baslangic '/' eksik: "root/android-sdk") gelirse, cwd'ye gore yanlis
# yere cozulur. Bu yuzden set edilmemis ya da goreli ise mutlak varsayilana
# dusuyoruz.
DEFAULT_ANDROID_HOME="$HOME/android-sdk"
case "${ANDROID_HOME:-}" in
  /*) : ;;  # zaten mutlak, oldugu gibi kullan
  "") ANDROID_HOME="$DEFAULT_ANDROID_HOME" ;;
  *)
    echo "==> UYARI: ANDROID_HOME goreli bir yol ('$ANDROID_HOME'); " \
         "mutlak varsayilana ayarlaniyor: $DEFAULT_ANDROID_HOME" >&2
    ANDROID_HOME="$DEFAULT_ANDROID_HOME"
    ;;
esac
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
BUILD_TOOLS_VERSION="35.0.0"
PLATFORM_VERSION="android-35"

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> Android SDK hedefi: $ANDROID_HOME"

# 1) Command-line tools (sdkmanager) yoksa indir+kur
if [ ! -x "$SDKM" ]; then
  echo "==> cmdline-tools indiriliyor..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmpzip="$(mktemp -d)/cmdline-tools.zip"
  curl -fsSL -o "$tmpzip" \
    "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
  unzip -q "$tmpzip" -d "$ANDROID_HOME/cmdline-tools"
  # unzip "cmdline-tools/" klasoru acar -> "latest" olarak yeniden adlandir
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f "$tmpzip"
  echo "==> cmdline-tools kuruldu."
else
  echo "==> cmdline-tools zaten kurulu, atlaniyor."
fi

# Yardimci: "yes | sdkmanager" boru hatti, sdkmanager stdin'i erken kapatinca
# `yes`'e SIGPIPE gonderir (exit 141). `set -o pipefail` altinda bu, gercek bir
# hata olmadigi halde betigi oldurur. Bu sarmalayici pipefail'i yalnizca bu
# cagri icin kapatir; boylece pipeline durumu `yes`'in degil, sdkmanager'in
# gercek cikis kodu olur.
sdkm_run() {
  local rc
  set +o pipefail
  yes 2>/dev/null | "$SDKM" "$@"
  rc=$?
  set -o pipefail
  return "$rc"
}

# 2) Lisanslari kabul et (sessiz)
sdkm_run --licenses >/dev/null 2>&1 || true

# 3) Gerekli paketler kurulu degilse kur
need_install=0
for pkg in "platform-tools" "build-tools/$BUILD_TOOLS_VERSION" "platforms/$PLATFORM_VERSION"; do
  [ -e "$ANDROID_HOME/$pkg" ] || need_install=1
done

if [ "$need_install" -eq 1 ]; then
  echo "==> SDK paketleri kuruluyor..."
  sdkm_run \
    "platform-tools" \
    "build-tools;${BUILD_TOOLS_VERSION}" \
    "platforms;${PLATFORM_VERSION}" >/dev/null
  echo "==> SDK paketleri kuruldu."
else
  echo "==> SDK paketleri zaten kurulu, atlaniyor."
fi

# 4) Ortam degiskenlerini kabuk oturumlari icin kalici yaz
PROFILE_LINE_HOME='export ANDROID_HOME="$HOME/android-sdk"'
PROFILE_LINE_ROOT='export ANDROID_SDK_ROOT="$ANDROID_HOME"'
PROFILE_LINE_PATH='export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"'
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
  touch "$rc"
  grep -qF "$PROFILE_LINE_HOME" "$rc" || {
    printf '\n# Android SDK\n%s\n%s\n%s\n' \
      "$PROFILE_LINE_HOME" "$PROFILE_LINE_ROOT" "$PROFILE_LINE_PATH" >> "$rc"
  }
done

echo "==> Tamamlandi. ANDROID_HOME=$ANDROID_HOME"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list_installed 2>/dev/null \
  | grep -E "platform-tools|build-tools|platforms;android" || true
