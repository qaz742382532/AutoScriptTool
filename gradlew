#!/bin/sh
# Gradle wrapper stub
export ANDROID_HOME="$HOME/android/sdk"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"

# Download Gradle if needed
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_VERSION="8.9"
GRADLE_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_ZIP="$GRADLE_DIR/gradle-${GRADLE_VERSION}-bin.zip"

# Try to find existing Gradle installation
for d in "$GRADLE_DIR"/*/; do
    if [ -f "${d}bin/gradle" ]; then
        exec "${d}bin/gradle" "$@"
    fi
done

# Need to download - use gradle from PATH or fail
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

echo "Gradle $GRADLE_VERSION not found. Downloading..."
mkdir -p "$GRADLE_DIR"
curl -L -o "$GRADLE_ZIP" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" 2>&1
mkdir -p "$GRADLE_DIR/$(unzip -qql "$GRADLE_ZIP" | head -1 | awk '{print $4}')" 
echo "Please re-run gradlew"
