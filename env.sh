#!/usr/bin/env bash
# Project-isolated Android/Gradle environment (never touches $HOME)
ROOT_DIR="/home/jfraziz/Projects/android/huawei-driver"

export ANDROID_HOME="$ROOT_DIR/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ROOT_DIR/toolchain/android-sdk"
export ANDROID_NDK_HOME="$ROOT_DIR/toolchain/android-ndk-r27c"
export ANDROID_USER_HOME="$ROOT_DIR/.android-home"
export GRADLE_USER_HOME="$ROOT_DIR/.gradle-home"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
