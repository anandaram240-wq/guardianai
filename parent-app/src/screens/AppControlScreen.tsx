import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, FlatList, TouchableOpacity,
  Switch, Alert, Modal, TextInput, ScrollView, StatusBar, Platform
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

const APP_ICONS = { youtube: '📺', tiktok: '🎵', instagram: '📸', snapchat: '👻', games: '🎮', whatsapp: '💬', telegram: '✈️', chrome: '🌐' };

function getAppIcon(name) {
  const n = name.toLowerCase();
  for (const [key, icon] of Object.entries(APP_ICONS)) {
    if (n.includes(key)) return icon;
  }
  return '📱';
}

function formatTime(seconds) {
  if (!seconds || seconds < 60) return `${seconds || 0}s`;
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

// ─── App Rule Card ────────────────────────────────────────────────────────────
function AppRuleCard({ app, onToggleBlock, onSetLimit, onSetSchedule, onBonusTime }) {
  const [expanded, setExpanded] = useState(false);
  const [timeLimit, setTimeLimit] = useState(app.daily_limit_minutes?.toString() || '');

  return (
    <TouchableOpacity onPress={() => setExpanded(!expanded)} activeOpacity={0.85}
      style={[styles.appCard, app.is_blocked && styles.appCardBlocked]}>

      {/* Main row */}
      <View style={styles.appRow}>
        <Text style={styles.appIcon}>{getAppIcon(app.app_name)}</Text>
        <View style={styles.appInfo}>
          <Text style={[styles.appName, app.is_blocked && { color: COLORS.red }]}>
            {app.app_name}
          </Text>
          <Text style={styles.appPkg}>{app.app_package?.split('.').slice(-2).join('.')}</Text>
          {app.daily_limit_minutes > 0 && (
            <Text style={styles.limitBadge}>⏱️ {app.daily_limit_minutes} min/day</Text>
          )}
        </View>
        <View style={styles.appActions}>
          <Switch
            value={app.is_blocked}
            onValueChange={(v) => onToggleBlock(app, v)}
            trackColor={{ false: COLORS.cardBorder, true: COLORS.red + '80' }}
            thumbColor={app.is_blocked ? COLORS.red : COLORS.green}
          />
          <Text style={{ color: app.is_blocked ? COLORS.red : COLORS.green, fontSize: 10, fontWeight: '700', marginTop: 2 }}>
            {app.is_blocked ? 'BLOCKED' : 'ALLOWED'}
          </Text>
        </View>
      </View>

      {/* Expanded controls */}
      {expanded && (
        <View style={styles.expandedControls}>
          {/* Time limit */}
          <View style={styles.controlRow}>
            <Text style={styles.controlLabel}>⏱️ Daily Limit (minutes)</Text>
            <View style={styles.limitRow}>
              {[15, 30, 60, 120, -1].map(mins => (
                <TouchableOpacity key={mins}
                  style={[styles.limitPill, app.daily_limit_minutes === mins && styles.limitPillActive]}
                  onPress={() => onSetLimit(app, mins)}>
                  <Text style={[styles.limitPillText, app.daily_limit_minutes === mins && { color: COLORS.purple }]}>
                    {mins === -1 ? '∞' : `${mins}m`}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>

          {/* Time window */}
          <View style={styles.controlRow}>
            <Text style={styles.controlLabel}>🕐 Allowed Hours</Text>
            <View style={styles.scheduleRow}>
              <Text style={styles.scheduleText}>
                {app.allowed_start_time || '00:00'} — {app.allowed_end_time || '23:59'}
              </Text>
              <TouchableOpacity style={styles.editBtn}
                onPress={() => onSetSchedule(app)}>
                <Text style={{ color: COLORS.purple, fontSize: 12 }}>Edit</Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Bonus time */}
          <TouchableOpacity style={styles.bonusBtn} onPress={() => onBonusTime(app)}>
            <Text style={styles.bonusBtnText}>🎁 Grant 30 min bonus</Text>
          </TouchableOpacity>
        </View>
      )}
    </TouchableOpacity>
  );
}

// ─── Main AppControlScreen ────────────────────────────────────────────────────
export default function AppControlScreen() {
  const [apps, setApps]           = useState([]);
  const [filter, setFilter]       = useState('all'); // all, blocked, limited
  const [totalLimit, setTotalLimit] = useState(-1);
  const [bedtime, setBedtime]     = useState('22:00');

  useEffect(() => { loadApps(); loadGlobalSettings(); }, []);

  const loadApps = async () => {
    const { data } = await supabase.from('app_rules').select('*').order('app_name');
    if (data) setApps(data);
  };

  const loadGlobalSettings = async () => {
    // Load from children table or separate settings
  };

  const toggleBlock = async (app, blocked) => {
    await supabase.from('app_rules')
      .update({ is_blocked: blocked })
      .eq('id', app.id);
    loadApps();
  };

  const setLimit = async (app, minutes) => {
    await supabase.from('app_rules')
      .update({ daily_limit_minutes: minutes })
      .eq('id', app.id);
    loadApps();
  };

  const setSchedule = (app) => {
    Alert.prompt('Set Allowed Hours', `Enter format: 08:00-21:00`, [
      { text: 'Cancel' },
      { text: 'Save', onPress: async (text) => {
        const parts = text?.split('-');
        if (parts?.length === 2) {
          await supabase.from('app_rules')
            .update({ allowed_start_time: parts[0].trim(), allowed_end_time: parts[1].trim() })
            .eq('id', app.id);
          loadApps();
        }
      }}
    ]);
  };

  const grantBonus = async (app) => {
    const current = app.bonus_minutes || 0;
    await supabase.from('app_rules')
      .update({ bonus_minutes: current + 30 })
      .eq('id', app.id);
    Alert.alert('🎁 Bonus Granted', `+30 minutes added to ${app.app_name} today!`);
    loadApps();
  };

  const blockAllSocialMedia = async () => {
    const socialApps = ['com.instagram.android', 'com.snapchat.android', 'com.zhiliaoapp.musically',
      'com.twitter.android', 'com.facebook.katana'];
    for (const pkg of socialApps) {
      await supabase.from('app_rules').update({ is_blocked: true }).eq('app_package', pkg);
    }
    Alert.alert('🚫 All Social Media Blocked');
    loadApps();
  };

  const filtered = apps.filter(a => {
    if (filter === 'blocked') return a.is_blocked;
    if (filter === 'limited') return a.daily_limit_minutes > 0;
    return true;
  });

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />

      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>📱 App Control</Text>
        <Text style={styles.headerSub}>{apps.filter(a => a.is_blocked).length} blocked • {apps.filter(a => a.daily_limit_minutes > 0).length} limited</Text>
      </View>

      {/* Quick Actions */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ maxHeight: 50, marginBottom: 12 }}
        contentContainerStyle={{ paddingHorizontal: 16, gap: 8 }}>
        {[
          { label: '🚫 Block All Social', action: blockAllSocialMedia, color: COLORS.red },
          { label: '🎮 Block All Games', action: () => {}, color: COLORS.orange },
          { label: '🔓 Unblock All', action: async () => { await supabase.from('app_rules').update({ is_blocked: false }).neq('app_package', ''); loadApps(); }, color: COLORS.green },
        ].map(btn => (
          <TouchableOpacity key={btn.label} style={[styles.quickBtn, { borderColor: btn.color + '60' }]} onPress={btn.action}>
            <Text style={[styles.quickBtnText, { color: btn.color }]}>{btn.label}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {/* Filter Tabs */}
      <View style={styles.filterRow}>
        {[['all','All Apps'], ['blocked','🚫 Blocked'], ['limited','⏱️ Limited']].map(([k,l]) => (
          <TouchableOpacity key={k}
            style={[styles.filterTab, filter === k && styles.filterTabActive]}
            onPress={() => setFilter(k)}>
            <Text style={[styles.filterText, filter === k && { color: COLORS.purple }]}>{l}</Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* App List */}
      <FlatList
        data={filtered}
        keyExtractor={item => item.id}
        renderItem={({ item }) => (
          <AppRuleCard app={item}
            onToggleBlock={toggleBlock}
            onSetLimit={setLimit}
            onSetSchedule={setSchedule}
            onBonusTime={grantBonus}
          />
        )}
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 32 }}
        ListEmptyComponent={
          <View style={styles.empty}>
            <Text style={styles.emptyIcon}>📱</Text>
            <Text style={styles.emptyText}>No apps found. Install the child agent to auto-detect installed apps.</Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: COLORS.bg },
  header: { paddingTop: Platform.OS === 'ios' ? 56 : 16, paddingHorizontal: 20, paddingBottom: 12 },
  headerTitle: { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800' },
  headerSub: { color: COLORS.textSec, fontSize: 12, marginTop: 4 },
  quickBtn: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 20, borderWidth: 1, backgroundColor: COLORS.card },
  quickBtnText: { fontSize: 12, fontWeight: '700' },
  filterRow: { flexDirection: 'row', paddingHorizontal: 16, marginBottom: 12, gap: 8 },
  filterTab: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 20, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder },
  filterTabActive: { backgroundColor: COLORS.purple + '20', borderColor: COLORS.purple },
  filterText: { color: COLORS.textMuted, fontSize: 12, fontWeight: '600' },
  appCard: { backgroundColor: COLORS.card, borderRadius: 14, marginBottom: 8, borderWidth: 1, borderColor: COLORS.cardBorder, overflow: 'hidden' },
  appCardBlocked: { borderColor: COLORS.red + '40' },
  appRow: { flexDirection: 'row', alignItems: 'center', padding: 14 },
  appIcon: { fontSize: 28, marginRight: 12 },
  appInfo: { flex: 1 },
  appName: { color: COLORS.textPrimary, fontSize: 15, fontWeight: '700' },
  appPkg: { color: COLORS.textMuted, fontSize: 10, marginTop: 2 },
  limitBadge: { color: COLORS.orange, fontSize: 11, fontWeight: '600', marginTop: 4 },
  appActions: { alignItems: 'center', marginLeft: 8 },
  expandedControls: { borderTopWidth: 1, borderColor: COLORS.cardBorder, padding: 14 },
  controlRow: { marginBottom: 16 },
  controlLabel: { color: COLORS.textSec, fontSize: 12, fontWeight: '600', marginBottom: 8 },
  limitRow: { flexDirection: 'row', gap: 8 },
  limitPill: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 20, backgroundColor: COLORS.bg, borderWidth: 1, borderColor: COLORS.cardBorder },
  limitPillActive: { borderColor: COLORS.purple, backgroundColor: COLORS.purple + '20' },
  limitPillText: { color: COLORS.textMuted, fontSize: 12, fontWeight: '700' },
  scheduleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  scheduleText: { color: COLORS.textPrimary, fontSize: 14, fontWeight: '600' },
  editBtn: { paddingHorizontal: 12, paddingVertical: 4, borderRadius: 8, backgroundColor: COLORS.purple + '20' },
  bonusBtn: { backgroundColor: COLORS.green + '15', borderRadius: 10, paddingVertical: 12, alignItems: 'center', borderWidth: 1, borderColor: COLORS.green + '40' },
  bonusBtnText: { color: COLORS.green, fontSize: 14, fontWeight: '700' },
  empty: { alignItems: 'center', paddingTop: 60 },
  emptyIcon: { fontSize: 48, marginBottom: 12 },
  emptyText: { color: COLORS.textMuted, fontSize: 13, textAlign: 'center', paddingHorizontal: 40 },
});
