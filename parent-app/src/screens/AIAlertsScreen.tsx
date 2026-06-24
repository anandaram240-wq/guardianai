import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, StyleSheet, FlatList, TouchableOpacity,
  Image, Modal, RefreshControl, Animated, StatusBar, Platform
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

const ALERT_ICONS = {
  adult_content:     '🔞',
  geofence_breach:   '📍',
  dangerous_keyword: '⚠️',
  unknown_contact:   '👤',
  sos:               '🆘',
  late_night:        '🌙',
  new_app:           '📱',
  cyberbullying:     '🚫',
  self_harm:         '💔',
  grooming:          '🚨',
  device_tampering:  '🔓',
};

const SEVERITY_COLORS = {
  critical: COLORS.red,
  warning:  COLORS.orange,
  info:     COLORS.blue,
};

function timeAgo(dateStr) {
  const diff = Date.now() - new Date(dateStr).getTime();
  const s = Math.floor(diff / 1000);
  if (s < 60)  return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60)  return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24)  return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

// ─── Filter Tabs ─────────────────────────────────────────────────────────────
function FilterTabs({ active, onChange }) {
  const tabs = [
    { key: 'all',      label: 'All' },
    { key: 'critical', label: '🔴 Critical' },
    { key: 'warning',  label: '🟠 Warning' },
    { key: 'info',     label: '🔵 Info' },
    { key: 'resolved', label: '✅ Resolved' },
  ];
  return (
    <View style={styles.filterRow}>
      {tabs.map(tab => (
        <TouchableOpacity
          key={tab.key}
          style={[styles.filterTab, active === tab.key && styles.filterTabActive]}
          onPress={() => onChange(tab.key)}
        >
          <Text style={[styles.filterTabText, active === tab.key && styles.filterTabTextActive]}>
            {tab.label}
          </Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

// ─── Alert Card ───────────────────────────────────────────────────────────────
function AlertCard({ alert, onResolve, onViewPhoto }) {
  const [expanded, setExpanded] = useState(false);
  const sevColor = SEVERITY_COLORS[alert.severity] || COLORS.blue;

  return (
    <TouchableOpacity
      style={[styles.alertCard, { borderLeftColor: sevColor }]}
      onPress={() => setExpanded(!expanded)}
      activeOpacity={0.8}
    >
      {/* Header row */}
      <View style={styles.alertRow}>
        <View style={[styles.alertIconBox, { backgroundColor: sevColor + '20', borderColor: sevColor + '50' }]}>
          <Text style={styles.alertIcon}>{ALERT_ICONS[alert.type] || '⚠️'}</Text>
        </View>

        <View style={styles.alertMain}>
          <View style={styles.alertTitleRow}>
            <Text style={styles.alertTitle} numberOfLines={expanded ? 0 : 1}>
              {alert.title}
            </Text>
            <View style={[styles.severityBadge, { backgroundColor: sevColor + '20' }]}>
              <Text style={[styles.severityText, { color: sevColor }]}>
                {alert.severity.toUpperCase()}
              </Text>
            </View>
          </View>

          <Text style={styles.alertBody} numberOfLines={expanded ? 0 : 2}>
            {alert.body}
          </Text>

          <Text style={styles.alertTime}>{timeAgo(alert.created_at)}</Text>
        </View>
      </View>

      {/* Expanded details */}
      {expanded && (
        <View style={styles.expandedSection}>
          {/* Screenshot preview */}
          {alert.screenshot_url && (
            <TouchableOpacity onPress={() => onViewPhoto(alert.screenshot_url)} style={styles.screenshotBtn}>
              <Image
                source={{ uri: alert.screenshot_url }}
                style={styles.screenshotThumb}
                resizeMode="cover"
              />
              <Text style={styles.screenshotLabel}>Tap to view screenshot →</Text>
            </TouchableOpacity>
          )}

          {/* Metadata */}
          {alert.metadata && Object.keys(alert.metadata).length > 0 && (
            <View style={styles.metaBox}>
              <Text style={styles.metaTitle}>Details:</Text>
              {Object.entries(alert.metadata).map(([k, v]) => (
                <Text key={k} style={styles.metaItem}>
                  <Text style={{ color: COLORS.textSec }}>{k}: </Text>
                  <Text style={{ color: COLORS.textPrimary }}>{String(v)}</Text>
                </Text>
              ))}
            </View>
          )}

          {/* Actions */}
          {!alert.resolved && (
            <TouchableOpacity
              style={styles.resolveBtn}
              onPress={() => onResolve(alert.id)}
            >
              <Text style={styles.resolveBtnText}>✅ Mark Resolved</Text>
            </TouchableOpacity>
          )}
        </View>
      )}
    </TouchableOpacity>
  );
}

// ─── Main AIAlertsScreen ──────────────────────────────────────────────────────
export default function AIAlertsScreen() {
  const [alerts, setAlerts]       = useState([]);
  const [filter, setFilter]       = useState('all');
  const [refreshing, setRefreshing] = useState(false);
  const [photoUrl, setPhotoUrl]   = useState(null);
  const [newAlertAnim]            = useState(new Animated.Value(0));

  const loadAlerts = useCallback(async () => {
    let query = supabase.from('alerts').select('*').order('created_at', { ascending: false }).limit(100);

    switch (filter) {
      case 'critical': query = query.eq('severity', 'critical').eq('resolved', false); break;
      case 'warning':  query = query.eq('severity', 'warning').eq('resolved', false);  break;
      case 'info':     query = query.eq('severity', 'info').eq('resolved', false);     break;
      case 'resolved': query = query.eq('resolved', true);                             break;
      default:         query = query.eq('resolved', false);
    }

    const { data, error } = await query;
    if (data) setAlerts(data);
  }, [filter]);

  useEffect(() => {
    loadAlerts();

    // Real-time: new alert animates in
    const sub = supabase
      .channel('alerts_realtime')
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'alerts' },
        (payload) => {
          setAlerts(prev => [payload.new, ...prev]);
          // Flash animation for new alert
          Animated.sequence([
            Animated.timing(newAlertAnim, { toValue: 1, duration: 300, useNativeDriver: true }),
            Animated.timing(newAlertAnim, { toValue: 0, duration: 300, useNativeDriver: true }),
          ]).start();
        }
      )
      .subscribe();

    return () => supabase.removeChannel(sub);
  }, [loadAlerts]);

  const onRefresh = async () => {
    setRefreshing(true);
    await loadAlerts();
    setRefreshing(false);
  };

  const resolveAlert = async (alertId) => {
    await supabase.from('alerts')
      .update({ resolved: true, resolved_at: new Date().toISOString() })
      .eq('id', alertId);
    setAlerts(prev => prev.filter(a => a.id !== alertId));
  };

  const criticalCount = alerts.filter(a => a.severity === 'critical' && !a.resolved).length;
  const warningCount  = alerts.filter(a => a.severity === 'warning'  && !a.resolved).length;

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />

      {/* ── Header ──────────────────────────────────────────────── */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>⚠️ AI Alerts</Text>
        <View style={styles.headerBadges}>
          {criticalCount > 0 && (
            <View style={[styles.countBadge, { backgroundColor: COLORS.red }]}>
              <Text style={styles.countBadgeText}>{criticalCount} Critical</Text>
            </View>
          )}
          {warningCount > 0 && (
            <View style={[styles.countBadge, { backgroundColor: COLORS.orange }]}>
              <Text style={styles.countBadgeText}>{warningCount} Warnings</Text>
            </View>
          )}
        </View>
      </View>

      {/* ── Filter Tabs ─────────────────────────────────────────── */}
      <FilterTabs active={filter} onChange={(f) => { setFilter(f); loadAlerts(); }} />

      {/* ── Alerts List ─────────────────────────────────────────── */}
      <FlatList
        data={alerts}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <AlertCard
            alert={item}
            onResolve={resolveAlert}
            onViewPhoto={(url) => setPhotoUrl(url)}
          />
        )}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={COLORS.purple} />}
        contentContainerStyle={{ paddingHorizontal: 16, paddingBottom: 32, paddingTop: 8 }}
        ListEmptyComponent={
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>🛡️</Text>
            <Text style={styles.emptyTitle}>All Clear!</Text>
            <Text style={styles.emptyText}>No {filter === 'all' ? '' : filter} alerts at this time.</Text>
          </View>
        }
      />

      {/* ── Screenshot Fullscreen Modal ──────────────────────────── */}
      <Modal visible={!!photoUrl} transparent animationType="fade" onRequestClose={() => setPhotoUrl(null)}>
        <View style={styles.photoModal}>
          <TouchableOpacity style={styles.photoClose} onPress={() => setPhotoUrl(null)}>
            <Text style={{ color: '#fff', fontSize: 28 }}>✕</Text>
          </TouchableOpacity>
          {photoUrl && (
            <Image source={{ uri: photoUrl }} style={styles.photoFull} resizeMode="contain" />
          )}
        </View>
      </Modal>
    </View>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  container:       { flex: 1, backgroundColor: COLORS.bg },
  header:          { paddingTop: Platform.OS === 'ios' ? 56 : 16, paddingHorizontal: 20, paddingBottom: 16, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  headerTitle:     { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800' },
  headerBadges:    { flexDirection: 'row', gap: 8 },
  countBadge:      { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 },
  countBadgeText:  { color: '#fff', fontSize: 11, fontWeight: '700' },
  filterRow:       { flexDirection: 'row', paddingHorizontal: 16, paddingBottom: 12, gap: 8 },
  filterTab:       { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 20, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder },
  filterTabActive: { backgroundColor: COLORS.purple + '30', borderColor: COLORS.purple },
  filterTabText:   { color: COLORS.textMuted, fontSize: 12, fontWeight: '600' },
  filterTabTextActive: { color: COLORS.purple },
  alertCard:       { backgroundColor: COLORS.card, borderRadius: 14, marginBottom: 10, borderLeftWidth: 4, borderWidth: 1, borderColor: COLORS.cardBorder, overflow: 'hidden' },
  alertRow:        { flexDirection: 'row', padding: 14, alignItems: 'flex-start' },
  alertIconBox:    { width: 44, height: 44, borderRadius: 12, justifyContent: 'center', alignItems: 'center', marginRight: 12, borderWidth: 1 },
  alertIcon:       { fontSize: 20 },
  alertMain:       { flex: 1 },
  alertTitleRow:   { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 },
  alertTitle:      { color: COLORS.textPrimary, fontSize: 14, fontWeight: '700', flex: 1, marginRight: 8 },
  severityBadge:   { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 8 },
  severityText:    { fontSize: 9, fontWeight: '800' },
  alertBody:       { color: COLORS.textSec, fontSize: 12, lineHeight: 18, marginBottom: 6 },
  alertTime:       { color: COLORS.textMuted, fontSize: 11 },
  expandedSection: { borderTopWidth: 1, borderColor: COLORS.cardBorder, padding: 14 },
  screenshotBtn:   { flexDirection: 'row', alignItems: 'center', marginBottom: 12, gap: 10 },
  screenshotThumb: { width: 80, height: 60, borderRadius: 8, backgroundColor: COLORS.bg },
  screenshotLabel: { color: COLORS.blue, fontSize: 12, fontWeight: '600' },
  metaBox:         { backgroundColor: COLORS.bg, borderRadius: 8, padding: 10, marginBottom: 12 },
  metaTitle:       { color: COLORS.textSec, fontSize: 11, fontWeight: '700', marginBottom: 6 },
  metaItem:        { fontSize: 12, marginBottom: 3 },
  resolveBtn:      { backgroundColor: COLORS.green + '20', borderRadius: 10, paddingVertical: 12, alignItems: 'center', borderWidth: 1, borderColor: COLORS.green + '60' },
  resolveBtnText:  { color: COLORS.green, fontSize: 14, fontWeight: '700' },
  emptyState:      { alignItems: 'center', paddingTop: 80 },
  emptyIcon:       { fontSize: 64, marginBottom: 16 },
  emptyTitle:      { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800', marginBottom: 8 },
  emptyText:       { color: COLORS.textSec, fontSize: 14 },
  photoModal:      { flex: 1, backgroundColor: '#000F', justifyContent: 'center', alignItems: 'center' },
  photoClose:      { position: 'absolute', top: 50, right: 20, zIndex: 1, padding: 8 },
  photoFull:       { width: '100%', height: '80%' },
});
