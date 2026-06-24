import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, ScrollView, TouchableOpacity,
  Switch, Alert, StatusBar, Platform, Linking
} from 'react-native';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = 'https://YOUR_PROJECT.supabase.co';
const SUPABASE_ANON_KEY = 'YOUR_ANON_KEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

const COLORS = {
  bg: '#080C18', card: '#10162A', cardBorder: '#1E2D50',
  purple: '#7C3AED', green: '#10B981', red: '#EF4444',
  orange: '#F59E0B', blue: '#3B82F6',
  textPrimary: '#F1F5F9', textSec: '#94A3B8', textMuted: '#475569',
};

function SettingRow({ icon, label, value, onPress, isSwitch, switchValue, onSwitch, danger }) {
  return (
    <TouchableOpacity style={styles.settingRow} onPress={onPress} disabled={isSwitch} activeOpacity={0.7}>
      <Text style={styles.settingIcon}>{icon}</Text>
      <View style={styles.settingInfo}>
        <Text style={[styles.settingLabel, danger && { color: COLORS.red }]}>{label}</Text>
        {value && <Text style={styles.settingValue}>{value}</Text>}
      </View>
      {isSwitch ? (
        <Switch value={switchValue} onValueChange={onSwitch}
          trackColor={{ false: COLORS.cardBorder, true: COLORS.green + '80' }}
          thumbColor={switchValue ? COLORS.green : COLORS.textMuted} />
      ) : (
        <Text style={{ color: COLORS.textMuted, fontSize: 18 }}>›</Text>
      )}
    </TouchableOpacity>
  );
}

