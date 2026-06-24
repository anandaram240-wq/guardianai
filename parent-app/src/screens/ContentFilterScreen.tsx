import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, ScrollView, Switch, TouchableOpacity,
  FlatList, Modal, StatusBar, Platform, Alert
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

const FILTER_CATEGORIES = {
  adult_content:  { icon: '🔞', label: 'Adult Content',        desc: '800,000+ adult & porn sites',    always: false, color: COLORS.red    },
  adultery_dating:{ icon: '💔', label: 'Dating & Adultery',    desc: '50,000+ affair/dating sites',    always: false, color: COLORS.red    },
  gambling:       { icon: '🎰', label: 'Gambling',             desc: '100,000+ gambling sites',        always: false, color: COLORS.orange },
  drugs_illegal:  { icon: '💊', label: 'Drugs & Illegal',      desc: '50,000+ illegal content sites',  always: false, color: COLORS.orange },
  social_media:   { icon: '📱', label: 'Social Media',         desc: '5,000+ social platforms',        always: false, color: COLORS.blue   },
  gaming:         { icon: '🎮', label: 'Gaming Sites',         desc: '10,000+ gaming sites',           always: false, color: COLORS.blue   },
  malware:        { icon: '🦠', label: 'Malware & Phishing',   desc: '200,000+ dangerous sites',       always: true,  color: COLORS.green  },
  ads_popups:     { icon: '📢', label: 'Ads & Popups',         desc: '2,000,000+ ad network domains',  always: true,  color: COLORS.green  },
};

function SectionHeader({ title }) {
  return <Text style={styles.sectionHeader}>{title}</Text>;
}

function FilterRow({ categoryKey, data, enabled, onChange }) {
  return (
    <View style={styles.filterRow}>
      <View style={[styles.filterIconBox, { backgroundColor: data.color + '20' }]}>
        <Text style={styles.filterIcon}>{data.icon}</Text>
      </View>
      <View style={styles.filterInfo}>
        <Text style={styles.filterLabel}>{data.label}</Text>
        <Text style={styles.filterDesc}>{data.desc}</Text>
      </View>
      {data.always ? (
        <View style={styles.alwaysChip}>
          <Text style={styles.alwaysText}>Always ON</Text>
        </View>
      ) : (
        <Switch
          value={enabled}
          onValueChange={onChange}
          trackColor={{ false: COLORS.cardBorder, true: data.color + '60' }}
          thumbColor={enabled ? data.color : COLORS.textMuted}
        />
      )}
    </View>
  );
}

function CustomDomainRow({ domain, onRemove }) {
  return (
    <View style={styles.domainRow}>
      <Text style={styles.domainText}>🚫 {domain}</Text>
      <TouchableOpacity onPress={onRemove} style={styles.removeBtn}>
        <Text style={{ color: COLORS.red, fontSize: 18 }}>✕</Text>
      </TouchableOpacity>
    </View>
  );
}

