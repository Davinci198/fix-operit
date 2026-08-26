import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    id("io.objectbox")
    id("kotlin-kapt")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

data class SttModelAsset(
    val targetPath: String,
    val sourceUrl: String,
    val expectedBytes: Long,
    val expectedSha256: String,
)

val requiredExternallyBuiltNativeLibraries =
    listOf(
        file("src/main/jniLibs/arm64-v8a/liboperit_ripgrep.so"),
    )

val ffmpegKitLocalAar = file("libs/ffmpeg-kit-local.aar")
// FFmpegKit este OPTIONAL: pe CI (GitHub Actions) lipseste AAR-ul local,
// iar build-ul trebuie sa reuseasca fara el. Pe dispozitivul de build local
// (ARM64) AAR-ul poate fi plasat in app/libs/ pentru suport FFmpeg complet.
val hasFfmpegKit = ffmpegKitLocalAar.isFile && ffmpegKitLocalAar.length() > 0L
val requiredFfmpegKitArm64Libraries =
    setOf(
        "jni/arm64-v8a/libavcodec.so",
        "jni/arm64-v8a/libavdevice.so",
        "jni/arm64-v8a/libavfilter.so",
        "jni/arm64-v8a/libavformat.so",
        "jni/arm64-v8a/libavutil.so",
        "jni/arm64-v8a/libc++_shared.so",
        "jni/arm64-v8a/libffmpegkit.so",
        "jni/arm64-v8a/libffmpegkit_abidetect.so",
        "jni/arm64-v8a/libswresample.so",
        "jni/arm64-v8a/libswscale.so",
    )

