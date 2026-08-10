# NDK 27 ARM64 Fixed - Motorola G57 12GB Build Guide
# by Davinci198 - BUILD SUCCESSFUL in 2h10m (build 1) / 10m42s (assemble)

## Device
- Motorola G57 12GB RAM + 12GB Swap (12GB zRAM + 12GB swapfile)
- Ubuntu 24 (ARM64) inside Operit AI
- **No PC - build 100% pe telefon!**

## Probleme rezolvate (9x ✅)

1. **libpthread.so.0 blocked by SABA DNS** -> DragonBones AAR prebuilt
   - Repo: https://github.com/Davinci198/dragonbones-aar-prebuilt
2. **bad_array_new_length / operator new(align_val_t)** -> NDK 27 + fix libgcc/libunwind/libatomic in sysroot
3. **Build time 26m -> 2-3m** -> AAR prebuilt dragonbones (descarcat odata, compilat mereu)
4. **ld, ld.lld, lld x86-64 crash pe ARM64** -> symlink la /usr/bin/ld.lld (LLVM 18.1.3 ARM64 nativ)
5. **--record-libdeps flag LLVM vs GNU ar** -> llvm-ar / llvm-ranlib ARM64 nativ din LLVM 18
6. **libc++_shared.so duplicat (dragonbones + ffmpeg-kit-local)** -> packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }
7. **AAPT2 daemon startup failed (aapt2 x86-64 in build-tools 36)** -> wrapper C ARM64 + qemu-x86_64
8. **AIDL x86-64 + IAccessibilityProvider lipsa** -> wrapper C ARM64 + qemu-x86_64 pentru aidl36 + generare manuala cu aidl34 (ARM64 nativ)
9. **ffmpeg-kit-local.aar e stub (5.4KB)** -> cod Kotlin adaptat: `ReturnCode.isSuccess(returnCode)`, `?:"unknown"`, `.allProperties` -> null

## Pasi rapizi pentru urmatorul build (3-10 min cu cache)

```bash
# 1. Swap 12GB real (nu doar zRAM)
sudo fallocate -l 12G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
free -h

# 2. Aplica fix-ul complet (scriptul salveaza TOT)
chmod +x fix-ndk-arm64-final.sh
./fix-ndk-arm64-final.sh

# 3. Verifica gradle.properties (limita paralelism, altfel OOM):
#    org.gradle.jvmargs=-Xmx4096m
#    org.gradle.workers.max=4
#    android.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2

# 4. Build!
./gradlew :app:assembleStandardDebug

# APK: app/build/outputs/apk/standard/debug/app-standard-debug.apk (~482MB)
```

## detaliu: De ce AAPT2 nu mergea pe ARM64

- `build-tools 36.0.0` contine `aapt2` si `aidl` compilati **doar x86-64** (Google nu publica binare ARM64 pentru Linux)
- Pe Motorola G57 (ARM64) procesul murea imediat: `AAPT2 Daemon startup failed`
- Solutia: **wrapper C (7KB) compilat pentru ARM64** care executa binarul x86-64 prin `qemu-x86_64` (qemu-user 8.2.2 din Ubuntu 24)
- Wrapper-ul: `/opt/aapt2-custom/aapt2` (+ backup `aapt2.x86_64`)
- Gradle il foloseste prin: `android.aapt2FromMavenOverride=/opt/aapt2-custom/aapt2`
- Pentru AIDL: AGP invoca binarul intern, deci am inlocuit direct `build-tools/36.0.0/aidl` cu wrapper (backup la `.orig`)
- EGAL: `IAccessibilityProvider` NU era generat de compilatorul AGP => generat manual cu `aidl --lang=java` din build-tools 34 (care e ARM64 nativ)

## Backup pretios (NU STERGE!)

- `ndk-27-arm64-fixed.tar.gz` - NDK reparat complet (bin/ cu LLVM ARM64 nativ + wrapper-e)
- `app/libs/dragonbones-release.aar` (3.4MB, prebuilt NDK27 fix)
- `app/libs/ffmpeg-kit-local.aar` (5.4KB stub)
- `fix-ndk-arm64-final.sh` - scriptul complet de reparatie
- `apk-backup/app-standard-debug.apk` - APK final 482MB
- `logs-backup/` - toate log-urile de build (build_local*, assemble*, kotlin2, aidl*)
- `/root/.gradle` + `app/build` cache -> al 2-lea build e 3-10 min

## Rezultat
- Build 1: 2h10m (kaptGenerateStubs + kapt + compileKotlin - cel mai lent pe ARM64)
- Assemble: 10m42s (cu totul compilat deja - doar dex + packaging)
- APK: ~482MB cu MNN + llama.cpp + MMD + DragonBones + Ubuntu 24 + 294 lib-uri .so

Legenda: compilat 100% pe telefon!

## Link-uri
- Fix Operit: https://github.com/Davinci198/fix-operit
- DragonBones AAR: https://github.com/Davinci198/dragonbones-aar-prebuilt
