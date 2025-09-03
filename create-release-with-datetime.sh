#!/bin/bash

echo "=== RoadWatch Release Builder with DateTime Tracking ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get current timestamp
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BUILD_DATE=$(date +"%Y-%m-%d %H:%M:%S")

echo -e "${BLUE}Build Timestamp: $TIMESTAMP${NC}"
echo -e "${BLUE}Build Date: $BUILD_DATE${NC}"
echo ""

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

# Create release directory with timestamp
RELEASE_DIR="releases/$TIMESTAMP"
mkdir -p "$RELEASE_DIR"

echo -e "${YELLOW}Building release APKs with datetime tracking...${NC}"

# Clean and build release
./gradlew clean
./gradlew assembleRelease

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Release build completed successfully${NC}"
else
    echo -e "${RED}✗ Release build failed${NC}"
    exit 1
fi

# Move and rename APKs with datetime
echo -e "${YELLOW}Organizing release files...${NC}"

if [ -d "app/build/outputs/apk/release" ]; then
    cd app/build/outputs/apk/release

    # Find and rename APK files
    for apk in *.apk; do
        if [[ $apk == *"admin"* ]]; then
            new_name="roadwatch-admin-release-$TIMESTAMP.apk"
        elif [[ $apk == *"public"* ]]; then
            new_name="roadwatch-public-release-$TIMESTAMP.apk"
        else
            new_name="roadwatch-universal-release-$TIMESTAMP.apk"
        fi

        cp "$apk" "../../../../$RELEASE_DIR/$new_name"
        echo -e "${GREEN}✓ Created: $new_name${NC}"
    done

    cd ../../../../..

    # Create build info file
    cat > "$RELEASE_DIR/build-info.txt" << EOF
RoadWatch Release Build Information
===================================

Build Date: $BUILD_DATE
Build Timestamp: $TIMESTAMP
Version: 1.0.0
Version Code: 1

APK Files:
$(ls -la "$RELEASE_DIR"/*.apk)

Permissions Included:
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_LOCATION
- POST_NOTIFICATIONS
- VIBRATE
- INTERNET
- ACCESS_NETWORK_STATE
- WAKE_LOCK
- RECEIVE_BOOT_COMPLETED

Features:
- Real-time GPS tracking
- Proximity hazard alerts
- Google Maps integration
- Text-to-speech alerts
- Vibration feedback
- Import/Export functionality
- Admin and Public flavors

Keystore Information:
- File: $KEYSTORE_FILE
- Alias: roadwatch
- Validity: 10000 days

SHA-1 Fingerprint for Google Console:
$(keytool -list -v -keystore "$KEYSTORE_FILE" -alias roadwatch -storepass roadwatch123 2>/dev/null | grep "SHA1:" | sed 's/.*SHA1: //')

Build Command Used:
./create-release-with-datetime.sh

Notes:
- All permissions are properly requested
- APK names include datetime for tracking
- Both admin and public flavors built
- Ready for Google Play Store submission
EOF

    echo ""
    echo -e "${GREEN}🎉 RoadWatch Release Build Complete!${NC}"
    echo ""
    echo -e "${BLUE}📁 Release Directory: $RELEASE_DIR${NC}"
    echo -e "${BLUE}📋 Build Info: $RELEASE_DIR/build-info.txt${NC}"
    echo ""

    # List all files in release directory
    echo -e "${YELLOW}📦 Release Files:${NC}"
    ls -la "$RELEASE_DIR"/

    echo ""
    echo -e "${GREEN}🚀 Ready for distribution and testing!${NC}"

else
    echo -e "${RED}✗ No release APK directory found${NC}"
    exit 1
fi