val verifyExternallyBuiltNativeLibraries by tasks.registering {
    description = "Checks native libraries built outside Gradle before Android packaging."
    group = "verification"
    inputs.property(
        "requiredLibraries",
        requiredExternallyBuiltNativeLibraries.map { library -> library.path },
    )
    inputs.property("ffmpegKitAar", ffmpegKitLocalAar.path)
    inputs.property("ffmpegKitArm64Libraries", requiredFfmpegKitArm64Libraries)
    outputs.upToDateWhen { false }

    doLast {
        val invalidLibraries =
            requiredExternallyBuiltNativeLibraries.filter { library ->
                !library.isFile || library.length() == 0L
            }
        require(invalidLibraries.isEmpty()) {
            "Missing or empty externally built native library: " +
                invalidLibraries.joinToString { library -> library.path } +
                ". Run tools/native_ripgrep/build_native_ripgrep.ps1 before packaging."
        }

        if (hasFfmpegKit) {
            ZipFile(ffmpegKitLocalAar).use { archive ->
                val invalidEntries =
                    requiredFfmpegKitArm64Libraries.filter { entryName ->
                        val entry = archive.getEntry(entryName)
                        entry == null || entry.size <= 0L
                    }
                require(invalidEntries.isEmpty()) {
                    "FFmpegKit AAR is missing or contains empty arm64 native libraries: " +
                        invalidEntries.joinToString()
                }
            }
        } else {
            println("WARN: ffmpeg-kit-local.aar lipseste - se construieste FARA suport FFmpeg (CI build).")
        }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

fun parseSttModelAssetManifest(manifestFile: File): List<SttModelAsset> {
    return manifestFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val parts = line.split("|")
            require(parts.size == 6) {
                "Invalid STT model asset manifest line ${index + 1}: expected 6 fields"
            }
            val targetPath = parts[0]
            require(!targetPath.startsWith("/") && !targetPath.contains("..") && !targetPath.contains('\\')) {
                "Invalid STT model asset target path: $targetPath"
            }
            SttModelAsset(
                targetPath = targetPath,
                sourceUrl = parts[1],
                expectedBytes = parts[2].toLong(),
                expectedSha256 = parts[3].lowercase(),
            )
        }
}

fun verifySttModelAsset(file: File, asset: SttModelAsset): Boolean {
    return file.isFile &&
        file.length() == asset.expectedBytes &&
        sha256(file) == asset.expectedSha256
}

fun downloadSttModelAsset(asset: SttModelAsset, destination: File) {
    destination.parentFile.mkdirs()
    require(destination.parentFile.isDirectory) {
        "Unable to create STT model asset directory: ${destination.parent}"
    }

    val tempFile = File(destination.parentFile, "${destination.name}.download")
    if (tempFile.exists()) {
        tempFile.delete()
    }

    val connection = URI(asset.sourceUrl).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "Operit Android build STT asset sync")
    try {
        val responseCode = connection.responseCode
        require(responseCode in 200..299) {
            "Unable to download ${asset.targetPath}: HTTP $responseCode from ${asset.sourceUrl}"
        }
        connection.inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } finally {
        connection.disconnect()
    }

    require(verifySttModelAsset(tempFile, asset)) {
        "Downloaded STT model asset failed verification: ${asset.targetPath}"
    }
    Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

val sttModelAssetsManifestFile = layout.projectDirectory.file("config/stt-model-assets.properties")
val generatedSttModelAssetsDir = layout.buildDirectory.dir("generated/stt-model-assets")
val generatedMainAssetsDir = layout.buildDirectory.dir("generated/main-assets")

val syncSttModelAssets by tasks.registering {
    description = "Downloads and verifies generated assets for local STT recognition."
    group = "build setup"

    inputs.file(sttModelAssetsManifestFile)
    outputs.dir(generatedSttModelAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
        val manifestFile = sttModelAssetsManifestFile.asFile
        val assets = parseSttModelAssetManifest(manifestFile)
        val outputRoot = generatedSttModelAssetsDir.get().asFile
        outputRoot.mkdirs()

        val outputRootPath = outputRoot.toPath().toAbsolutePath().normalize()
        val expectedFiles = mutableSetOf<File>()

        assets.forEach { asset ->
            val destinationPath = outputRootPath.resolve(asset.targetPath).normalize()
            require(destinationPath.startsWith(outputRootPath)) {
                "STT model asset target escapes generated assets directory: ${asset.targetPath}"
            }
            val destination = destinationPath.toFile()
            expectedFiles.add(destination.canonicalFile)

            if (!verifySttModelAsset(destination, asset)) {
                if (destination.exists() && !destination.delete()) {
                    error("Unable to replace invalid STT model asset: ${destination.path}")
                }
                downloadSttModelAsset(asset, destination)
            }

            require(verifySttModelAsset(destination, asset)) {
                "STT model asset verification failed after sync: ${asset.targetPath}"
            }
        }

        outputRoot.walkBottomUp()
            .filter { it.isFile && it.canonicalFile !in expectedFiles }
            .forEach { file ->
                require(file.delete()) {
                    "Unable to remove stale STT model asset: ${file.path}"
                }
            }
        outputRoot.walkBottomUp()
            .filter { it.isDirectory && it != outputRoot && it.list()?.isEmpty() == true }
            .forEach { directory ->
                require(directory.delete()) {
                    "Unable to remove empty STT model asset directory: ${directory.path}"
                }
            }
    }
}

val syncMainAssets by tasks.registering(Sync::class) {
    description = "Assembles application assets with verified generated STT model files."
    group = "build setup"
    dependsOn(syncSttModelAssets)

    from("src/main/assets") {
        exclude("models/**")
    }
    from(generatedSttModelAssetsDir)
    into(generatedMainAssetsDir)
}

android {
    namespace = "com.ai.assistance.operit"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.setSrcDirs(listOf(generatedMainAssetsDir.get().asFile))
        }
    }

    signingConfigs {
        val releaseKeystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (releaseKeystorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        applicationId = "com.ai.assistance.operit"
        minSdk = 26
        targetSdk = 34
        versionCode = 54
        versionName = "1.16.2+1-clone"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

    }
    flavorDimensions += "dist"
    productFlavors {
        create("standard") {
            dimension = "dist"
        }
        create("clone") {
            dimension = "dist"
            applicationIdSuffix = ".clone"
            versionNameSuffix = "-clone"
            resValue("string", "app_name", "Fix Operit Clone")
        }
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")

        release {
            // FIX R8: cloneRelease pica cu Missing class com.gemalto.jp2.JP2Decoder etc.
            // Pentru clone dezactivam minify, pentru standard il pastram dar cu dontwarn in proguard
            // Daca vrei sa pui minify true la loc, pune proguard rules din comentariu
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        debug {
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        create("nightly") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    applicationVariants.all {
        if (buildType.name == "nightly") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-nightly.apk"
            }
        }
        if (flavorName == "clone") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-clone-${buildType.name}.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libsudo.so")
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/module-info.class"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            pickFirsts += "**/*.so"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncMainAssets)
    dependsOn(verifyExternallyBuiltNativeLibraries)
}

tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(syncMainAssets)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.github.jelmerk:hnswlib-core:1.2.1")
    implementation(files("libs/dragonbones-release.aar"))
    implementation(project(":terminal"))
    implementation(project(":mnn"))
    implementation(project(":llama"))
    implementation(project(":mmd"))
    implementation(project(":fbx"))
    implementation(project(":showerclient"))
    implementation(project(":quickjs"))
    implementation("com.google.android.filament:filament-android:1.69.2")
    implementation("com.google.android.filament:gltfio-android:1.69.2")
    implementation("com.google.android.filament:filament-utils-android:1.69.2")
    implementation(libs.androidx.ui.graphics.android)
    if (hasFfmpegKit) {
        implementation(files("libs/ffmpeg-kit-local.aar"))
    }
    implementation("com.arthenica:smart-exception-common:0.2.1")
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.androidx.activity.ktx)
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.zxing.core)
    implementation(libs.java.diff.utils)
    implementation(libs.android.apksig)
    implementation(libs.apk.parser)
    implementation(libs.sable.axml)
    implementation(libs.zipalign.java)
    implementation(libs.commons.compress)
    implementation(libs.commons.io)
    implementation(libs.glide)
    implementation(libs.androidx.core.ktx)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")
    implementation(libs.androidsvg)
    implementation(libs.android.gif)
    implementation(libs.image.cropper)
    implementation(libs.exoplayer)
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)
    implementation(libs.material3.window)
    implementation(libs.window)
    implementation(libs.androidx.webkit)
    implementation(libs.itextg)
    implementation(libs.pdfbox)
    implementation(libs.zip4j)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.jlatexmath)
    implementation(libs.renderx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.reflect)
    implementation(libs.uuid)
    implementation(libs.gson)
    implementation(libs.hjson)
    implementation(libs.jieba)
    implementation(libs.hnswlib.core)
    implementation(libs.hnswlib.utils)
    implementation(libs.tensorflow.lite)
    implementation(libs.mediapipe.tasks.text)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)
    implementation(libs.commons.compress.v2)
    implementation(libs.junrar)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.preferences.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    implementation(libs.colorpicker)
    implementation(libs.backdrop)
    implementation(libs.liquid)
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.android)
    implementation(libs.reorderable)
    implementation(libs.swipe)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.mcp.sdk.client)
    implementation(libs.ktor.client.okhttp)
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation(libs.okhttp.logging.interceptor)
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.32.0")
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
