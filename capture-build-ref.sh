#!/usr/bin/env bash
set -euo pipefail

# Directory where we’ll copy reference files
REF_DIR="my-working-android-build-ref"
mkdir -p "$REF_DIR"

echo "📦 Capturing current Android build reference into $REF_DIR/"

# List of relevant build files (adjust if your project has more)
FILES=(
  "settings.gradle"
  "settings.gradle.kts"
  "build.gradle"
  "build.gradle.kts"
  "gradle.properties"
  "app/build.gradle"
  "app/build.gradle.kts"
  "gradle/libs.versions.toml"
  "gradle/wrapper/gradle-wrapper.properties"
  "local.properties"
)

for f in "${FILES[@]}"; do
  if [ -f "$f" ]; then
    dest="$REF_DIR/$(basename "$f")"
    cp "$f" "$dest"
    echo "✅ Copied $f → $dest"
  fi
done

# Copy whole gradle init scripts if present
if [ -d "$HOME/.gradle/init.d" ]; then
  mkdir -p "$REF_DIR/gradle-init.d"
  cp -r "$HOME/.gradle/init.d/"* "$REF_DIR/gradle-init.d/" 2>/dev/null || true
  echo "✅ Copied ~/.gradle/init.d → $REF_DIR/gradle-init.d/"
fi

# Copy wrapper directory for reference (not binaries, just config)
if [ -d "gradle/wrapper" ]; then
  mkdir -p "$REF_DIR/gradle-wrapper"
  cp -r gradle/wrapper/* "$REF_DIR/gradle-wrapper/"
  echo "✅ Copied gradle/wrapper/ → $REF_DIR/gradle-wrapper/"
fi

# Save environment info
{
  echo "=== JAVA ==="
  java -version 2>&1 || echo "Java not found"
  echo
  echo "=== Gradle (system) ==="
  gradle -v 2>/dev/null || echo "Gradle not installed globally"
  echo
  echo "=== Gradle Wrapper ==="
  ./gradlew -v || echo "Wrapper not runnable"
  echo
  echo "=== Environment Vars ==="
  echo "JAVA_HOME=$JAVA_HOME"
  echo "ANDROID_HOME=$ANDROID_HOME"
  echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
} > "$REF_DIR/environment-info.txt"

echo "🎉 Snapshot complete. See $REF_DIR/ for reference files + environment info."
