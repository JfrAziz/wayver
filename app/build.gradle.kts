plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

val rustOutJniDir = layout.buildDirectory.dir("generated/rustJniLibs")

android {
    namespace = "dev.wayver.app"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.wayver.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rustOutJniDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}

val buildRustNative =
    tasks.register<Exec>("buildRustNative") {
        group = "build"
        description = "Build libwayver.so from Rust for arm64-v8a with 16 KB ELF alignment"

        val rustDir = file("src/main/rust")
        val ndkDir = System.getenv("ANDROID_NDK_HOME") ?: android.ndkDirectory.absolutePath
        val clang = "$ndkDir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
        val builtSo = file("src/main/rust/target/aarch64-linux-android/release/libwayver.so")
        val outDir = rustOutJniDir.get().dir("arm64-v8a").asFile

        inputs.dir(rustDir.resolve("src"))
        inputs.file(rustDir.resolve("Cargo.toml"))
        outputs.file(outDir.resolve("libwayver.so"))

        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang)
        environment("CC_aarch64_linux_android", clang)
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-landroid")

        commandLine(
            "cargo",
            "build",
            "--manifest-path",
            rustDir.resolve("Cargo.toml").absolutePath,
            "--target",
            "aarch64-linux-android",
            "--release"
        )

        doLast {
            outDir.mkdirs()
            builtSo.copyTo(outDir.resolve("libwayver.so"), overwrite = true)
        }
    }

tasks.named("preBuild") {
    dependsOn(buildRustNative)
}
