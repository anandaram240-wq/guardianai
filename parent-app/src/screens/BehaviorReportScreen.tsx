import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, ScrollView, TouchableOpacity,
  StatusBar, Platform, Dimensions
} from 'react-native';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = 'https://YOUR_PROJECT.supabase.co';
const SUPABASE_ANON_KEY = 'YOUR_ANON_KEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

const COLORS = {
  bg: '#080C18', card: '#10162A', cardBorder: '#1E2D50',
  purple: '#7C3AED', green: '#10B981', red: '#EF4444',
  orange: '#F59E0B', blue: '#3B82F6', cyan: '#06B6D4',
  textPrimary: '#F1F5F9', textSec: '#94A3B8', textMuted: '#475569',
};
const { width } = Dimensions.get('window');

function riskColor(s) { return s < 30 ? COLORS.green : s < 70 ? COLORS.orange : COLORS.red; }
function riskLabel(s) { return s < 30 ? 'Low Risk' : s < 70 ? 'Moderate' : 'High Risk'; }
function formatDuration(s) { const h = Math.floor(s/3600), m = Math.floor((s%3600)/60); return h > 0 ? `${h}h ${m}m` : `${m}m`; }

function RiskGauge({ score }) {
  const angle = (score / 100) * 180 - 90;
  return (
    <View style={styles.gaugeContainer}>
      <View style={styles.gaugeOuter}>
        <View style={[styles.gaugeSection, { backgroundColor: COLORS.green + '40', left: 0, width: '33%' }]} />
        <View style={[styles.gaugeSection, { backgroundColor: COLORS.orange + '40', left: '33%', width: '34%' }]} />
        <View style={[styles.gaugeSection, { backgroundColor: COLORS.red + '40', left: '67%', width: '33%' }]} />
      </View>
      <Text style={[styles.gaugeScore, { color: riskColor(score) }]}>{score}</Text>
      <Text style={[styles.gaugeLabel, { color: riskColor(score) }]}>{riskLabel(score)}</Text>
    </View>
  );
}

function BarChart({ data, maxValue, color }) {
  return (
    <View style={styles.barChart}>
      {data.map((item, i) => (
        <View key={i} style={styles.barItem}>
          <View style={styles.barTrack}>
            <View style={[styles.barFill, {
              height: `${Math.min((item.value / maxValue) * 100, 100)}%`,
              backgroundColor: color
            }]} />
          </View>
          <Text style={styles.barLabel}>{item.label}</Text>
        </View>
      ))}
    </View>
  );
}

function InsightCard({ icon, title, value, desc, color }) {
  return (
    <View style={[styles.insightCard, { borderLeftColor: color }]}>
      <Text style={styles.insightIcon}>{icon}</Text>
      <View style={styles.insightInfo}>
        <Text style={styles.insightTitle}>{title}</Text>
        <Text style={[styles.insightValue, { color }]}>{value}</Text>
        <Text style={styles.insightDesc}>{desc}</Text>
      </View>
    </View>
  );
}

