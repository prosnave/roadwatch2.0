#!/bin/bash

# RoadWatch Android Setup Script
# Follows the exact instructions provided by the user

set -e

echo "🚀 RoadWatch Android Setup"
echo "=========================="

# Check JDK 17
echo "📋 Checking JDK 17..."
if ! java -version 2>&1 | grep -q "17\."; then
    echo "❌ JDK 17 not found. Please install JDK 17 first."
    echo "   Visit: https://adoptium.net/temurin/releases/"
    exit 1
fi
echo "✅ JDK 17 found"

# Check Gradle
echo "📋 Checking Gradle..."
if ! command -v gradle &> /dev/null && [ ! -f "./gradlew" ]; then
    echo "❌ Gradle not found. Please install Gradle or ensure ./gradlew exists."
    exit 1
fi
echo "✅ Gradle available"

# Setup Android SDK
echo "📋 Setting up Android SDK..."

ANDROID_HOME="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="$HOME/Android/Sdk"

# Create SDK directory
mkdir -p "$ANDROID_HOME"

# Check if command line tools exist
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    echo "⚠️  Android Command Line Tools not found!"
    echo ""
    echo "📥 Please download from:"
    echo "   https://developer.android.com/studio#command-line-tools-only"
    echo ""
    echo "📦 Then extract to: $ANDROID_HOME/cmdline-tools/latest/"
    echo ""
    echo "🛠️  After extraction, run this script again."
    exit 1
fi

# Set environment variables
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "✅ Android SDK path: $ANDROID_HOME"

# Install required packages
echo "📦 Installing Android SDK packages..."
echo "   This may take several minutes..."

if command -v sdkmanager &> /dev/null; then
    sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    echo "✅ SDK packages installed"
else
    echo "❌ sdkmanager not found in PATH"
    echo "   Please check your Android SDK installation"
    exit 1
fi

# Verify local.properties
echo "📋 Checking local.properties..."
if [ ! -f "local.properties" ]; then
    echo "❌ local.properties not found!"
    exit 1
fi

if ! grep -q "MAPS_API_KEY" local.properties; then
    echo "❌ MAPS_API_KEY not found in local.properties!"
    echo "   Please add: MAPS_API_KEY=your_google_maps_api_key_here"
    exit 1
fi

echo "✅ local.properties configured"

echo ""
echo "🎉 Setup complete!"
echo ""
echo "🏗️  To build the app:"
echo "   ./gradlew clean assemblePublicDebug"
echo ""
echo "📱 APK will be at:"
echo "   app/build/outputs/apk/public/debug/app-public-debug.apk"
echo ""
echo "🌐 To serve over WiFi:"
echo "   cd app/build/outputs/apk/public/debug"
echo "   python3 -m http.server 8000"
echo ""
echo "📱 Then download from your phone:"
echo "   http://<your-laptop-ip>:8000/app-public-debug.apk"