// ─── Content Filter Screen ────────────────────────────────────────────────────
export default function ContentFilterScreen() {
  const [toggles, setToggles] = useState({
    adult_content:   true,
    adultery_dating: true,
    gambling:        true,
    drugs_illegal:   true,
    social_media:    false,
    gaming:          false,
  });
  const [customBlocked, setCustomBlocked] = useState([]);
  const [customAllowed, setCustomAllowed] = useState([]);
  const [safeSearch, setSafeSearch]       = useState(true);
  const [dnsStatus, setDnsStatus]         = useState('checking...');
  const [lastUpdated, setLastUpdated]     = useState('');
  const [newDomain, setNewDomain]         = useState('');
  const [modalType, setModalType]         = useState(null); // 'block' | 'allow'
  const [totalBlocked, setTotalBlocked]   = useState('3,200,000+');

  useEffect(() => {
    checkDnsStatus();
    loadCustomLists();
    setLastUpdated(new Date().toLocaleDateString());
  }, []);

  const checkDnsStatus = async () => {
    // In production: ping AdGuard Home API to check status
    setDnsStatus('ACTIVE');
  };

  const loadCustomLists = async () => {
    // Load from Supabase (custom blocked/allowed domains per family)
    // This would call AdGuard Home API via our backend
  };

  const onToggle = (key, value) => {
    setToggles(prev => ({ ...prev, [key]: value }));
    // In production: update AdGuard Home filter via API
    Alert.alert(
      value ? '✅ Filter Enabled' : '🔓 Filter Disabled',
      `${FILTER_CATEGORIES[key].label} filter has been ${value ? 'enabled' : 'disabled'}.`,
      [{ text: 'OK' }]
    );
  };

  const addBlockedDomain = (domain) => {
    const clean = domain.toLowerCase().replace(/^https?:\/\//, '').replace(/\/$/, '');
    if (!clean) return;
    setCustomBlocked(prev => [clean, ...prev]);
    // In production: POST to AdGuard Home API
  };

  const removeBlockedDomain = (domain) => {
    setCustomBlocked(prev => prev.filter(d => d !== domain));
  };

  const refreshBlocklists = async () => {
    // Call our API server → AdGuard Home API → refresh all blocklists
    Alert.alert('🔄 Refreshing...', 'Blocklists are being updated from all sources. This may take 30 seconds.', [{ text: 'OK' }]);
    setLastUpdated(new Date().toLocaleDateString());
  };

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />
      <ScrollView showsVerticalScrollIndicator={false}>

        {/* ── Header Banner ──────────────────────────────────────── */}
        <View style={styles.header}>
          <View style={styles.shieldBanner}>
            <Text style={styles.shieldIcon}>🛡️</Text>
            <View>
              <Text style={styles.shieldTitle}>Ghost Shield</Text>
              <Text style={styles.shieldSubtitle}>Silent content filtering — active 24/7</Text>
            </View>
          </View>
          <Text style={styles.shieldDesc}>
            Harmful content is blocked at the DNS level. Child sees nothing unusual — apps work normally, 
            harmful sites simply don't load. Zero popups, zero alerts to child.
          </Text>
        </View>

        {/* ── DNS Status ─────────────────────────────────────────── */}
        <View style={styles.section}>
          <SectionHeader title="🌐 DNS Filter Status" />
          <View style={styles.dnsCard}>
            <View style={styles.dnsLeft}>
              <View style={[styles.dnsStatusDot, {
                backgroundColor: dnsStatus === 'ACTIVE' ? COLORS.green : COLORS.red
              }]} />
              <View>
                <Text style={[styles.dnsStatus, {
                  color: dnsStatus === 'ACTIVE' ? COLORS.green : COLORS.red
                }]}>{dnsStatus}</Text>
                <Text style={styles.dnsServer}>AdGuard Home (Oracle Free VM)</Text>
              </View>
            </View>
            <View style={styles.blockedCount}>
              <Text style={styles.blockedNum}>{totalBlocked}</Text>
              <Text style={styles.blockedLabel}>domains blocked</Text>
            </View>
          </View>
        </View>

        {/* ── Category Toggles ───────────────────────────────────── */}
        <View style={styles.section}>
          <SectionHeader title="🔘 Content Categories" />
          <View style={styles.card}>
            {Object.entries(FILTER_CATEGORIES).map(([key, data], idx) => (
              <View key={key}>
                {idx > 0 && <View style={styles.divider} />}
                <FilterRow
                  categoryKey={key}
                  data={data}
                  enabled={data.always ? true : (toggles[key] ?? false)}
                  onChange={(v) => !data.always && onToggle(key, v)}
                />
              </View>
            ))}
          </View>
        </View>

        {/* ── Safe Search ────────────────────────────────────────── */}
        <View style={styles.section}>
          <SectionHeader title="🔍 Safe Search" />
          <View style={styles.card}>
            <View style={styles.filterRow}>
              <View style={[styles.filterIconBox, { backgroundColor: COLORS.blue + '20' }]}>
                <Text style={styles.filterIcon}>🔍</Text>
              </View>
              <View style={styles.filterInfo}>
                <Text style={styles.filterLabel}>Force Safe Search</Text>
                <Text style={styles.filterDesc}>
                  Google, YouTube, Bing, DuckDuckGo — all forced to safe mode
                </Text>
              </View>
              <Switch
                value={safeSearch}
                onValueChange={setSafeSearch}
                trackColor={{ false: COLORS.cardBorder, true: COLORS.blue + '60' }}
                thumbColor={safeSearch ? COLORS.blue : COLORS.textMuted}
              />
            </View>
          </View>
        </View>

        {/* ── Custom Block List ───────────────────────────────────── */}
        <View style={styles.section}>
          <View style={styles.rowBetween}>
            <SectionHeader title="🚫 Custom Block List" />
            <TouchableOpacity
              style={styles.addBtn}
              onPress={() => setModalType('block')}
            >
              <Text style={styles.addBtnText}>+ Add</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.card}>
            {customBlocked.length === 0 ? (
              <Text style={styles.emptyList}>No custom blocked domains. Tap + Add to block a specific website.</Text>
            ) : (
              customBlocked.map((d) => (
                <CustomDomainRow key={d} domain={d} onRemove={() => removeBlockedDomain(d)} />
              ))
            )}
          </View>
        </View>

        {/* ── Custom Allow List ───────────────────────────────────── */}
        <View style={styles.section}>
          <View style={styles.rowBetween}>
            <SectionHeader title="✅ Custom Allow List (Whitelist)" />
            <TouchableOpacity
              style={[styles.addBtn, { backgroundColor: COLORS.green + '20', borderColor: COLORS.green + '60' }]}
              onPress={() => setModalType('allow')}
            >
              <Text style={[styles.addBtnText, { color: COLORS.green }]}>+ Allow</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.card}>
            {customAllowed.length === 0 ? (
              <Text style={styles.emptyList}>No whitelisted domains. Add trusted educational sites here.</Text>
            ) : (
              customAllowed.map((d) => (
                <View key={d} style={styles.domainRow}>
                  <Text style={[styles.domainText, { color: COLORS.green }]}>✅ {d}</Text>
                  <TouchableOpacity onPress={() => setCustomAllowed(prev => prev.filter(x => x !== d))}>
                    <Text style={{ color: COLORS.red, fontSize: 18 }}>✕</Text>
                  </TouchableOpacity>
                </View>
              ))
            )}
          </View>
        </View>

        {/* ── Blocklist Update ────────────────────────────────────── */}
        <View style={[styles.section, { paddingBottom: 40 }]}>
          <View style={styles.card}>
            <View style={styles.rowBetween}>
              <View>
                <Text style={styles.filterLabel}>Blocklist Sources</Text>
                <Text style={styles.filterDesc}>Last updated: {lastUpdated}</Text>
                <Text style={[styles.filterDesc, { marginTop: 4 }]}>
                  12 free community blocklists active
                </Text>
              </View>
              <TouchableOpacity style={styles.refreshBtn} onPress={refreshBlocklists}>
                <Text style={styles.refreshBtnText}>🔄 Refresh</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>

      </ScrollView>
    </View>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  container:      { flex: 1, backgroundColor: COLORS.bg },
  header:         { backgroundColor: COLORS.card, margin: 16, borderRadius: 16, padding: 16, borderWidth: 1, borderColor: COLORS.cardBorder },
  shieldBanner:   { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 12 },
  shieldIcon:     { fontSize: 40 },
  shieldTitle:    { color: COLORS.textPrimary, fontSize: 20, fontWeight: '800' },
  shieldSubtitle: { color: COLORS.textSec, fontSize: 12, marginTop: 2 },
  shieldDesc:     { color: COLORS.textMuted, fontSize: 12, lineHeight: 18 },
  section:        { paddingHorizontal: 16, marginBottom: 8 },
  sectionHeader:  { color: COLORS.textSec, fontSize: 12, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 10, marginTop: 8 },
  card:           { backgroundColor: COLORS.card, borderRadius: 14, borderWidth: 1, borderColor: COLORS.cardBorder, overflow: 'hidden' },
  dnsCard:        { backgroundColor: COLORS.card, borderRadius: 14, borderWidth: 1, borderColor: COLORS.cardBorder, padding: 16, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  dnsLeft:        { flexDirection: 'row', alignItems: 'center', gap: 10 },
  dnsStatusDot:   { width: 12, height: 12, borderRadius: 6 },
  dnsStatus:      { fontSize: 15, fontWeight: '800' },
  dnsServer:      { color: COLORS.textMuted, fontSize: 11, marginTop: 2 },
  blockedCount:   { alignItems: 'flex-end' },
  blockedNum:     { color: COLORS.purple, fontSize: 18, fontWeight: '800' },
  blockedLabel:   { color: COLORS.textMuted, fontSize: 10 },
  filterRow:      { flexDirection: 'row', alignItems: 'center', padding: 14 },
  filterIconBox:  { width: 42, height: 42, borderRadius: 11, justifyContent: 'center', alignItems: 'center', marginRight: 12 },
  filterIcon:     { fontSize: 20 },
  filterInfo:     { flex: 1 },
  filterLabel:    { color: COLORS.textPrimary, fontSize: 14, fontWeight: '600' },
  filterDesc:     { color: COLORS.textMuted, fontSize: 11, marginTop: 2 },
  alwaysChip:     { paddingHorizontal: 10, paddingVertical: 4, backgroundColor: COLORS.green + '20', borderRadius: 10, borderWidth: 1, borderColor: COLORS.green + '50' },
  alwaysText:     { color: COLORS.green, fontSize: 10, fontWeight: '700' },
  divider:        { height: 1, backgroundColor: COLORS.cardBorder, marginHorizontal: 14 },
  rowBetween:     { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  addBtn:         { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 10, backgroundColor: COLORS.red + '20', borderWidth: 1, borderColor: COLORS.red + '60', marginBottom: 10 },
  addBtnText:     { color: COLORS.red, fontSize: 12, fontWeight: '700' },
  domainRow:      { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 12, borderBottomWidth: 1, borderColor: COLORS.cardBorder },
  domainText:     { color: COLORS.textPrimary, fontSize: 13 },
  removeBtn:      { padding: 4 },
  emptyList:      { color: COLORS.textMuted, fontSize: 12, padding: 16, textAlign: 'center', lineHeight: 18 },
  refreshBtn:     { paddingHorizontal: 14, paddingVertical: 8, backgroundColor: COLORS.purple + '20', borderRadius: 10, borderWidth: 1, borderColor: COLORS.purple + '60' },
  refreshBtnText: { color: COLORS.purple, fontSize: 12, fontWeight: '700' },
});