// ─── Main BehaviorReportScreen ────────────────────────────────────────────────
export default function BehaviorReportScreen() {
  const [report, setReport]   = useState(null);
  const [weekData, setWeekData] = useState([]);
  const [topApps, setTopApps] = useState([]);
  const [period, setPeriod]   = useState('today');

  useEffect(() => { loadReport(); }, [period]);

  const loadReport = async () => {
    if (period === 'today') {
      const { data } = await supabase.rpc('get_child_daily_summary', {
        p_child_id: 'CHILD_UUID_HERE', p_date: new Date().toISOString().split('T')[0]
      });
      if (data) setReport(data);
    } else {
      const { data } = await supabase.from('behavior_reports')
        .select('*')
        .order('report_date', { ascending: false })
        .limit(1)
        .single();
      if (data) setReport(data);
    }

    // Load 7-day trend
    const weekStart = new Date(); weekStart.setDate(weekStart.getDate() - 6);
    const { data: weekReports } = await supabase.from('behavior_reports')
      .select('report_date, risk_score, total_screen_time, blocked_count')
      .gte('report_date', weekStart.toISOString().split('T')[0])
      .order('report_date');
    if (weekReports) setWeekData(weekReports);
  };

  const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const screenTimeByDay = weekData.map((d, i) => ({
    label: days[new Date(d.report_date).getDay()],
    value: d.total_screen_time / 60  // minutes
  }));
  const riskByDay = weekData.map((d, i) => ({
    label: days[new Date(d.report_date).getDay()],
    value: d.risk_score
  }));

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />
      <ScrollView showsVerticalScrollIndicator={false}>

        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.headerTitle}>📊 Behavior Report</Text>
          <Text style={styles.headerSub}>AI-generated insights about your child's digital behavior</Text>
        </View>

        {/* Period Tabs */}
        <View style={styles.periodRow}>
          {[['today','Today'], ['week','This Week'], ['month','This Month']].map(([k,l]) => (
            <TouchableOpacity key={k}
              style={[styles.periodTab, period === k && styles.periodTabActive]}
              onPress={() => setPeriod(k)}>
              <Text style={[styles.periodText, period === k && { color: COLORS.purple }]}>{l}</Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Risk Score Gauge */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>🎯 Overall Risk Score</Text>
          <View style={styles.gaugeCard}>
            <RiskGauge score={report?.risk_score || 0} />
            <Text style={styles.gaugeExplain}>
              Based on content blocks, alerts, screen time, late-night usage, and contact patterns.
            </Text>
          </View>
        </View>

        {/* Key Metrics */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>📈 Key Metrics</Text>
          <View style={styles.metricsGrid}>
            <InsightCard icon="⏱️" title="Screen Time" value={formatDuration(report?.screen_time_sec || 0)}
              desc="Total device usage" color={COLORS.blue} />
            <InsightCard icon="🚫" title="Blocked" value={report?.blocked_count?.toString() || '0'}
              desc="Content blocked" color={COLORS.green} />
            <InsightCard icon="⚠️" title="Alerts" value={report?.alert_count?.toString() || '0'}
              desc="Incidents detected" color={report?.critical_alerts > 0 ? COLORS.red : COLORS.orange} />
            <InsightCard icon="📍" title="Locations" value={report?.location_count?.toString() || '0'}
              desc="Location updates" color={COLORS.purple} />
          </View>
        </View>

        {/* Screen Time Trend */}
        {screenTimeByDay.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>📱 Screen Time (7 Days)</Text>
            <View style={styles.chartCard}>
              <BarChart data={screenTimeByDay} maxValue={Math.max(...screenTimeByDay.map(d => d.value), 120)} color={COLORS.blue} />
            </View>
          </View>
        )}

        {/* Risk Trend */}
        {riskByDay.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>🎯 Risk Score (7 Days)</Text>
            <View style={styles.chartCard}>
              <BarChart data={riskByDay} maxValue={100} color={COLORS.orange} />
            </View>
          </View>
        )}

        {/* Top Apps */}
        {report?.top_apps && report.top_apps.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>📱 Most Used Apps</Text>
            <View style={styles.appList}>
              {report.top_apps.slice(0, 5).map((app, i) => (
                <View key={i} style={styles.topAppRow}>
                  <Text style={styles.topAppRank}>#{i + 1}</Text>
                  <View style={styles.topAppInfo}>
                    <Text style={styles.topAppName}>{app.app_name}</Text>
                    <View style={styles.topAppBar}>
                      <View style={[styles.topAppFill, {
                        width: `${Math.min((app.duration / (report.top_apps[0]?.duration || 1)) * 100, 100)}%`
                      }]} />
                    </View>
                  </View>
                  <Text style={styles.topAppTime}>{formatDuration(app.duration)}</Text>
                </View>
              ))}
            </View>
          </View>
        )}

        {/* AI Anomalies */}
        {report?.anomalies && report.anomalies.length > 0 && (
          <View style={[styles.section, { paddingBottom: 8 }]}>
            <Text style={styles.sectionTitle}>🤖 AI Anomalies Detected</Text>
            {report.anomalies.map((anomaly, i) => (
              <View key={i} style={styles.anomalyCard}>
                <Text style={styles.anomalyIcon}>⚡</Text>
                <Text style={styles.anomalyText}>{anomaly}</Text>
              </View>
            ))}
          </View>
        )}

        {/* Recommendations */}
        {report?.recommendations && report.recommendations.length > 0 && (
          <View style={[styles.section, { paddingBottom: 40 }]}>
            <Text style={styles.sectionTitle}>💡 AI Recommendations</Text>
            {report.recommendations.map((rec, i) => (
              <View key={i} style={styles.recCard}>
                <Text style={styles.recIcon}>💡</Text>
                <Text style={styles.recText}>{rec}</Text>
              </View>
            ))}
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container:       { flex: 1, backgroundColor: COLORS.bg },
  header:          { paddingTop: Platform.OS === 'ios' ? 56 : 16, paddingHorizontal: 20, paddingBottom: 12 },
  headerTitle:     { color: COLORS.textPrimary, fontSize: 22, fontWeight: '800' },
  headerSub:       { color: COLORS.textSec, fontSize: 12, marginTop: 4 },
  periodRow:       { flexDirection: 'row', paddingHorizontal: 16, marginBottom: 16, gap: 8 },
  periodTab:       { flex: 1, paddingVertical: 8, borderRadius: 12, backgroundColor: COLORS.card, borderWidth: 1, borderColor: COLORS.cardBorder, alignItems: 'center' },
  periodTabActive: { backgroundColor: COLORS.purple + '20', borderColor: COLORS.purple },
  periodText:      { color: COLORS.textMuted, fontSize: 13, fontWeight: '700' },
  section:         { paddingHorizontal: 16, marginBottom: 16 },
  sectionTitle:    { color: COLORS.textPrimary, fontSize: 15, fontWeight: '700', marginBottom: 12 },
  gaugeCard:       { backgroundColor: COLORS.card, borderRadius: 16, padding: 24, alignItems: 'center', borderWidth: 1, borderColor: COLORS.cardBorder },
  gaugeContainer:  { alignItems: 'center', marginBottom: 12 },
  gaugeOuter:      { flexDirection: 'row', width: 200, height: 12, borderRadius: 6, overflow: 'hidden', marginBottom: 16 },
  gaugeSection:    { position: 'absolute', top: 0, height: 12 },
  gaugeScore:      { fontSize: 56, fontWeight: '900' },
  gaugeLabel:      { fontSize: 16, fontWeight: '700', marginTop: 4 },
  gaugeExplain:    { color: COLORS.textMuted, fontSize: 11, textAlign: 'center', lineHeight: 18, marginTop: 8 },
  metricsGrid:     { gap: 8 },
  insightCard:     { flexDirection: 'row', alignItems: 'center', backgroundColor: COLORS.card, borderRadius: 12, padding: 14, borderWidth: 1, borderColor: COLORS.cardBorder, borderLeftWidth: 4, marginBottom: 8 },
  insightIcon:     { fontSize: 24, marginRight: 12 },
  insightInfo:     { flex: 1 },
  insightTitle:    { color: COLORS.textSec, fontSize: 11, fontWeight: '600' },
  insightValue:    { fontSize: 22, fontWeight: '800', marginTop: 2 },
  insightDesc:     { color: COLORS.textMuted, fontSize: 10, marginTop: 2 },
  chartCard:       { backgroundColor: COLORS.card, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: COLORS.cardBorder },
  barChart:        { flexDirection: 'row', justifyContent: 'space-around', height: 120, alignItems: 'flex-end' },
  barItem:         { alignItems: 'center', flex: 1 },
  barTrack:        { width: 24, height: 100, backgroundColor: COLORS.bg, borderRadius: 4, overflow: 'hidden', justifyContent: 'flex-end' },
  barFill:         { width: '100%', borderRadius: 4, minHeight: 4 },
  barLabel:        { color: COLORS.textMuted, fontSize: 10, marginTop: 6, fontWeight: '600' },
  appList:         { backgroundColor: COLORS.card, borderRadius: 14, borderWidth: 1, borderColor: COLORS.cardBorder, overflow: 'hidden' },
  topAppRow:       { flexDirection: 'row', alignItems: 'center', padding: 12, borderBottomWidth: 1, borderColor: COLORS.cardBorder },
  topAppRank:      { color: COLORS.purple, fontSize: 14, fontWeight: '800', width: 30 },
  topAppInfo:      { flex: 1, marginRight: 8 },
  topAppName:      { color: COLORS.textPrimary, fontSize: 13, fontWeight: '600', marginBottom: 6 },
  topAppBar:       { height: 6, backgroundColor: COLORS.bg, borderRadius: 3, overflow: 'hidden' },
  topAppFill:      { height: '100%', backgroundColor: COLORS.purple, borderRadius: 3 },
  topAppTime:      { color: COLORS.textSec, fontSize: 13, fontWeight: '700', minWidth: 50, textAlign: 'right' },
  anomalyCard:     { flexDirection: 'row', alignItems: 'center', backgroundColor: COLORS.orange + '10', borderRadius: 10, padding: 12, marginBottom: 6, borderWidth: 1, borderColor: COLORS.orange + '30' },
  anomalyIcon:     { fontSize: 18, marginRight: 10 },
  anomalyText:     { color: COLORS.orange, fontSize: 13, flex: 1 },
  recCard:         { flexDirection: 'row', alignItems: 'center', backgroundColor: COLORS.blue + '10', borderRadius: 10, padding: 12, marginBottom: 6, borderWidth: 1, borderColor: COLORS.blue + '30' },
  recIcon:         { fontSize: 18, marginRight: 10 },
  recText:         { color: COLORS.blue, fontSize: 13, flex: 1 },
});
