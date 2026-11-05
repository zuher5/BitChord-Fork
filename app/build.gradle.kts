import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Signing details, kept out of the repository in `keystore.properties`
 * (see keystore.properties.example). Absent on a fresh checkout, in which case
 * the release build still runs and simply comes out unsigned rather than
 * failing — only whoever holds the key can produce a shippable APK.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// See the root build.gradle.kts: the shared version marker for every build type.
val betaSuffix = rootProject.extra["bitchordBetaSuffix"] as String

android {
    namespace = "com.music.bitchord"
    // InnerTubeX's AAR requires compiling against 37; targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.music.bitchord"
        // 26 keeps reach wide; real-time blur (RenderEffect) kicks in on API 31+,
        // Haze falls back to a translucent scrim below that.
        minSdk = 26
        targetSdk = 36
        versionCode = rootProject.extra["bitchordVersionCode"] as Int
        versionName = rootProject.extra["bitchordVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Hanya Inggris + Indonesia. shrinkResources memang aktif, tapi tanpa
         * filter ini AppCompat/Material3 tetap membawa ~80 locale miliknya
         * ke dalam APK. Fork ini dipakai satu orang dengan dua bahasa itu;
         * locale lain hanya bobot mati. Tambahkan kembali di sini kalau
         * butuh bahasa lain.
         */
        resourceConfigurations += listOf("en", "id")
    }

    /*
     * arm64-v8a saja, tanpa universal APK. Semua perangkat Android yang masih
     * dapat update sekarang arm64, dan fork ini tidak lagi membawa kode native
     * apa pun (FFmpeg/onnx dihapus) — split per-ABI lain hanya menggandakan
     * APK dengan isi identik. Satu APK, satu target.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // applicationId can only be overridden per flavor, not per build type, so a
    // dev/prod dimension exists purely to let both sit installed side by side
    // on the same device instead of the dev build overwriting the prod one.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationId = "com.dev.bitchord"
            resValue("string", "app_name", "BitChord Dev")
        }
        create("prod") {
            dimension = "env"
            // Matches defaultConfig — this is the package already shipped/installed.
        }
    }

    signingConfigs {
        // Both halves have to be there, not just the properties file: it *names*
        // the keystore rather than containing it, and both are gitignored
        // separately, so a checkout can easily end up with the one and not the
        // other. A signing config pointing at a keystore that is not on disk
        // fails the release build outright at validateSigningRelease — which is
        // exactly the failure the unsigned fallback above exists to avoid, so
        // the keystore has to be looked for rather than assumed.
        val store = signing.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            create("release") {
                storeFile = store
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
        }
        release {
            // Carried here too — see [betaSuffix]. A sideloaded beta is a
            // release build, and it is the one that most needs the marker.
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
            /*
             * On for what it does to speed, not size. Compose is written to be
             * run through R8 — without it every composable keeps the debug-era
             * shape the compiler emits, and the whole UI runs measurably slower.
             *
             * Nothing is renamed (-dontobfuscate), and every library that reaches
             * for classes by name — Rhino running YouTube's player JavaScript,
             * NewPipe, InnerTubeX, the FFmpeg JNI bridge, protobuf-lite,
             * Ktor — is kept whole: see proguard-rules.pro. What R8
             * is left to optimise is Compose, Media3, coroutines and our own
             * code, which is where the time goes. Checked on a device through the
             * `benchmark` build type below before it ships.
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null without a keystore to sign with: the build then produces
            // app-release-unsigned.apk instead of failing outright.
            signingConfig = signingConfigs.findByName("release")
        }
        /*
         * The release build, installable next to the dev and prod apps: same R8,
         * same non-debuggable runtime, signed with the debug key under its own
         * package so it never replaces either. For measuring startup the way
         * users get it and for checking that shrinking broke nothing — a debug
         * build is interpreted and verified at runtime and says little about
         * either. `./gradlew installDevBenchmark`.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // jspecify ships this descriptor more than once across the graph.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // Several libraries on the runtime classpath (Ktor, kotlinx) ship the
            // same licence/notice files under the same paths.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Unit tests run against a stub android.jar whose methods throw
            // rather than return. That is the right default for anything whose
            // behaviour depends on the framework, and wrong for android.util.Log
            // — which [TrackLog] calls on every decision the source layer makes,
            // so a test of that layer fails on the logging rather than on the
            // logic it was written to check.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and
 * app/src/main/java carries a patched copy at the same package path (see that
 * file for why it exists). A debug build keeps project and library dex separate,
 * so the project copy simply wins at class-load time and the two coexist; a
 * release build merges every input into one dex set, where D8 rejects the
 * duplicate type outright ("Utils is defined multiple times"). So the library's
 * copy is stripped from its jar before it reaches dexing, leaving exactly one
 * definition of the class in the build.
 *
 * The artifact is resolved on its own and non-transitive purely to re-jar it;
 * the transitive dependencies it would otherwise have carried are declared by
 * hand in the dependencies block below, since dropping the module drops them too.
 */
val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        // The class itself, plus any nested or synthetic siblings the upstream
        // compiler emitted alongside it, so nothing from the jar's Utils survives.
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":sharedUi"))

    // ---- Compose (Material 3) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Pinned above the BOM's 1.7.6: [IosOverscroll] uses OverscrollFactory,
    // which that version doesn't have. Newer foundation alongside the BOM's
    // older ui/material3 is a combination Compose supports deliberately —
    // foundation depends on ui, not the reverse — and this exact pairing was
    // already in effect (foundation was reaching 1.10.0 transitively through
    // the liquid-glass library before that dependency was removed).
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media playback: Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // Some backends hand back HLS manifests rather than bare audio files; the
    // source layer marks them and this is what plays them. See StreamContainer.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // Source modules hand back manifests rather than files (see StreamContainer's
    // transport markers), and which kind it is, is the backend's choice. Without
    // this artifact a DASH manifest is not merely unplayed —
    // DefaultMediaSourceFactory cannot build a source for it, falls back to
    // progressive, and the extractors try to sniff XML as audio
    // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    // Coroutines/Guava interop: Media3's ListenableFutures are awaited from
    // suspend code around the player.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // ---- Images: Coil 3 + Palette (dominant colors for the mesh gradient) ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Title logos are PNG, but artist signatures are usually SVG — without
    // this Coil can't decode them (see BitChordApplication's image loader).
    implementation("io.coil-kt.coil3:coil-svg:3.0.4")
    // SF Symbols as Compose vectors, for the iOS-style settings and menus (as Convx uses).
    // Without its own dependencies: it asks for Compose 1.12, which needs a newer Android
    // Gradle plugin than this build, and the plain ImageVectors used here need nothing
    // the app's own Compose doesn't already have.
    implementation("com.github.cosmictaserdev-creator.Jetpack_SF_Symbols:sfsymbols-android:1.1.1") {
        isTransitive = false
    }
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ---- Frosted glass / progressive blur (Telegram-style bars) ----
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // ---- Innertube (YouTube Music) client: Ktor + kotlinx.serialization ----
    // Ktor and serialization are held at InnerTubeX's versions (below) so the
    // upgrade it forces is explicit rather than resolved behind our backs.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- YouTube stream extraction: live-benchmarked client catalog + cipher tiers ----
    implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    // Pinned to v0.26.3, not the newer v0.26.4: v0.26.4's player-JS parser fails with
    // "Could not parse deobfuscation function" on the current player build, which blocks
    // WEB_REMIX's ciphered formats entirely. v0.26.3 solves the same signatures cleanly
    // against the same player JS — confirmed side by side against PixelMusic-ref, which
    // pins v0.26.3 and doesn't hit the parse failure.
    //
    // Consumed as a stripped jar rather than as the module, so its own
    // Utils.class does not reach dexing. See newPipeExtractorStripped above; the
    // transitive dependencies the module would have brought are listed here
    // because dropping its artifact drops them too. If the version changes,
    // re-derive this list with
    //   ./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath
    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // A real HTTP server for the source-layer tests (Innertube parsing, stream
    // resolution). A hand-rolled fake of the client would be a test of the
    // fake. Pinned to the OkHttp version already on the runtime classpath.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

