plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

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

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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

val cppFiles =
    fileTree("src/main/cpp") {
        include("*.c", "*.cpp", "*.h", "*.hpp")
    }

tasks.register<Exec>("clangFormat") {
    group = "formatting"
    description = "Format C/C++ sources with clang-format"
    commandLine(listOf("clang-format", "-i") + cppFiles.files.map { it.absolutePath })
}

tasks.register<Exec>("clangFormatCheck") {
    group = "verification"
    description = "Verify C/C++ formatting with clang-format"
    commandLine(listOf("clang-format", "--dry-run", "--Werror") + cppFiles.files.map { it.absolutePath })
}

tasks.register<Exec>("clangTidy") {
    group = "verification"
    description = "Lint C/C++ sources with clang-tidy"
    dependsOn("externalNativeBuildDebug")
    val cSources =
        fileTree("src/main/cpp") {
            include("*.c", "*.cpp")
        }
    commandLine(
        listOf("clang-tidy", "-p", ".cxx/tools/debug/arm64-v8a") +
            cSources.files.map { it.absolutePath }
    )
}
