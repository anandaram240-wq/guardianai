#!/bin/bash
# ========================================================
# GuardianAI — Quick Start Guide
# Run this to initialize the project on your machine
# ========================================================

echo "🛡️  Initializing GuardianAI project..."

# Install API server dependencies
echo "📦 Installing API server packages..."
cd api-server && npm install && cd ..

# Install parent app dependencies
echo "📦 Installing parent app packages..."
cd parent-app && npm install && cd ..

echo ""
echo "✅ Project initialized!"
echo ""
echo "Next steps:"
echo "  1. Set up Oracle Cloud VM:  bash infrastructure/oracle-setup.sh"
echo "  2. Create Supabase project: https://supabase.com (free)"
echo "  3. Run DB migrations:       supabase db push"
echo "  4. Start API server:        cd api-server && npm start"
echo "  5. Build parent app:        cd parent-app && npx react-native run-android"
echo "  6. Build child agent:       cd guardian-agent && ./gradlew assembleDebug"
