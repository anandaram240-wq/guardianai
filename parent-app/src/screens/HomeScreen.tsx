import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, StyleSheet, ScrollView, TouchableOpacity,
  RefreshControl, Dimensions, Platform, StatusBar, Animated
} from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = 'https://YOUR_PROJECT.supabase.co';
const SUPABASE_ANON_KEY = 'YOUR_ANON_KEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

const { width } = Dimensions.get('window');

// ─── Color System ────────────────────────────────────────────────────────────
const COLORS = {
  bg:          '#080C18',
  card:        '#10162A',
  cardBorder:  '#1E2D50',
  purple:      '#7C3AED',
  purpleLight: '#A78BFA',
  blue:        '#3B82F6',
  green:       '#10B981',
  red:         '#EF4444',
  orange:      '#F59E0B',
  textPrimary: '#F1F5F9',
  textSec:     '#94A3B8',
  textMuted:   '#475569',
};

// ─── Risk Score Color ─────────────────────────────────────────────────────────
function riskColor(score) {
  if (score < 30) return COLORS.green;
  if (score < 70) return COLORS.orange;
  return COLORS.red;
}

function riskLabel(score) {
  if (score < 30) return 'Safe';
  if (score < 70) return 'Moderate Risk';
  return 'High Risk';
}

function formatDuration(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function timeAgo(dateStr) {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

// ─── Stat Card ────────────────────────────────────────────────────────────────
function StatCard({ icon, label, value, color, onPress }) {
  return (
    <TouchableOpacity style={[styles.statCard, { borderColor: color + '40' }]} onPress={onPress} activeOpacity={0.7}>
      <Text style={styles.statIcon}>{icon}</Text>
      <Text style={[styles.statValue, { color }]}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </TouchableOpacity>
  );
}

// ─── Alert Badge ──────────────────────────────────────────────────────────────
function AlertBadge({ type, severity }) {
  const icons = {
    adult_content:      '🔞',
    geofence_breach:    '📍',
    dangerous_keyword:  '⚠️',
    unknown_contact:    '👤',
    sos:                '🆘',
    late_night:         '🌙',
    new_app:            '📱',
    cyberbullying:      '🚫',
    self_harm:          '💔',
    grooming:           '🚨',
    device_tampering:   '🔓',
  };
  const bgColors = { critical: '#EF444420', warning: '#F59E0B20', info: '#3B82F620' };
  const borderColors = { critical: '#EF4444', warning: '#F59E0B', info: '#3B82F6' };
  return (
    <View style={[styles.alertBadge, {
      backgroundColor: bgColors[severity] || bgColors.info,
      borderColor: borderColors[severity] || borderColors.info,
    }]}>
      <Text style={styles.alertBadgeIcon}>{icons[type] || '⚠️'}</Text>
    </View>
  );
}

// ─── Main HomeScreen ──────────────────────────────────────────────────────────
export default function HomeScreen({ navigation }) {
  const [child, setChild]       = useState(null);
  const [alerts, setAlerts]     = useState([]);
  const [stats, setStats]       = useState({
    screenTime: 0, blockedCount: 0, alertCount: 0, locationUpdates: 0
  });
  const [refreshing, setRefreshing] = useState(false);
  const pulseAnim = new Animated.Value(1);

  // Pulse animation for online indicator
  useEffect(() => {
    if (child?.is_online) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.3, duration: 800, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1.0, duration: 800, useNativeDriver: true }),
        ])
      ).start();
    }
  }, [child?.is_online]);

  const loadData = useCallback(async () => {
    try {
      // Load child current status
      const { data: childData } = await supabase
        .from('child_current_status')
        .select('*')
        .single();
      if (childData) setChild(childData);

      // Load recent alerts (last 3 unresolved)
      const { data: alertData } = await supabase
        .from('alerts')
        .select('*')
        .eq('resolved', false)
        .order('created_at', { ascending: false })
        .limit(3);
      if (alertData) setAlerts(alertData);

      // Stats from child_current_status view
      if (childData) {
        setStats({
          screenTime:      childData.today_screen_time || 0,
          blockedCount:    childData.today_blocked_count || 0,
          alertCount:      childData.unread_alerts || 0,
          locationUpdates: 0,
        });
      }
    } catch (err) {
      console.error('Load error:', err);
    }
  }, []);

  useEffect(() => {
    loadData();

    // Real-time updates via Supabase Realtime (free)
    const sub = supabase
      .channel('home_realtime')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'alerts' }, () => loadData())
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'children' }, () => loadData())
      .subscribe();

    return () => supabase.removeChannel(sub);
  }, [loadData]);

  const onRefresh = async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  };

  const sendCommand = async (command) => {
    if (!child?.id) return;
    await supabase.from('device_commands').insert({
      child_id: child.id,
      command,
      status: 'pending',
    });
  };

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor={COLORS.bg} />
      <ScrollView
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={COLORS.purple} />}
        showsVerticalScrollIndicator={false}
      >
        {/* ── Header ─────────────────────────────────────────────── */}
        <LinearGradient
          colors={['#1A0533', '#0D1B3E', COLORS.bg]}
          style={styles.header}
        >
          <View style={styles.headerTop}>
            <View>
              <Text style={styles.appName}>🛡️ GuardianAI</Text>
              <Text style={styles.headerSub}>Child Safety Dashboard</Text>
            </View>
            <View style={styles.liveChip}>
              <View style={[styles.liveDot, { backgroundColor: child?.is_online ? COLORS.green : COLORS.red }]} />
              <Text style={[styles.liveText, { color: child?.is_online ? COLORS.green : COLORS.red }]}>
                {child?.is_online ? 'LIVE' : 'OFFLINE'}
              </Text>
            </View>
          </View>

          {/* ── Child Status Card ───────────────────────────────── */}
          {child && (
            <View style={styles.childCard}>
              <View style={styles.childAvatarRow}>
                <View style={styles.childAvatar}>
                  <Text style={styles.childAvatarText}>
                    {child.name?.charAt(0).toUpperCase() || '?'}
                  </Text>
                  {child.is_online && (
                    <Animated.View style={[styles.onlinePulse, { transform: [{ scale: pulseAnim }] }]} />
                  )}
                </View>
                <View style={styles.childInfo}>
                  <Text style={styles.childName}>{child.name || 'Child'}</Text>
                  <Text style={styles.childMeta}>Age {child.age} • {child.device_model || 'Android'}</Text>
                  <Text style={styles.childLastSeen}>
                    {child.is_online ? 'Online now' : `Last seen ${timeAgo(child.last_seen)}`}
                  </Text>
                </View>
                {/* Battery */}
                <View style={styles.batteryContainer}>
                  <Text style={styles.batteryIcon}>🔋</Text>
                  <Text style={[styles.batteryText, {
                    color: (child.battery_level || 0) < 20 ? COLORS.red : COLORS.green
                  }]}>
                    {child.battery_level || '--'}%
                  </Text>
                </View>
              </View>

              {/* Risk Score */}
              <View style={styles.riskRow}>
                <View style={[styles.riskBadge, { backgroundColor: riskColor(child.risk_score) + '20', borderColor: riskColor(child.risk_score) + '60' }]}>
                  <Text style={[styles.riskScore, { color: riskColor(child.risk_score) }]}>
                    {child.risk_score || 0}
                  </Text>
                  <Text style={[styles.riskLabel, { color: riskColor(child.risk_score) }]}>
                    {riskLabel(child.risk_score || 0)}
                  </Text>
                </View>
                <Text style={styles.riskDesc}>
                  Risk score based on today's activity, alerts, and AI analysis.
                </Text>
              </View>
            </View>
          )}
        </LinearGradient>

        {/* ── Today's Stats ──────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>📊 Today's Summary</Text>
          <View style={styles.statsGrid}>
            <StatCard icon="⏱️" label="Screen Time" value={formatDuration(stats.screenTime)} color={COLORS.blue} onPress={() => navigation.navigate('Apps')} />
            <StatCard icon="🚫" label="Blocked" value={stats.blockedCount.toString()} color={COLORS.green} onPress={() => navigation.navigate('Content')} />
            <StatCard icon="⚠️" label="Alerts" value={stats.alertCount.toString()} color={stats.alertCount > 0 ? COLORS.red : COLORS.textSec} onPress={() => navigation.navigate('Alerts')} />
            <StatCard icon="📍" label="Location" value="Live" color={COLORS.purple} onPress={() => navigation.navigate('Location')} />
          </View>
        </View>

        {/* ── Quick Actions ──────────────────────────────────────── */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>⚡ Quick Actions</Text>
          <View style={styles.actionsRow}>
            {[
              { icon: '📍', label: 'Location',  nav: 'Location',   cmd: null },
              { icon: '📷', label: 'Camera',    nav: 'Camera',     cmd: 'start_camera' },
              { icon: '🔊', label: 'Listen',    nav: 'Audio',      cmd: 'start_audio' },
              { icon: '🔒', label: 'Lock',      nav: null,         cmd: 'lock_device' },
            ].map((action) => (
              <TouchableOpacity
                key={action.label}
                style={styles.actionBtn}
                onPress={() => {
                  if (action.nav) navigation.navigate(action.nav);
                  if (action.cmd) sendCommand(action.cmd);
                }}
                activeOpacity={0.7}
              >
                <LinearGradient colors={[COLORS.purple + '30', COLORS.blue + '20']} style={styles.actionBtnInner}>
                  <Text style={styles.actionIcon}>{action.icon}</Text>
                  <Text style={styles.actionLabel}>{action.label}</Text>
                </LinearGradient>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* ── Recent Alerts ──────────────────────────────────────── */}
        <View style={[styles.section, { paddingBottom: 32 }]}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>🔔 Recent Alerts</Text>
            <TouchableOpacity onPress={() => navigation.navigate('Alerts')}>
              <Text style={styles.seeAll}>See All →</Text>
            </TouchableOpacity>
          </View>

          {alerts.length === 0 ? (
            <View style={styles.emptyAlerts}>
              <Text style={styles.emptyIcon}>✅</Text>
              <Text style={styles.emptyText}>All clear! No recent alerts.</Text>
            </View>
          ) : (
            alerts.map((alert) => (
              <TouchableOpacity
                key={alert.id}
                style={[styles.alertCard, { borderLeftColor:
                  alert.severity === 'critical' ? COLORS.red :
                  alert.severity === 'warning'  ? COLORS.orange : COLORS.blue
                }]}
                onPress={() => navigation.navigate('Alerts')}
                activeOpacity={0.7}
              >
                <AlertBadge type={alert.type} severity={alert.severity} />
                <View style={styles.alertContent}>
                  <Text style={styles.alertTitle} numberOfLines={1}>{alert.title}</Text>
                  <Text style={styles.alertBody} numberOfLines={2}>{alert.body}</Text>
                  <Text style={styles.alertTime}>{timeAgo(alert.created_at)}</Text>
                </View>
              </TouchableOpacity>
            ))
          )}
        </View>
      </ScrollView>
    </View>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  container:       { flex: 1, backgroundColor: COLORS.bg },
  header:          { paddingTop: Platform.OS === 'ios' ? 50 : StatusBar.currentHeight + 10, paddingHorizontal: 20, paddingBottom: 24 },
  headerTop:       { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 },
  appName:         { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800', letterSpacing: 0.5 },
  headerSub:       { color: COLORS.textSec, fontSize: 12, marginTop: 2 },
  liveChip:        { flexDirection: 'row', alignItems: 'center', backgroundColor: COLORS.card, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 20, borderWidth: 1, borderColor: COLORS.cardBorder },
  liveDot:         { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  liveText:        { fontSize: 12, fontWeight: '700' },
  childCard:       { backgroundColor: COLORS.card + 'CC', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: COLORS.cardBorder },
  childAvatarRow:  { flexDirection: 'row', alignItems: 'center', marginBottom: 16 },
  childAvatar:     { width: 56, height: 56, borderRadius: 28, backgroundColor: COLORS.purple + '40', justifyContent: 'center', alignItems: 'center', marginRight: 14, borderWidth: 2, borderColor: COLORS.purple + '60' },
  childAvatarText: { color: COLORS.purple, fontSize: 24, fontWeight: '800' },
  onlinePulse:     { position: 'absolute', bottom: 2, right: 2, width: 14, height: 14, borderRadius: 7, backgroundColor: COLORS.green, borderWidth: 2, borderColor: COLORS.card },
  childInfo:       { flex: 1 },
  childName:       { color: COLORS.textPrimary, fontSize: 20, fontWeight: '700' },
  childMeta:       { color: COLORS.textSec, fontSize: 13, marginTop: 2 },
  childLastSeen:   { color: COLORS.textMuted, fontSize: 12, marginTop: 4 },
  batteryContainer:{ alignItems: 'center' },
  batteryIcon:     { fontSize: 20 },
  batteryText:     { fontSize: 13, fontWeight: '700', marginTop: 2 },
  riskRow:         { flexDirection: 'row', alignItems: 'center', gap: 12 },
  riskBadge:       { alignItems: 'center', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 12, borderWidth: 1, minWidth: 80 },
  riskScore:       { fontSize: 28, fontWeight: '900' },
  riskLabel:       { fontSize: 11, fontWeight: '600', marginTop: 2 },
  riskDesc:        { flex: 1, color: COLORS.textMuted, fontSize: 12, lineHeight: 18 },
  section:         { paddingHorizontal: 20, paddingTop: 24 },
  sectionHeader:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 },
  sectionTitle:    { color: COLORS.textPrimary, fontSize: 16, fontWeight: '700', marginBottom: 14 },
  seeAll:          { color: COLORS.purple, fontSize: 13, fontWeight: '600' },
  statsGrid:       { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  statCard:        { flex: 1, minWidth: (width - 52) / 2, backgroundColor: COLORS.card, borderRadius: 14, padding: 16, alignItems: 'center', borderWidth: 1 },
  statIcon:        { fontSize: 24, marginBottom: 8 },
  statValue:       { fontSize: 22, fontWeight: '800' },
  statLabel:       { color: COLORS.textSec, fontSize: 12, marginTop: 4, textAlign: 'center' },
  actionsRow:      { flexDirection: 'row', gap: 10 },
  actionBtn:       { flex: 1 },
  actionBtnInner:  { borderRadius: 14, padding: 16, alignItems: 'center', borderWidth: 1, borderColor: COLORS.cardBorder },
  actionIcon:      { fontSize: 24, marginBottom: 6 },
  actionLabel:     { color: COLORS.textPrimary, fontSize: 12, fontWeight: '600' },
  alertCard:       { backgroundColor: COLORS.card, borderRadius: 12, padding: 14, marginBottom: 10, flexDirection: 'row', alignItems: 'flex-start', borderLeftWidth: 4, borderWidth: 1, borderColor: COLORS.cardBorder },
  alertBadge:      { width: 40, height: 40, borderRadius: 10, justifyContent: 'center', alignItems: 'center', marginRight: 12, borderWidth: 1 },
  alertBadgeIcon:  { fontSize: 18 },
  alertContent:    { flex: 1 },
  alertTitle:      { color: COLORS.textPrimary, fontSize: 14, fontWeight: '700', marginBottom: 4 },
  alertBody:       { color: COLORS.textSec, fontSize: 12, lineHeight: 18, marginBottom: 6 },
  alertTime:       { color: COLORS.textMuted, fontSize: 11 },
  emptyAlerts:     { alignItems: 'center', paddingVertical: 32, backgroundColor: COLORS.card, borderRadius: 14, borderWidth: 1, borderColor: COLORS.cardBorder },
  emptyIcon:       { fontSize: 40, marginBottom: 10 },
  emptyText:       { color: COLORS.textSec, fontSize: 14 },
});