export default function SettingsScreen({ navigation }) {
  const [settings, setSettings] = useState({
    ghostShield:     true,
    safeSearch:       true,
    sosEnabled:       true,
    fallDetection:    true,
    speedAlerts:      true,
    bedtimeEnforced:  true,
    vpnDetection:     true,
    wifiGuard:        true,
    screenCapture:    true,
    socialScan:       true,
    contactMonitor:   true,
  });
  const [child, setChild] = useState(null);

  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    const { data } = await supabase.from('child_current_status').select('*').single();
    if (data) setChild(data);
  };

  const toggle = (key) => {
    setSettings(prev => ({ ...prev, [key]: !prev[key] }));
  };

  const sendCommand = async (command) => {
    if (!child?.id) return;
    await supabase.from('device_commands').insert({ child_id: child.id, command, status: 'pending' });
    Alert.alert('✅ Command Sent', `${command} sent to ${child.name || 'child'}'s device.`);
  };

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />
      <ScrollView showsVerticalScrollIndicator={false}>

        <View style={styles.header}>
          <Text style={styles.headerTitle}>⚙️ Settings</Text>
          <Text style={styles.headerSub}>Advanced safety configuration</Text>
        </View>

        {/* ── PROTECTION MODULES ──────────────────────── */}
        <Text style={styles.sectionTitle}>🛡️ PROTECTION MODULES</Text>
        <View style={styles.card}>
          <SettingRow icon="🌐" label="Ghost Shield (DNS Filter)" value="AdGuard Home" isSwitch switchValue={settings.ghostShield} onSwitch={() => toggle('ghostShield')} />
          <SettingRow icon="🔍" label="Force Safe Search" value="Google, YouTube, Bing" isSwitch switchValue={settings.safeSearch} onSwitch={() => toggle('safeSearch')} />
          <SettingRow icon="🆘" label="SOS Panic Button" value="5x Power / 3x Shake" isSwitch switchValue={settings.sosEnabled} onSwitch={() => toggle('sosEnabled')} />
          <SettingRow icon="🤸" label="Fall Detection" value="Accelerometer AI" isSwitch switchValue={settings.fallDetection} onSwitch={() => toggle('fallDetection')} />
          <SettingRow icon="🚗" label="Speed Alerts" value="> 80 km/h" isSwitch switchValue={settings.speedAlerts} onSwitch={() => toggle('speedAlerts')} />
          <SettingRow icon="🌙" label="Bedtime Enforcement" value="10PM - 7AM" isSwitch switchValue={settings.bedtimeEnforced} onSwitch={() => toggle('bedtimeEnforced')} />
          <SettingRow icon="🔓" label="VPN Bypass Detection" value="Block + Alert" isSwitch switchValue={settings.vpnDetection} onSwitch={() => toggle('vpnDetection')} />
          <SettingRow icon="📶" label="WiFi Guard" value="Rogue AP Detection" isSwitch switchValue={settings.wifiGuard} onSwitch={() => toggle('wifiGuard')} />
          <SettingRow icon="📸" label="AI Screen Capture" value="Every 30s" isSwitch switchValue={settings.screenCapture} onSwitch={() => toggle('screenCapture')} />
          <SettingRow icon="💬" label="Social Media Scanner" value="WhatsApp, Insta, TikTok" isSwitch switchValue={settings.socialScan} onSwitch={() => toggle('socialScan')} />
          <SettingRow icon="📞" label="Contact Monitor" value="Calls + SMS" isSwitch switchValue={settings.contactMonitor} onSwitch={() => toggle('contactMonitor')} />
        </View>

        {/* ── REMOTE ACTIONS ──────────────────────────── */}
        <Text style={styles.sectionTitle}>🎮 REMOTE ACTIONS</Text>
        <View style={styles.card}>
          <SettingRow icon="🔒" label="Lock Device Now" onPress={() => sendCommand('lock_device')} />
          <SettingRow icon="📷" label="Take Front Photo" onPress={() => sendCommand('take_photo_front')} />
          <SettingRow icon="📸" label="Take Back Photo" onPress={() => sendCommand('take_photo_back')} />
          <SettingRow icon="🔊" label="Start Ambient Audio" onPress={() => sendCommand('start_audio')} />
          <SettingRow icon="🎬" label="Live Camera Stream" onPress={() => navigation.navigate('Camera', { childId: child?.id })} />
          <SettingRow icon="📊" label="Behavior Reports" onPress={() => navigation.navigate('Reports')} />
          <SettingRow icon="🚨" label="Emergency Alarm" onPress={() => {
            Alert.alert('⚠️ Emergency Alarm', 'This will make the device vibrate and make noise. Proceed?', [
              { text: 'Cancel' }, { text: 'Activate', onPress: () => sendCommand('emergency_alert'), style: 'destructive' }
            ]);
          }} />
        </View>

        {/* ── NAVIGATION ──────────────────────────────── */}
        <Text style={styles.sectionTitle}>📁 MANAGEMENT</Text>
        <View style={styles.card}>
          <SettingRow icon="🛡️" label="Content Filter" value="Ghost Shield config" onPress={() => navigation.navigate('Content')} />
          <SettingRow icon="📱" label="App Rules" value={`${child?.blocked_apps || 0} blocked`} onPress={() => navigation.navigate('Apps')} />
          <SettingRow icon="📍" label="Geofence Zones" onPress={() => navigation.navigate('Location')} />
          <SettingRow icon="🆘" label="Emergency Contacts" onPress={() => navigation.navigate('Contacts')} />
        </View>

        {/* ── DEVICE INFO ─────────────────────────────── */}
        <Text style={styles.sectionTitle}>📱 DEVICE INFO</Text>
        <View style={styles.card}>
          <SettingRow icon="👶" label="Child Name" value={child?.name || '—'} />
          <SettingRow icon="📱" label="Device" value={child?.device_model || '—'} />
          <SettingRow icon="🔋" label="Battery" value={`${child?.battery_level || '--'}%`} />
          <SettingRow icon="🟢" label="Status" value={child?.is_online ? 'Online' : 'Offline'} />
        </View>

        {/* ── DANGER ZONE ─────────────────────────────── */}
        <Text style={styles.sectionTitle}>⚠️ DANGER ZONE</Text>
        <View style={[styles.card, { marginBottom: 40 }]}>
          <SettingRow icon="🗑️" label="Factory Reset Device" danger onPress={() => {
            Alert.alert('⚠️ FACTORY RESET', 'This will PERMANENTLY ERASE all data on the child\'s device. This cannot be undone!', [
              { text: 'Cancel' },
              { text: 'WIPE DEVICE', style: 'destructive', onPress: () => sendCommand('wipe_device') }
            ]);
          }} />
        </View>

      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container:     { flex: 1, backgroundColor: COLORS.bg },
  header:        { paddingTop: Platform.OS === 'ios' ? 56 : 16, paddingHorizontal: 20, paddingBottom: 16 },
  headerTitle:   { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800' },
  headerSub:     { color: COLORS.textSec, fontSize: 12, marginTop: 4 },
  sectionTitle:  { color: COLORS.textSec, fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 1.5, paddingHorizontal: 20, marginTop: 20, marginBottom: 8 },
  card:          { marginHorizontal: 16, backgroundColor: COLORS.card, borderRadius: 14, borderWidth: 1, borderColor: COLORS.cardBorder, overflow: 'hidden' },
  settingRow:    { flexDirection: 'row', alignItems: 'center', paddingVertical: 14, paddingHorizontal: 16, borderBottomWidth: 1, borderColor: COLORS.cardBorder },
  settingIcon:   { fontSize: 20, marginRight: 12, width: 28, textAlign: 'center' },
  settingInfo:   { flex: 1 },
  settingLabel:  { color: COLORS.textPrimary, fontSize: 14, fontWeight: '600' },
  settingValue:  { color: COLORS.textMuted, fontSize: 11, marginTop: 2 },
});
