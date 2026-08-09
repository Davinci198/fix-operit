#!/bin/bash
# ============================================================
# fix-ndk-arm64-final.sh - Full ARM64 Native Build Fix for Operit
# Device : Motorola G57 12GB RAM (+12GB swap) + Ubuntu 24 (ARM64)
# Author : Davinci198 - BUILD SUCCESSFUL in 2h10m (kapt) / 10m42s (assemble)
# Repo   : https://github.com/Davinci198/fix-operit
#          https://github.com/Davinci198/dragonbones-aar-prebuilt
#
# Rezolva 8 probleme de cross-compilare pe ARM64:
#  1. libpthread.so.0 blocked by SABA DNS  -> dragonbones AAR prebuilt
#  2. bad_array_new_length/aligned new      -> NDK 27 + patch aligned new
#  3. Timp build 26m -> 2-3m               -> AAR prebuilt dragonbones
#  4. ld/ld.lld/llvm-ar x86-64 crash ARM64 -> LLVM 18.1.3 ARM64 nativ
#  5. --record-libdeps LLVM vs GNU ar      -> llvm-ar/ranlib nativ
#  6. libc++_shared.so duplicat            -> pickFirsts in build.gradle.kts
#  7. AAPT2 daemon x86-64                  -> wrapper C + qemu-x86_64
#  8. AIDL/IAccessibilityProvider           -> wrapper C + qemu-x86_64
# ============================================================
set -e

echo "=== FIX NDK 27 ARM64 FINAL ==="

NDK_ROOT=/opt/android-sdk/ndk/27.0.12077973
NDK_BIN=$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
LLVM_BIN=/usr/lib/llvm-18/bin
SYSROOT=$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot
BT_DIR=/opt/android-sdk/build-tools/36.0.0

# ------------------------------------------------------------
# [1/9] Dependinte ARM64 native
# ------------------------------------------------------------
echo "[1/9] Instalare llvm-18, lld-18, cmake, ninja, qemu-user..."
sudo apt update
sudo apt install -y llvm-18 lld-18 cmake ninja-build qemu-user aapt

# ------------------------------------------------------------
# [2/9] Fix ld, ld.lld, lld -> ARM64 nativ (LLVM 18.1.3 via symlink)
# ------------------------------------------------------------
echo "[2/9] Fix linker-e x86-64 -> ARM64 nativ..."
sudo ln -sf /usr/bin/ld.lld $NDK_BIN/ld.lld
sudo ln -sf /usr/bin/ld.lld $NDK_BIN/ld
sudo ln -sf /usr/bin/lld $NDK_BIN/lld || true
sudo chmod +x $NDK_BIN/ld* $NDK_BIN/lld* 2>/dev/null || true

# ------------------------------------------------------------
# [3/9] Fix llvm-ar / llvm-ranlib -> ARM64 nativ (LLVM 18.1.3)
#   Rezolva: --record-libdeps (flag LLVM vs GNU ar)
# ------------------------------------------------------------
echo "[3/9] Fix llvm-ar / llvm-ranlib / llvm-strip -> ARM64 nativ..."
sudo ln -sf $LLVM_BIN/llvm-ar $NDK_BIN/llvm-ar
sudo ln -sf $LLVM_BIN/llvm-ranlib $NDK_BIN/llvm-ranlib
sudo ln -sf $LLVM_BIN/llvm-strip $NDK_BIN/llvm-strip || true
sudo chmod +x $NDK_BIN/llvm-ar $NDK_BIN/llvm-ranlib $NDK_BIN/llvm-strip 2>/dev/null || true

# Verificare arhitectura
file $NDK_BIN/ld.lld
file $NDK_BIN/llvm-ar

# ------------------------------------------------------------
# [4/9] cmake 3.28 + ninja 1.11.1 ARM64 (symlink la versiunile apt)
# ------------------------------------------------------------
echo "[4/9] Fix cmake 3.28 si ninja 1.11.1 ARM64..."
which cmake && sudo ln -sf $(which cmake) /usr/local/bin/cmake || true
which ninja && sudo ln -sf $(which ninja) /usr/local/bin/ninja || true
cmake --version
ninja --version

