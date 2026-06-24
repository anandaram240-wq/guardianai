# 🛡️ GuardianAI — Advanced Child Safety Platform

**Cost: ₹0/month — 100% Free — 100% Open Source — Forever**

The most advanced AI-powered parental control system ever built, using entirely free and open-source tools.

---

## 🏆 What Makes GuardianAI Different

| Feature | Other Apps (AirDroid, etc.) | GuardianAI |
|---|---|---|
| Cost | ₹500–2000/month | **₹0/month** |
| DNS filter | Paid | **AdGuard Home (Free)** |
| AI content detection | Cloud (charged per image) | **On-device TFLite (Free, offline)** |
| VPN notification to child | YES (child sees it) | **ZERO (Ghost Shield)** |
| App blocking popup to child | YES | **ZERO (silent close)** |
| SOS system | Basic | **5x power button + shake** |
| Location maps | Google Maps (paid API) | **OpenStreetMap (Free)** |
| Push notifications | Firebase (paid) | **ntfy.sh self-hosted (Free)** |
| WebRTC camera relay | Paid TURN servers | **Coturn self-hosted (Free)** |
| Server | Paid cloud | **Oracle Cloud Always Free** |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ORACLE CLOUD (FREE VM)                    │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ AdGuard Home │  │   Coturn     │  │     ntfy.sh      │  │
│  │ (DNS Filter) │  │ (WebRTC TURN)│  │  (Push Notify)   │  │
│  │ 3M+ domains  │  │ Free relay   │  │  Self-hosted     │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │            GuardianAI API Server (Node.js)           │   │
│  │         (WebRTC Signaling + Command Router)          │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              ▲▼
                    ┌─────────────────┐
                    │  Supabase FREE  │
                    │  (Database +    │
                    │   Realtime +    │
                    │   Storage)      │
                    └─────────────────┘
                         ▲        ▲
              ┌──────────┘        └──────────┐
              ▼                              ▼
┌─────────────────────┐        ┌─────────────────────┐
│  CHILD'S ANDROID    │        │   PARENT'S PHONE    │
│                     │        │                     │
│  GuardianAI Agent   │        │  GuardianAI Parent  │
│  (Invisible app)    │        │  (React Native App) │
│                     │        │                     │
│  🔒 Device Owner    │        │  📍 Live Map        │
│  🌐 Private DNS     │        │  ⚠️ AI Alerts       │
│  🤖 TFLite AI       │        │  📷 Camera View     │
│  📍 GPS Tracking    │        │  🔊 Audio Listen    │
│  🚫 App Blocker     │        │  🔒 Remote Lock     │
│  🆘 SOS System      │        │  📊 Reports         │
└─────────────────────┘        └─────────────────────┘
```

---

## 🚀 Complete Setup Guide (Step by Step)

### Step 1: Oracle Cloud Always Free Server

1. Create a free account at [oracle.com/cloud/free](https://oracle.com/cloud/free)
2. Create an **ARM VM** (Ampere A1 — Always Free tier: 4 OCPU, 24GB RAM)
3. SSH into your VM and run:

```bash
curl -fsSL https://raw.githubusercontent.com/YOUR_REPO/guardianai/main/infrastructure/oracle-setup.sh | sudo bash
```

Or upload and run:
```bash
sudo bash infrastructure/oracle-setup.sh
```

4. **Edit your config:**
```bash
sudo nano /opt/guardianai/.env
```

5. **Start all services:**
```bash
cd /opt/guardianai && docker-compose up -d
```

6. **Access AdGuard Home:** `http://YOUR_ORACLE_IP:3000`
   - Complete the setup wizard
   - Your blocklists are already configured!

---

### Step 2: Supabase Database (Free)

