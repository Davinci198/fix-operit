#!/usr/bin/env bash
set -e

log() {
  echo "[android-setup] $*" >&2
}

fail() {
  log "ERROR: $*"
  exit 1
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

speed_to_int() {
  local speed="$1"
  if [[ ! "$speed" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    echo 0
    return
  fi
  local speed_int="${speed%.*}"
  if [[ -z "$speed_int" ]]; then
    speed_int=0
  fi
  echo "$speed_int"
}

GRADLE_VERSION="9.1.0"
GRADLE_ROOT="${GRADLE_ROOT:-$HOME/gradle}"
GRADLE_DIST="gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${GRADLE_ROOT}/${GRADLE_DIST}-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
SCRIPT_DIR=""
export GRADLE_USER_HOME
APT_UPDATED=0

ping_host() {
  local host="$1"
  local ping_cmd=""
  if command_exists ping; then
    ping_cmd="ping"
  elif command_exists busybox; then
    ping_cmd="busybox ping"
  fi
  if [[ -z "$ping_cmd" ]]; then
    log "ping not found; skipping mirror checks"
    return 2
  fi
  # -W este iputils; busybox ping folosește -w (diferență de sintaxă)
  if [[ "$ping_cmd" == "busybox ping" ]]; then
    if $ping_cmd -c 1 -w 2 "$host" >/dev/null 2>&1; then
      log "Ping OK: $host"
      return 0
    fi
  else
    if $ping_cmd -c 1 -W 2 "$host" >/dev/null 2>&1; then
      log "Ping OK: $host"
      return 0
    fi
  fi
  log "Ping fail: $host"
  return 1
}

select_download_url() {
  local label="$1"
  local default_url="$2"
  local default_host="$3"
  shift 3
  local mirror_args=("$@")

  log "Selecting fastest mirror for $label"

  if command_exists curl; then
    local best_url="$default_url"
    local best_speed=0

    local probe_dir
    probe_dir=$(mktemp -d)

    {
      local speed
      speed=$(measure_download_speed "$default_url") || speed="0"
      printf '%s\t%s\n' "$(speed_to_int "$speed")" "$default_url" > "$probe_dir/default"
    } &

    local i=0
    local probe_idx=0
    while (( i < ${#mirror_args[@]} )); do
      local url="${mirror_args[$((i + 1))]}"
      local key="mirror_${probe_idx}"
      {
        local speed
        speed=$(measure_download_speed "$url") || speed="0"
        printf '%s\t%s\n' "$(speed_to_int "$speed")" "$url" > "$probe_dir/$key"
      } &
      i=$((i + 2))
      probe_idx=$((probe_idx + 1))
    done

    wait

    local result_file
    while IFS= read -r -d '' result_file; do
      local speed_int
      speed_int=$(cut -f1 "$result_file")
      local url
      url=$(cut -f2- "$result_file")
      if [[ -n "$speed_int" && "$speed_int" -gt "$best_speed" ]]; then
        best_speed="$speed_int"
        best_url="$url"
      fi
    done < <(find "$probe_dir" -type f -print0)

    rm -rf "$probe_dir"

    if [[ "$best_speed" -gt 0 ]]; then
      log "Fastest mirror selected for $label: $best_url (speed=${best_speed}B/s)"
      echo "$best_url"
      return 0
    fi

    log "Speed test failed for all mirrors; fallback to ping selection"
  fi

  if ping_host "$default_host"; then
    echo "$default_url"
    return 0
  fi

  local i=0
  while (( i < ${#mirror_args[@]} )); do
    local host="${mirror_args[$i]}"
    local url="${mirror_args[$((i + 1))]}"
    i=$((i + 2))
    if ping_host "$host"; then
      log "Using mirror for $label: $host"
      echo "$url"
      return 0
    fi
  done

  log "No reachable mirror; fallback to default URL for $label"
  echo "$default_url"
}

measure_download_speed() {
  local url="$1"
  # Download a small range to /dev/null and use curl's measured speed.
  # Use short timeouts to keep selection fast.
  curl -L \
    --range 0-524287 \
    --output /dev/null \
    --silent \
    --show-error \
    --connect-timeout 3 \
    --max-time 8 \
    -w "%{speed_download}" \
    "$url" 2>/dev/null
}

download_file() {
  local url="$1"
  local dest="$2"
  # Timeout opțional per-apel (secunde); fișierele mari (zip-uri Gradle/SDK)
  # au nevoie de mai mult decât default-ul de 120s pe conexiuni mobile lente.
  local max_time="${3:-120}"
  local max_retries=3
  local retry_count=0

  while [[ $retry_count -lt $max_retries ]]; do
    if command_exists curl; then
      if curl -L --connect-timeout 30 --max-time "$max_time" --retry 2 --retry-delay 3 "$url" -o "$dest"; then
        return 0
      fi
    elif command_exists wget; then
      if wget --timeout=30 --tries=3 --waitretry=3 -O "$dest" "$url"; then
        return 0
      fi
    else
      log "curl or wget is required to download files."
      exit 1
    fi

    retry_count=$((retry_count + 1))
    log "Download failed, retrying ($retry_count/$max_retries)..."
    sleep 2
  done

  log "Failed to download file after $max_retries attempts: $url"
  return 1
}

install_packages() {
  local packages=("$@")
  if command_exists apt-get; then
    local sudo_cmd=""
    if command_exists sudo; then
      sudo_cmd="sudo"
    fi
    log "Installing packages: ${packages[*]}"
    if [[ "$APT_UPDATED" -eq 0 ]]; then
      $sudo_cmd apt-get update
      APT_UPDATED=1
    fi
    $sudo_cmd apt-get install -y "${packages[@]}"
  else
    log "apt-get not found; please install: ${packages[*]}"
  fi
}

ensure_ping() {
  if command_exists ping || command_exists busybox; then
    return
  fi
  if command_exists apt-get; then
    install_packages iputils-ping
  fi
  if ! command_exists ping && ! command_exists busybox; then
    log "ping still unavailable; mirror selection will be skipped"
  fi
}

ensure_java() {
  if command_exists java; then
    local version
    version=$(java -version 2>&1 | sed -n 's/.*version "\(.*\)".*/\1/p')
    local major=${version%%.*}
    if [[ "$major" == "1" ]]; then
      major=$(echo "$version" | cut -d. -f2)
    fi
    if [[ -n "$major" && "$major" -ge 17 ]]; then
      log "Java $version detected"
      return
    fi
    log "Java version $version is below 17; upgrading"
  else
    log "Java not found; installing OpenJDK 17"
  fi
  install_packages openjdk-17-jdk
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME" ]]; then
    return
  fi
  if command_exists java; then
    local java_path
    java_path=$(readlink -f "$(command -v java)")
    JAVA_HOME=$(dirname "$(dirname "$java_path")")
    export JAVA_HOME
  fi
}

ensure_android_tools() {
  ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
  export ANDROID_HOME
  export ANDROID_SDK_ROOT="$ANDROID_HOME"

  if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "Downloading Android command line tools"
    install_packages unzip
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    local tmp_dir
    tmp_dir=$(mktemp -d)
    local zip_path="$tmp_dir/cmdline-tools.zip"
    local cmdline_url
    cmdline_url=$(select_download_url \
      "Android command line tools" \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
      "dl.google.com" \
      "mirrors.cloud.tencent.com" "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip")
    download_file "$cmdline_url" "$zip_path" 900
    unzip -q "$zip_path" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp_dir"
  fi

  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
  log "Installing Android SDK packages"
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.0.12077973"
}

ensure_gradle() {
  install_packages unzip
  mkdir -p "$GRADLE_ROOT"
  if command_exists gradle; then
    local installed_version
    installed_version=$(gradle --version 2>/dev/null | grep -oP 'Gradle \K[0-9.]+' | head -1 || true)
    if [[ -n "$installed_version" ]]; then
      log "System Gradle detected: $installed_version (still preparing local Gradle zip for wrapper cache)"
    else
      log "System Gradle detected (version parse failed); preparing local Gradle zip for wrapper cache"
    fi
  fi

  if [[ ! -f "$GRADLE_ZIP" ]]; then
    log "Downloading Gradle ${GRADLE_VERSION}"
    local gradle_url
    gradle_url=$(select_download_url \
      "Gradle distribution" \
      "https://services.gradle.org/distributions/${GRADLE_DIST}-bin.zip" \
      "services.gradle.org" \
      "mirrors.cloud.tencent.com" "https://mirrors.cloud.tencent.com/gradle/${GRADLE_DIST}-bin.zip")
    download_file "$gradle_url" "$GRADLE_ZIP" 900
  else
    log "Gradle zip already present: $GRADLE_ZIP"
  fi

  if [[ ! -d "$GRADLE_ROOT/$GRADLE_DIST" ]]; then
    log "Extracting Gradle ${GRADLE_VERSION}"
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_ROOT"
  else
    log "Local Gradle already extracted: $GRADLE_ROOT/$GRADLE_DIST"
  fi

  export GRADLE_HOME="$GRADLE_ROOT/$GRADLE_DIST"
  export PATH="$GRADLE_HOME/bin:$PATH"
}

update_gradle_wrapper_properties() {
  local wrapper_file="gradle/wrapper/gradle-wrapper.properties"
  if [[ ! -f "$wrapper_file" ]]; then
    return
  fi
  if [[ ! -f "$GRADLE_ZIP" ]]; then
    log "Gradle zip not found; keeping existing wrapper distributionUrl"
    return
  fi

  local gradle_zip_abs="$GRADLE_ZIP"
  if command_exists readlink; then
    gradle_zip_abs=$(readlink -f "$GRADLE_ZIP" 2>/dev/null || echo "$GRADLE_ZIP")
  fi
  local file_url="file\\://$gradle_zip_abs"

  if grep -q '^distributionUrl=' "$wrapper_file"; then
    sed -i "s|^distributionUrl=.*|distributionUrl=$file_url|" "$wrapper_file"
  else
    echo "distributionUrl=$file_url" >> "$wrapper_file"
  fi
  log "Wrapper distributionUrl set to local file: $gradle_zip_abs"

  # Protecție anti-commit accidental: distributionUrl=file:// este valabil DOAR
  # local (pe telefon). Marcam fișierul skip-worktree dacă suntem într-un repo git,
  # altfel modificarea locală ar ajunge în repo și ar rupe CI / alte mașini.
  if [[ ! -f "$wrapper_file.setup-orig" ]]; then
    cp "$wrapper_file" "$wrapper_file.setup-orig"
    log "Backup original wrapper properties: $wrapper_file.setup-orig"
  fi
  if [[ -d .git ]] && command_exists git; then
    if git ls-files --error-unmatch "$wrapper_file" >/dev/null 2>&1; then
      git update-index --skip-worktree "$wrapper_file" 2>/dev/null \
        && log "git skip-worktree activ pentru $wrapper_file (nu se comite accidental)"
    fi
  fi
}

warmup_gradle_wrapper_cache() {
  if [[ ! -x "./gradlew" ]]; then
    log "gradlew not found; skipping wrapper cache warm-up"
    return 0
  fi
  if [[ ! -f "gradle/wrapper/gradle-wrapper.properties" ]]; then
    log "gradle-wrapper.properties not found; skipping wrapper cache warm-up"
    return 0
  fi
  log "Warming Gradle wrapper cache"
  if ! ./gradlew --version --no-daemon >/dev/null; then
    log "Wrapper cache warm-up failed; continuing"
    return 1
  fi
}

restore_gradle_properties() {
  # MERGE, nu overwrite: păstrăm setările existente (ex. android.aapt2FromMavenOverride,
  # jvmargs personalizate anti-OOM) și adăugăm DOAR cheile lipsă din template.
  # Scriem template-ul integral doar dacă fișierul nu există deloc.
  local props="gradle.properties"
  # Perechi complete cheie=valoare: dacă lipsesc, se adaugă cu valoarea recomandată.
  # Dacă cheia există deja (chiar cu altă valoare, ex. jvmargs anti-OOM), NU se atinge.
  local -a required_keys=(
    "org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=1536m"
    "kotlin.daemon.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8"
    "org.gradle.parallel=true"
    "org.gradle.workers.max=4"
    "org.gradle.caching=true"
    "org.gradle.configureondemand=true"
    "android.useAndroidX=true"
    "kotlin.code.style=official"
    "android.nonTransitiveRClass=true"
    "android.builder.cmake.inCMakeCacheDir=false"
    "android.aapt2.process.daemon=false"
  )

  if [[ ! -f "$props" ]]; then
    log "gradle.properties lipsă - scriu template-ul complet"
    cat > "$props" <<'EOF'
# Project-wide Gradle settings.
# IDE (e.g. Android Studio) users:
# Gradle settings configured through the IDE *will override*
# any settings specified in this file.
# For more details on how to configure your build environment visit
# http://www.gradle.org/docs/current/userguide/build_environment.html

# ============================================================================
# JVM & Daemon Configuration - Critical for native module builds (MNN monolith)
# ============================================================================
# Main Gradle daemon JVM arguments (6GB heap + 1.5GB metaspace for heavy CMake)
org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=1536m

# Kotlin daemon for faster compilation (3GB heap)
kotlin.daemon.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8

# ============================================================================
# Parallel & Build Cache Configuration
# ============================================================================
# Enable parallel builds for faster compilation (with decoupled projects)
org.gradle.parallel=true

# Max workers for CMake/NDK parallel compilation (balance: CPU cores / memory)
org.gradle.workers.max=4

# Build cache for incremental builds (avoid recompiling unchanged code)
org.gradle.caching=true

# Configure on demand: only build requested projects (faster for multi-module)
org.gradle.configureondemand=true

# ============================================================================
# Android Build Tools Configuration
# ============================================================================
# AndroidX support (required for modern Android libraries)
android.useAndroidX=true

# Kotlin code style
kotlin.code.style=official

# Namespacing R classes per library (reduces APK size)
android.nonTransitiveRClass=true

# ============================================================================
# NDK & CMake Configuration
# ============================================================================
# Optimize CMake cache directory (avoid proot issues)
android.builder.cmake.inCMakeCacheDir=false

# ============================================================================
# Proot/Termux Compatibility Settings
# ============================================================================
# Disable AAPT2 daemon mode to prevent "Daemon startup failed" errors in proot environment
android.aapt2.process.daemon=false
EOF
    log "gradle.properties creat (template complet)"
    return 0
  fi

  local appended=0
  local entry key
  for entry in "${required_keys[@]}"; do
    key="${entry%%=*}"
    if ! grep -q "^${key}=" "$props"; then
      echo "$entry" >> "$props"
      appended=$((appended + 1))
    fi
  done

  log "gradle.properties existent păstrat; $appended chei lipsă adăugate"
}

restore_gradlew_bat() {
  cat > gradlew.bat <<'EOF'
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
EOF
}

update_local_properties() {
  local sdk_dir="$ANDROID_HOME"
  cat > local.properties <<EOF
## This file is automatically generated by Android Studio.
# Do not modify this file -- YOUR CHANGES WILL BE ERASED!
#
# This file should *NOT* be checked into Version Control Systems,
# as it contains information specific to your local configuration.
#
# Location of the SDK. This is only used by Gradle.
# For customization when using a Version Control System, please read the
# header note.
sdk.dir=$sdk_dir
EOF
}

configure_env_persistence() {
  local bashrc="$HOME/.bashrc"
  touch "$bashrc"
  if ! grep -q "operit android env" "$bashrc"; then
    cat >> "$bashrc" <<EOF
# >>> operit android env >>>
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$JAVA_HOME/bin:\$PATH
export GRADLE_USER_HOME=$GRADLE_USER_HOME
export GRADLE_HOME=${GRADLE_HOME:-$HOME/gradle/gradle-9.1.0}
export PATH=\$GRADLE_HOME/bin:\$PATH
# <<< operit android env <<<
EOF
    log "Environment variables appended to ~/.bashrc"
  else
    log "Environment variables already configured in ~/.bashrc"
  fi
}

warmup_gradle_cache_for_aapt2() {
  local gradle_cmd="$GRADLE_HOME/bin/gradle"
  if [[ ! -x "$gradle_cmd" ]]; then
    log "Local Gradle not found: $gradle_cmd"
    return 1
  fi
  log "Running warm-up Gradle task to resolve and execute AAPT2"
  # --rerun (per-task, Gradle 7.6+) în loc de --rerun-tasks (global) — evită
  # re-executarea tuturor task-urilor; doar processDebugResources e rulat din nou.
  if ! "$gradle_cmd" --no-daemon :app:processDebugResources --rerun; then
    log "AAPT2 pre-replace warm-up failed; continuing to patch aapt2"
    return 1
  fi
}

warmup_gradle_cache_after_aapt2_replace() {
  local gradle_cmd="$GRADLE_HOME/bin/gradle"
  if [[ ! -x "$gradle_cmd" ]]; then
    log "Local Gradle not found: $gradle_cmd"
    return 1
  fi
  log "Running post-replace warm-up to ensure patched AAPT2 is used"
  if ! "$gradle_cmd" --no-daemon :app:processDebugResources --rerun; then
    log "AAPT2 post-replace warm-up failed; setup will still continue"
    return 1
  fi
}

replace_aapt2() {
  local bundled_aapt2="$SCRIPT_DIR/tools/aapt2/aapt2-arm64-v8a"
  local expected_sha256="e5b5ff7f0d4f6ecd7fa5d05d77fed3f09f6f1bf80f078b8aada82bc578848561"
  if [[ ! -f "$bundled_aapt2" ]]; then
    fail "Bundled ARM64 aapt2 not found: $bundled_aapt2"
  fi

  local actual_sha256
  actual_sha256=$(sha256sum "$bundled_aapt2" | awk '{print $1}')
  if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    fail "Bundled ARM64 aapt2 checksum mismatch: expected $expected_sha256, got $actual_sha256"
  fi

  local tmp_dir
  tmp_dir=$(mktemp -d)
  local aapt2_path="$tmp_dir/aapt2"
  log "Using bundled ARM64 aapt2 from template"
  cp "$bundled_aapt2" "$aapt2_path"
  chmod +x "$aapt2_path"

  # Patch-uim aapt2 în TOATE versiunile de build-tools prezente (nu doar 35.0.0 —
  # sdkmanager poate instala 36.0.0, iar AGP folosește aapt2 din cache/transforms oricum)
  local bt_dir_aapt2
  for bt_dir_aapt2 in "$ANDROID_HOME"/build-tools/*/; do
    if [[ -d "$bt_dir_aapt2" ]]; then
      cp "$aapt2_path" "$bt_dir_aapt2/aapt2"
      log "Replaced SDK build-tools aapt2: $bt_dir_aapt2"
    fi
  done

  local gradle_cache_root="$GRADLE_USER_HOME/caches"
  local gradle_aapt_dir="$gradle_cache_root/modules-2/files-2.1/com.android.tools.build/aapt2"
  if [[ -d "$gradle_aapt_dir" ]]; then
    local updated_jar_count=0
    while IFS= read -r -d '' jar_path; do
      local jar_dir
      jar_dir=$(dirname "$jar_path")
      cp "$aapt2_path" "$jar_dir/aapt2"
      (cd "$jar_dir" && zip -q -f "$(basename "$jar_path")" aapt2)
      updated_jar_count=$((updated_jar_count + 1))
    done < <(find "$gradle_aapt_dir" -name "aapt2-*-linux.jar" -print0)
    log "Updated Gradle cache aapt2 jars: $updated_jar_count"
  else
    log "Gradle aapt2 module cache not found: $gradle_aapt_dir"
  fi

  local updated_transform_count=0
  while IFS= read -r -d '' transforms_dir; do
    while IFS= read -r -d '' transformed_aapt2; do
      cp "$aapt2_path" "$transformed_aapt2"
      updated_transform_count=$((updated_transform_count + 1))
    done < <(find "$transforms_dir" -name "aapt2" -type f -print0 2>/dev/null || true)
  done < <(find "$gradle_cache_root" -maxdepth 1 -type d -name "transforms-*" -print0 2>/dev/null || true)
  if [[ "$updated_transform_count" -gt 0 ]]; then
    log "Updated transformed aapt2 binaries: $updated_transform_count"
  else
    log "No transformed aapt2 binaries found under: $gradle_cache_root"
  fi

  rm -rf "$tmp_dir"
}

# ------------------------------------------------------------
# ARM64 NDK: inlocuim linkerele x86-64 cu LLVM 18 nativ
# Rezolva: ld/ld.lld/lld + llvm-ar/llvm-ranlib crash pe ARM64,
#          --record-libdeps (flag LLVM vs GNU ar)
# ------------------------------------------------------------
ensure_ndk_arm64_toolchain() {
  local ndk_root
  ndk_root=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
  if [[ -z "$ndk_root" ]]; then
    log "NDK not found; skipping ARM64 toolchain fix"
    return
  fi
  local ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
  local llvm_bin="/usr/lib/llvm-18/bin"
  if [[ ! -d "$llvm_bin" ]]; then
    log "LLVM 18 not found; installing llvm-18 lld-18"
    install_packages llvm-18 lld-18
  fi
  local sudo_cmd=""
  if command_exists sudo; then
    sudo_cmd="sudo"
  fi
  log "NDK: $ndk_root"
  if [[ -f "$llvm_bin/ld.lld" ]]; then
    $sudo_cmd ln -sf "$llvm_bin/ld.lld" "$ndk_bin/ld.lld"
    $sudo_cmd ln -sf "$llvm_bin/ld.lld" "$ndk_bin/ld"
    $sudo_cmd ln -sf "$llvm_bin/ld.lld" "$ndk_bin/lld" 2>/dev/null || true
    log "Replaced NDK linker (ld/ld.lld/lld) with ARM64 LLVM 18"
  fi
  for tool in llvm-ar llvm-ranlib llvm-strip; do
    if [[ -f "$llvm_bin/$tool" ]]; then
      $sudo_cmd ln -sf "$llvm_bin/$tool" "$ndk_bin/$tool"
    fi
  done
  $sudo_cmd chmod +x "$ndk_bin"/ld* "$ndk_bin"/lld* "$ndk_bin"/llvm-* 2>/dev/null || true
  log "Replaced NDK llvm-ar/llvm-ranlib/llvm-strip with ARM64 LLVM 18"
}

# ------------------------------------------------------------
# ARM64 NDK sysroot: stub-uri libgcc/libunwind/libatomic
# Rezolva: bad_array_new_length, operator new(align_val_t)
#          NDK 27 cauta aceste lib-uri; lipsa lor => link fail
# ------------------------------------------------------------
patch_ndk_sysroot_libs() {
  local ndk_root
  ndk_root=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
  if [[ -z "$ndk_root" ]]; then
    log "NDK not found; skipping sysroot lib patch"
    return
  fi
  local sysroot="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
  if [[ ! -d "$sysroot" ]]; then
    log "NDK sysroot not found; skipping sysroot lib patch"
    return
  fi
  local sudo_cmd=""
  if command_exists sudo; then
    sudo_cmd="sudo"
  fi
  local patched=0
  for abi_dir in "$sysroot"/usr/lib/*/; do
    [[ -d "$abi_dir" ]] || continue
    for lib in libgcc.a libunwind.a libatomic.a; do
      if [[ ! -f "$abi_dir/$lib" ]]; then
        if $sudo_cmd touch "$abi_dir/$lib" 2>/dev/null; then
          patched=$((patched + 1))
        fi
      fi
    done
  done
  log "NDK sysroot stub libs patched: $patched"
}

# ------------------------------------------------------------
# AIDL x86-64 -> wrapper C + qemu-x86_64 (daca e cazul)
#   + genereaza IAccessibilityProvider (AGP nu il genereaza)
# ------------------------------------------------------------
fix_aidl_arm64() {
  local bt_dir
  bt_dir=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
  if [[ -z "$bt_dir" ]]; then
    log "build-tools not found; skipping AIDL fix"
    return
  fi
  local aidl_bin="$bt_dir/aidl"
  if [[ ! -f "$aidl_bin" ]]; then
    log "aidl not found in $bt_dir; skipping AIDL fix"
    return
  fi
  if ! command_exists gcc; then
    install_packages gcc
  fi
  if ! command_exists qemu-x86_64; then
    install_packages qemu-user
  fi
  if file "$aidl_bin" | grep -q "x86-64"; then
    local sudo_cmd=""
    if command_exists sudo; then
      sudo_cmd="sudo"
    fi
    if [[ ! -f "$aidl_bin.orig" ]]; then
      $sudo_cmd cp "$aidl_bin" "$aidl_bin.orig"
      log "Backup AIDL original: $aidl_bin.orig"
    fi
    local tmp_c
    tmp_c=$(mktemp)
    cat > "$tmp_c" <<EOF
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    const char *bin = "$aidl_bin.orig";
    char **args = malloc((argc + 3) * sizeof(char *));
    int n = 0;
    args[n++] = "qemu-x86_64";
    args[n++] = (char *)bin;
    for (int i = 1; i < argc; i++) args[n++] = argv[i];
    args[n] = NULL;
    execv("/usr/bin/qemu-x86_64", args);
    perror("execv qemu");
    return 127;
}
EOF
    local tmp_wrap
    tmp_wrap=$(mktemp)
    if gcc -O2 -o "$tmp_wrap" "$tmp_c"; then
      $sudo_cmd cp "$tmp_wrap" "$aidl_bin"
      $sudo_cmd chmod +x "$aidl_bin"
      log "Replaced AIDL with qemu wrapper: $aidl_bin"
    else
      log "gcc wrapper build failed; keeping original AIDL"
    fi
    rm -f "$tmp_c" "$tmp_wrap"
  else
    log "AIDL already ARM64 native: $aidl_bin"
  fi

  # Genereaza IAccessibilityProvider + IAccessibilityEventCallback (AGP nu le
  # genereaza din .aidl - atat pentru cloneRelease cat si pentru variantele
  # standard/release). Fisierele sunt surse persistente in app/src/main/java si
  # nu dispar la clean. Folosim aidl ARM64 nativ cu -I import path.
  local aidl_native=""
  local ver
  for ver in 36.0.0 35.0.0 34.0.0; do
    local cand="$ANDROID_HOME/build-tools/$ver/aidl"
    if [[ -f "$cand" ]] && file "$cand" | grep -qE "ARM aarch64|ARM64"; then
      aidl_native="$cand"
      log "aidl ARM64 nativ gasit: $cand"
      break
    fi
  done
  if [[ -z "$aidl_native" ]] && [[ -f "$ANDROID_HOME/build-tools/36.0.0/aidl" ]]; then
    aidl_native="$ANDROID_HOME/build-tools/36.0.0/aidl"
    log "Folosesc aidl 36.0.0 (poate fi wrapper)"
  fi

  local aidl_src_dir="app/src/main/aidl"
  local aidl_dst_dir="app/src/main/java"
  if [[ -n "$aidl_native" ]] && [[ -d "$aidl_src_dir" ]]; then
    log "Generare IAccessibilityProvider + IAccessibilityEventCallback ..."
    "$aidl_native" --lang=java -I "$aidl_src_dir" -o "$aidl_dst_dir" \
      "$aidl_src_dir/com/ai/assistance/operit/provider/IAccessibilityProvider.aidl" || \
      log "IAccessibilityProvider generation failed (non-fatal)"
    "$aidl_native" --lang=java -I "$aidl_src_dir" -o "$aidl_dst_dir" \
      "$aidl_src_dir/com/ai/assistance/operit/provider/IAccessibilityEventCallback.aidl" || \
      log "IAccessibilityEventCallback generation failed (non-fatal)"
    if [[ -f "$aidl_dst_dir/com/ai/assistance/operit/provider/IAccessibilityProvider.java" ]]; then
      log "OK: IAccessibilityProvider.java generat ($(stat -c%s "$aidl_dst_dir/com/ai/assistance/operit/provider/IAccessibilityProvider.java") bytes)"
    fi
    if [[ -f "$aidl_dst_dir/com/ai/assistance/operit/provider/IAccessibilityEventCallback.java" ]]; then
      log "OK: IAccessibilityEventCallback.java generat ($(stat -c%s "$aidl_dst_dir/com/ai/assistance/operit/provider/IAccessibilityEventCallback.java") bytes)"
    fi
  else
    log "!! Nu am gasit aidl ARM64 - generare manuala SKIP (build-ul va pica daca lipsesc .java-urile)"
  fi
}

# ------------------------------------------------------------
# Librarii native locale: dragonbones AAR prebuilt v1.1.0
#   - offline: copia din tools/dragonbones/ (inclus in repo)
#   - fallback: download din release-ul oficial GitHub
#   + ffmpeg-kit-local.aar (optional, dar verificat)
# ------------------------------------------------------------
ensure_native_libs() {
  mkdir -p app/libs
  local bundled_dragonbones="$SCRIPT_DIR/tools/dragonbones/dragonbones-release.aar"
  local expected_sha256="639238ed2d6d2c68fb7f4954c809475aaee25e4c5eeaebfcc690f53d7f775807"
  if [[ ! -f "app/libs/dragonbones-release.aar" ]]; then
    if [[ -f "$bundled_dragonbones" ]]; then
      local actual_sha256
      actual_sha256=$(sha256sum "$bundled_dragonbones" | awk '{print $1}')
      if [[ "$actual_sha256" == "$expected_sha256" ]]; then
        cp "$bundled_dragonbones" "app/libs/dragonbones-release.aar"
        log "dragonbones-release.aar copiat din tools/dragonbones (offline, SHA256 OK)"
      else
        log "dragonbones bundled checksum mismatch: $actual_sha256 != $expected_sha256"
      fi
    fi
  fi
  if [[ ! -f "app/libs/dragonbones-release.aar" ]]; then
    local dragonbones_url="https://github.com/Davinci198/dragonbones-aar-prebuilt/releases/download/v1.1.0/dragonbones-release.aar"
    log "Downloading dragonbones-release.aar v1.1.0 (2.49MB)"
    if download_file "$dragonbones_url" "app/libs/dragonbones-release.aar"; then
      log "dragonbones-release.aar downloaded from GitHub release"
    else
      log "dragonbones download failed; foloseste AAR-ul din repo-ul dragonbones-aar-prebuilt"
    fi
  else
    log "dragonbones-release.aar already present"
  fi
  if [[ ! -f "app/libs/ffmpeg-kit-local.aar" ]]; then
    log "ffmpeg-kit-local.aar missing - build-ul merge si fara el (FFmpeg optional)"
    log "Pentru suport FFmpeg complet, copiaza ffmpeg-kit-local.aar in app/libs/"
  else
    log "ffmpeg-kit-local.aar already present"
  fi
}

# ------------------------------------------------------------
# Pachete UI (assets/packages/*.toolpkg) - CRITICE pentru butoane.
#   - gen: python3 tools/example_packages/sync_example_packages.py
#         (copiaza .js + pacheaza .toolpkg din examples/ in assets/packages/)
#   - daca lipsesc, build-ul iese FARA butoanele dependente de pachete
#     (bug observat pe build local: 17 fisiere lipsa in APK, UI fara butoane)
#   - aici: ruleaza sync-ul (daca e posibil) + valideaza ca toate cele 11
#     pachete .toolpkg exista; daca nu, FAIL cu mesaj clar.
# ------------------------------------------------------------
ensure_ui_packages() {
  local sync_script="tools/example_packages/sync_example_packages.py"
  local packages_dir="app/src/main/assets/packages"
  # Cele 11 pachete toolpkg care trebuie sa existe mereu in assets
  local required=(
    apktool context_limiter_c deepsearching linux_ssh message_insert
    plan_mode qqbot remote_operit thinking_guidance windows_control worldbook
  )
  local missing=0

  log "ensure_ui_packages: verific pachetele UI (toolpkg)..."

  # Pas 1: ruleaza sync-ul oficial ca sa (re)genereze .toolpkg din examples/
  if [[ -f "$sync_script" ]]; then
    if command -v python3 >/dev/null 2>&1; then
      log "ensure_ui_packages: rulez sync_example_packages.py (genereaza .toolpkg din examples/)..."
      if python3 "$sync_script" --no-hot-reload >/tmp/sync_ui_packages.log 2>&1; then
        log "ensure_ui_packages: sync OK - $(grep -o 'packed=[0-9]*' /tmp/sync_ui_packages.log | head -1), $(grep -o 'copied=[0-9]*' /tmp/sync_ui_packages.log | head -1)"
      else
        log "WARN: sync_example_packages.py a esuat (vezi /tmp/sync_ui_packages.log) - continui cu validare"
      fi
    else
      log "WARN: python3 lipseste - nu pot rula sync; validez doar pachetele existente"
    fi
  else
    log "WARN: $sync_script lipseste - nu pot rula sync; validez doar pachetele existente"
  fi

  # Pas 2: validare - fiecare pachet .toolpkg trebuie sa existe si sa aiba dimensiune > 0
  for pkg in "${required[@]}"; do
    if [[ ! -f "$packages_dir/$pkg.toolpkg" || ! -s "$packages_dir/$pkg.toolpkg" ]]; then
      log "MISSING UI PACKAGE: $packages_dir/$pkg.toolpkg"
      missing=1
    fi
  done

  if [[ $missing -ne 0 ]]; then
    fail "Pachete UI lipsa (toolpkg) - butoanele NU vor aparea in APK! Ruleaza: python3 tools/example_packages/sync_example_packages.py --no-hot-reload"
  fi
  log "ensure_ui_packages: OK - toate cele 11 pachete toolpkg sunt prezente"
}

main() {
  SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  cd "$SCRIPT_DIR"

  if [[ -f "./gradlew" ]]; then
    chmod +x "./gradlew"
  fi

  install_packages wget curl unzip zip
  ensure_ping
  ensure_java
  resolve_java_home
  ensure_android_tools
  ensure_gradle
  update_gradle_wrapper_properties
  if ! warmup_gradle_wrapper_cache; then
    log "Ignoring wrapper warm-up error and continuing"
  fi
  restore_gradle_properties
  # Adaugă override-ul aapt2 ARM64 în gradle.properties DOAR dacă wrapper-ul local există.
  # Nu e în gradle.properties urmărit (strică CI: runnerul nu are binarul). CI rămâne curat.
  if [[ -f /opt/aapt2-custom/aapt2 ]]; then
    if ! grep -q '^android.aapt2FromMavenOverride=' gradle.properties 2>/dev/null; then
      printf '\n# ARM64 aapt2 override — doar local (generat de setup)\nandroid.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2\n' >> gradle.properties
      log "Injected android.aapt2FromMavenOverride into gradle.properties (local ARM64 aapt2)"
    fi
  fi
  restore_gradlew_bat
  update_local_properties
  ensure_native_libs
  ensure_ui_packages
  ensure_ndk_arm64_toolchain
  patch_ndk_sysroot_libs
  fix_aidl_arm64
  if ! warmup_gradle_cache_for_aapt2; then
    log "Ignoring pre-replace warm-up error and continuing to patch aapt2"
  fi
  replace_aapt2
  if ! warmup_gradle_cache_after_aapt2_replace; then
    log "Ignoring post-replace warm-up error and continuing"
  fi
  configure_env_persistence

  log "Android environment setup complete"
  log "Reload shell or run: source ~/.bashrc"
}

main "$@"