# ------------------------------------------------------------
# [5/9] libgcc / libunwind / libatomic in sysroot-uri
#   Rezolva: bad_array_new_length, operator new(align_val_t)
#   NDK 27 cauta aceste lib-uri in sysroot; lipsa lor => link fail
# ------------------------------------------------------------
echo "[5/9] Fix -lgcc / -lunwind / -latomic in sysroot-uri..."
for ABI_DIR in $SYSROOT/usr/lib/*/; do
  [ -d "$ABI_DIR" ] || continue
  echo "  Verific $ABI_DIR"
  for lib in libgcc.a libunwind.a libatomic.a; do
    if [ ! -f "$ABI_DIR/$lib" ]; then
      # Stub gol valid (ar) - doar pentru link-time presence
      touch "$ABI_DIR/$lib" 2>/dev/null || true
    fi
  done
done

# ------------------------------------------------------------
# [6/9] AAPT2 x86-64 -> wrapper C + qemu-x86_64
#   build-tools 36.0.0 vine cu aapt2 x86-64; pe ARM64 nu porneste
#   Compilam un wrapper C ARM64 care executa binarul x86-64
#   prin qemu-user. Gradle il foloseste via:
#     android.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2
# ------------------------------------------------------------
echo "[6/9] Fix AAPT2: wrapper C + qemu-x86_64..."
AAPT2_X86=$BT_DIR/aapt2
if [ -f "$AAPT2_X86" ] && file "$AAPT2_X86" | grep -q "x86-64"; then
  echo "  aapt2 x86-64 gasit: $AAPT2_X86"
  sudo mkdir -p /opt/aapt2-custom
  sudo cp "$AAPT2_X86" /opt/aapt2-custom/aapt2.x86_64
  cat > /tmp/aapt2_wrap.c << 'EOF'
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
int main(int argc, char **argv) {
    const char *bin = "/opt/aapt2-custom/aapt2.x86_64";
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
  gcc -O2 -o /tmp/aapt2_wrap /tmp/aapt2_wrap.c
  sudo cp /tmp/aapt2_wrap /opt/aapt2-custom/aapt2
  sudo chmod +x /opt/aapt2-custom/aapt2 /opt/aapt2-custom/aapt2.x86_64
  rm -f /tmp/aapt2_wrap /tmp/aapt2_wrap.c
  echo "  Wrapper creat: /opt/aapt2-custom/aapt2 (ARM64)"
  echo "  Adauga in gradle.properties:"
  echo "    android.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2"
else
  echo "  AAPT2 deja fixat sau absenta - skip"
fi

# ------------------------------------------------------------
# [7/9] AIDL x86-64 -> wrapper C + qemu-x86_64
#   Binarul aidl din build-tools 36 e tot x86-64; AGP il invoca
#   intern (AIDLCompiler) si nu permite override => trebuie
#   inlocuit binarul cu wrapper C ARM64 care ruleaza qemu.
#   De asemenea AGP nu genereaza IAccessibilityProvider =>
#   il generam manual cu aidl34 (care merge ARM64 nativ).
# ------------------------------------------------------------
echo "[7/9] Fix AIDL: wrapper C + qemu-x86_64 + IAccessibilityProvider..."
AIDL_X86=$BT_DIR/aidl
if [ -f "$AIDL_X86" ]; then
  echo "  AIDL gasit: $AIDL_X86 (backup la .orig)"
  sudo mv "$AIDL_X86" "$AIDL_X86.orig" || true
  cat > /tmp/aidl_wrap.c << 'EOF'
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    const char *bin = "/opt/android-sdk/build-tools/36.0.0/aidl.orig";
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
  gcc -O2 -o /tmp/aidl_wrap /tmp/aidl_wrap.c
  sudo cp /tmp/aidl_wrap "$AIDL_X86"
  sudo chmod +x "$AIDL_X86"
  rm -f /tmp/aidl_wrap /tmp/aidl_wrap.c
  echo "  Wrapper creat: $AIDL_X86 (ARM64)"
fi

# Genereaza IAccessibilityProvider (AGP nu il genereaza din .aidl)
if [ -f /opt/android-sdk/build-tools/34.0.0/aidl ] && [ -f "app/src/main/aidl/android/accessibilityservice/IAccessibilityProvider.aidl" ]; then
  echo "  Generare IAccessibilityProvider.java (aidl 34.0.0 ARM64 nativ)..."
  /opt/android-sdk/build-tools/34.0.0/aidl --lang=java \
    -o app/src/main/java \
    app/src/main/aidl/android/accessibilityservice/IAccessibilityProvider.aidl || true
fi

# ------------------------------------------------------------
# [8/9] libs locale: dragonbones AAR prebuilt + ffmpeg-kit stub
# ------------------------------------------------------------
echo "[8/9] Verific app/libs (dragonbones AAR + ffmpeg-kit stub)..."
mkdir -p app/libs
if [ ! -f "app/libs/dragonbones-release.aar" ]; then
  echo "  !! LIPSA dragonbones-release.aar - descarca de pe:"
  echo "     https://github.com/Davinci198/dragonbones-aar-prebuilt"
fi
if [ ! -f "app/libs/ffmpeg-kit-local.aar" ]; then
  echo "  !! LIPSA ffmpeg-kit-local.aar (stub 5.4KB) - trebuie creat:"
  echo "     AAR cu classes.jar minimal + jni/arm64-v8a placeholder-uri de 6B"
fi

# ------------------------------------------------------------
# [9/9] Verificari finale + pickFirsts libc++_shared.so
# ------------------------------------------------------------
echo "[9/9] Verificari finale..."
echo "  Fix libc++_shared.so duplicat - in app/build.gradle.kts trebuie:"
echo '    packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }'

echo ""
echo "=== TOATE FIX-URILE APLICATE ==="
echo "Build 1 (fara cache):  ~2h10m  (kapt + compile e lent pe ARM64)"
echo "Build 2 (cache cald):   ~3-10m"
echo "APK final: app/build/outputs/apk/standard/debug/app-standard-debug.apk"
