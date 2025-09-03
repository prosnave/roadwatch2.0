#!/bin/bash

echo "=== RoadWatch Secure Release Builder ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if keystore exists
KEYSTORE_FILE="roadwatch-release-key.keystore"
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}Creating release keystore...${NC}"
    
    # Generate keystore
    keytool -genkey -v -keystore "$KEYSTORE_FILE" \
        -alias roadwatch \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass roadwatch123 \
        -keypass roadwatch123 \
        -dname "CN=RoadWatch,OU=Mobile,O=RoadWatch,L=Nairobi,ST=Nairobi,C=KE"
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Keystore created successfully${NC}"
    else
        echo -e "${RED}✗ Failed to create keystore${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}✓ Using existing keystore${NC}"
fi

# Add signing configuration to build.gradle temporarily
echo -e "${YELLOW}Configuring signing...${NC}"

# Create temporary build.gradle with signing config
cp app/build.gradle app/build.gradle.backup

# Add signing configuration
cat >> app/build.gradle << 'EOF'

android {
    signingConfigs {
        release {
            storeFile file('../roadwatch-release-key.keystore')
            storePassword 'roadwatch123'
            keyAlias 'roadwatch'
            keyPassword 'roadwatch123'
        }
    }
}

android.buildTypes.release.signingConfig android.signingConfigs.release
EOF

echo -e "${YELLOW}Building secure release APK...${NC}"

# Clean and build release
./gradlew clean
./gradlew assembleRelease

# Restore original build.gradle
mv app/build.gradle.backup app/build.gradle

if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    echo -e "${GREEN}✓ Secure release APK created!${NC}"
    echo ""
    echo "📦 APK Location: app/build/outputs/apk/release/app-release.apk"
    echo "🔑 Keystore: $KEYSTORE_FILE (keep this safe!)"
    echo ""
    
    # Get APK info
    APK_SIZE=$(du -h app/build/outputs/apk/release/app-release.apk | cut -f1)
    echo "📊 APK Size: $APK_SIZE"
    
    # Get SHA-1 fingerprint for Google Console
    echo ""
    echo -e "${YELLOW}📋 SHA-1 Fingerprint for Google Console:${NC}"
    keytool -list -v -keystore "$KEYSTORE_FILE" -alias roadwatch -storepass roadwatch123 | grep SHA1
    
    echo ""
    echo -e "${GREEN}🎉 Your secure RoadWatch APK is ready!${NC}"
    echo -e "${GREEN}This APK should NOT trigger Google's malware detection.${NC}"
    
else
    echo -e "${RED}✗ Failed to create release APK${NC}"
    exit 1
fi