/*
 * A debug APK lands on the device uncompiled — `dumpsys package dexopt` reports
 * it as run-from-apk — so every launch verifies the whole app's classes at
 * runtime before a line of our code runs. Measured on the BlueStacks box, that
 * was about two seconds of every cold start and most of why the dev build felt
 * so much slower than a release one. `verify` is the cheapest filter that
 * removes it (~15s once per install), and unlike `speed` it leaves the debug
 * build debuggable exactly as before.
 *
 * Runs after `installDevDebug` from the command line. Android Studio's Run
 * button deploys on its own and never reaches this task, so from there run
 * `./gradlew verifyDevInstall` after installing.
 */
val verifyDevInstall = tasks.register("verifyDevInstall") {
    group = "install"
    description = "Pre-verifies the installed dev build on every connected device."
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val adbPath = adb.get().asFile.absolutePath
        val serials = ProcessBuilder(adbPath, "devices").start()
            .inputStream.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
        serials.forEach { serial ->
            logger.lifecycle("verifyDevInstall: compiling com.dev.bitchord on $serial")
            ProcessBuilder(
                adbPath, "-s", serial, "shell", "cmd", "package", "compile",
                "-m", "verify", "-f", "com.dev.bitchord",
            ).inheritIO().start().waitFor()
        }
    }
}
tasks.matching { it.name == "installDevDebug" }.configureEach { finalizedBy(verifyDevInstall) }