1. Create a free project at [supabase.com](https://supabase.com)
2. Go to **SQL Editor** → **New Query**
3. Run each migration file in order:
   ```
   supabase/migrations/001_initial_schema.sql
   supabase/migrations/002_row_level_security.sql  
   supabase/migrations/003_functions_and_realtime.sql
   ```
4. Enable Realtime on these tables:
   - Supabase Dashboard → Database → Replication → Enable for:
     - `location_history`
     - `alerts`
     - `device_commands`
     - `children`

5. Copy your project URL and anon key from **Settings → API**

---

### Step 3: Update Configuration

Edit `api-server/src/config.js` with your:
- Supabase URL + keys
- Oracle VM IP address
- ntfy.sh server URL

---

### Step 4: Build Child Agent Android App

**Prerequisites:** Android Studio, Android SDK 26+

```bash
cd guardian-agent/android

# Build debug APK
./gradlew assembleDebug

# Install on child's device
adb install app/build/outputs/apk/debug/app-debug.apk
```

**One-time ADB Device Owner setup** (most important step!):

```bash
# Factory reset child's device first, OR:
# Run at the "Welcome" screen BEFORE any account is set up:

adb shell dpm set-device-owner com.guardianai.agent/.receivers.GuardianAdminReceiver
```

> ⚠️ **This is what makes GuardianAI invisible!** After this command, the app has MDM-level control — it can set Private DNS and Always-On VPN WITHOUT any popups or notifications to the child.

---

### Step 5: Build Parent App

```bash
cd parent-app
npm install
npx react-native run-android
```

---

### Step 6: Initial App Setup

1. Open GuardianAI on the child's phone
2. Enter your DNS server IP (Oracle VM IP)
3. The app will:
   - Set Private DNS to your AdGuard Home server ✅
   - Apply all bypass restrictions ✅
   - Hide its own icon from the launcher ✅
   - Start monitoring silently ✅

---

## 🔒 Ghost Shield — How Silent Blocking Works

```
Child types: pornhub.com
     ↓
DNS query → Your AdGuard Home server (Oracle VM)
     ↓
AdGuard: "This domain is in blocklist" → returns 0.0.0.0
     ↓
Browser tries to connect to 0.0.0.0 → silently fails
     ↓
Child sees: blank tab / "This site can't be reached"
     (NO popup. NO Guardian message. Looks like server is down.)
```

**Why child doesn't suspect:**
- Looks exactly like the website is down
- No "blocked by parental control" message
- App icon hidden — child can't find or uninstall it
- Can't disable via ADB (DISALLOW_DEBUGGING_FEATURES)
- Can't factory reset (DISALLOW_FACTORY_RESET)
- Can't install VPN to bypass (DISALLOW_CONFIG_VPN)

---

## 🆘 SOS Emergency System

Child can trigger SOS silently via:

| Method | How |
|---|---|
| Power button | Press 5 times rapidly within 2 seconds |
| Shake | Shake phone hard 3 times within 3 seconds |
| App button | Secret button inside the disguised app |

**What happens in 3 seconds:**
1. 📳 Phone vibrates SOS morse code (... --- ...)
2. 📷 Takes 3 front camera photos silently
3. 🎤 Records 30 seconds of ambient audio
4. 📍 Gets precise GPS location
5. 🚨 Creates CRITICAL alert in parent app instantly
6. 📱 Auto-calls parent's phone number
7. 💬 Sends SMS to ALL emergency contacts with live Google Maps link

---

## 📊 Features Summary

### Child Agent App (Invisible)
- ✅ Device Owner mode — full MDM control, zero popups
- ✅ Private DNS → AdGuard Home (silent content blocking)
- ✅ Real-time GPS (adaptive: 5s moving, 60s stationary)
- ✅ App usage monitoring + silent blocking
- ✅ Screen time limits (enforced by closing apps silently)
- ✅ On-device AI content detection (TFLite, zero cost)
- ✅ SOS panic system (power button + shake)
- ✅ Call & SMS monitoring
- ✅ New app installation alerts
- ✅ Auto-restart if killed (JobScheduler + START_STICKY)
- ✅ Remote command execution (lock, photo, audio, camera, wipe)

### Parent App
- ✅ Live GPS map (OpenStreetMap dark tiles — free)
- ✅ Geofence zones (draw on map, enter/exit alerts)
- ✅ Real-time AI alerts with screenshots
- ✅ App control panel (block, time limits, schedules)
- ✅ Content filter management (Ghost Shield control)
- ✅ Remote commands (lock device, take photo, listen, wipe)
- ✅ Weekly behavior reports with risk score
- ✅ Emergency contacts management
- ✅ Live camera stream via WebRTC

### Server (Oracle Free VM)
- ✅ AdGuard Home — 3,000,000+ domains blocked
- ✅ 12 free community blocklists (auto-updated daily)
- ✅ DNS-over-TLS (encrypted, bypass-proof)
- ✅ Safe Search enforcement (Google, YouTube, Bing)
- ✅ Coturn — free WebRTC relay (camera/audio)
- ✅ ntfy.sh — instant push notifications
- ✅ Auto SSL with Let's Encrypt

---

## 💰 Total Cost Breakdown

| Service | Free Tier | Usage |
|---|---|---|
| Oracle Cloud ARM VM | Always Free (4 OCPU, 24GB RAM) | Server hosting |
| Supabase | Free (500MB DB, 1GB storage, 50k MAU) | Database + Realtime |
| AdGuard Home | Free (GPL-3.0) | DNS filtering |
| Coturn | Free (MIT) | WebRTC relay |
| ntfy.sh | Free (Apache-2.0) | Push notifications |
| OpenStreetMap | Free (ODbL) | Maps |
| TensorFlow Lite | Free (Apache-2.0) | AI on-device |
| **TOTAL** | **₹0/month** | **Forever** |

---

## 📁 Project Structure

```
DISABLE/
├── guardian-agent/android/    # Child's invisible Android app (Kotlin)
│   └── app/src/main/
│       ├── kotlin/com/guardianai/agent/
│       │   ├── receivers/     # Device admin, boot receiver
│       │   ├── services/      # All background services
│       │   ├── ai/            # TFLite content detection
│       │   └── utils/         # Supabase client, helpers
│       ├── res/xml/           # Device admin policies
│       └── AndroidManifest.xml
├── parent-app/src/            # Parent React Native app
│   └── screens/               # All parent UI screens
├── api-server/src/            # Node.js + Socket.io API
├── infrastructure/            # Oracle VM setup scripts
├── dns-server/                # AdGuard Home config
├── supabase/migrations/       # Database schema (3 files)
└── scripts/                   # Utility scripts
```

---

## 🔧 Open Source Licenses

| Component | License |
|---|---|
| AdGuard Home | GPL-3.0 |
| Coturn | BSD/MIT |
| ntfy.sh | Apache-2.0 |
| TensorFlow Lite | Apache-2.0 |
| OpenStreetMap | ODbL |
| Supabase | Apache-2.0 |
| React Native | MIT |
| Socket.io | MIT |

**GuardianAI itself: MIT License**

---

## ⚠️ Legal & Ethical Note

GuardianAI is designed for **parents monitoring their own minor children's devices**. Always:
- Inform children they are being monitored (recommended for trust)
- Comply with your local laws regarding device monitoring
- Use only on devices you own or control legally

---

*Built with ❤️ for child safety. Zero cost. Zero compromise.*
