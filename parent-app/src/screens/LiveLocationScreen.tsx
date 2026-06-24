import React, { useState, useEffect, useRef } from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, Dimensions,
  Animated, Alert, Platform, FlatList
} from 'react-native';
import MapView, { Marker, Circle, Polyline, UrlTile, Callout, PROVIDER_DEFAULT } from 'react-native-maps';
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

function haversineMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLng = (lng2 - lng1) * Math.PI / 180;
  const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLng/2)**2;
  return R * 2 * Math.asin(Math.sqrt(a));
}

function timeAgo(dateStr) {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;
  return `${Math.floor(mins/60)}h ago`;
}

// ─── Geofence Badge ──────────────────────────────────────────────────────────
function GeofenceBadge({ zone }) {
  const color = zone.zone_type === 'safe' ? COLORS.green : COLORS.red;
  return (
    <View style={[styles.geofenceBadge, { borderColor: color + '60', backgroundColor: color + '15' }]}>
      <Text style={{ color, fontSize: 11, fontWeight: '700' }}>
        {zone.zone_type === 'safe' ? '✅ ' : '🚫 '}{zone.name}
      </Text>
    </View>
  );
}

// ─── Main LiveLocationScreen ─────────────────────────────────────────────────
export default function LiveLocationScreen({ navigation }) {
  const mapRef = useRef(null);
  const [location, setLocation]       = useState(null);
  const [route, setRoute]             = useState([]);
  const [geofences, setGeofences]     = useState([]);
  const [childInfo, setChildInfo]     = useState(null);
  const [address, setAddress]         = useState('Fetching address...');
  const [showRoute, setShowRoute]     = useState(false);
  const [showAddZone, setShowAddZone] = useState(false);
  const [newZone, setNewZone]         = useState(null);
  const [sheetAnim]                   = useState(new Animated.Value(0));

  // ─── Load data ───────────────────────────────────────────────────────────
  useEffect(() => {
    loadAll();

    // Real-time location updates (free Supabase Realtime)
    const sub = supabase
      .channel('live_location')
      .on('postgres_changes', {
        event: 'INSERT', schema: 'public', table: 'location_history'
      }, (payload) => {
        const loc = payload.new;
        setLocation(loc);
        setRoute((prev) => [...prev.slice(-200), { latitude: loc.latitude, longitude: loc.longitude }]);
        reverseGeocode(loc.latitude, loc.longitude);

        // Animate map to new position
        mapRef.current?.animateToRegion({
          latitude: loc.latitude, longitude: loc.longitude,
          latitudeDelta: 0.005, longitudeDelta: 0.005,
        }, 800);
      })
      .subscribe();

    return () => supabase.removeChannel(sub);
  }, []);

  const loadAll = async () => {
    // Load child info
    const { data: child } = await supabase.from('child_current_status').select('*').single();
    if (child) {
      setChildInfo(child);
      if (child.last_lat && child.last_lng) {
        setLocation({ latitude: child.last_lat, longitude: child.last_lng, accuracy: child.last_accuracy });
        reverseGeocode(child.last_lat, child.last_lng);

        mapRef.current?.animateToRegion({
          latitude: child.last_lat, longitude: child.last_lng,
          latitudeDelta: 0.01, longitudeDelta: 0.01,
        }, 500);
      }
    }

    // Load geofences
    const { data: zones } = await supabase.from('geofences').select('*').eq('is_active', true);
    if (zones) setGeofences(zones);

    // Load today's route
    const today = new Date().toISOString().split('T')[0];
    const { data: locs } = await supabase
      .from('location_history')
      .select('latitude, longitude, recorded_at')
      .gte('recorded_at', today + 'T00:00:00Z')
      .order('recorded_at', { ascending: true })
      .limit(500);
    if (locs) {
      setRoute(locs.map(l => ({ latitude: l.latitude, longitude: l.longitude })));
    }
  };

  // ─── Nominatim Reverse Geocoding (FREE — no API key needed) ─────────────
  const reverseGeocode = async (lat, lng) => {
    try {
      const res = await fetch(
        `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json`,
        { headers: { 'User-Agent': 'GuardianAI/1.0' } }
      );
      const data = await res.json();
      const addr = data.display_name || `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
      // Shorten address to first 2 parts
      const short = addr.split(',').slice(0, 3).join(',');
      setAddress(short);
    } catch {
      setAddress(`${lat.toFixed(5)}, ${lng.toFixed(5)}`);
    }
  };

  // ─── Add Geofence ────────────────────────────────────────────────────────
  const onMapLongPress = ({ nativeEvent }) => {
    const { coordinate } = nativeEvent;
    setNewZone({ latitude: coordinate.latitude, longitude: coordinate.longitude, radius: 200, type: 'safe' });
    setShowAddZone(true);
  };

  const saveGeofence = async () => {
    if (!newZone) return;
    const name = newZone.type === 'safe' ? 'Safe Zone' : 'Restricted Zone';
    await supabase.from('geofences').insert({
      name, latitude: newZone.latitude, longitude: newZone.longitude,
      radius_meters: newZone.radius, zone_type: newZone.type,
      color: newZone.type === 'safe' ? '#10B981' : '#EF4444',
      is_active: true,
    });
    setShowAddZone(false);
    setNewZone(null);
    loadAll();
  };

  // ─── Check child's geofence status ───────────────────────────────────────
  const childInZone = (zone) => {
    if (!location) return false;
    const dist = haversineMeters(location.latitude, location.longitude, zone.latitude, zone.longitude);
    return dist <= zone.radius_meters;
  };

  return (
    <View style={styles.container}>
      {/* ── Dark map with OpenStreetMap + CartoDB Dark tiles ─────────────── */}
      <MapView
        ref={mapRef}
        style={styles.map}
        provider={PROVIDER_DEFAULT}
        mapType="none"       // Use custom tiles
        showsUserLocation={false}
        showsCompass={false}
        showsScale={true}
        onLongPress={onMapLongPress}
        initialRegion={{
          latitude: location?.latitude || 20.5937,
          longitude: location?.longitude || 78.9629,
          latitudeDelta: 0.05, longitudeDelta: 0.05,
        }}
      >
        {/* CartoDB Dark tiles — FREE, no API key */}
        <UrlTile
          urlTemplate="https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"
          maximumZ={19}
          flipY={false}
          tileSize={256}
        />

        {/* Geofence Circles */}
        {geofences.map((zone) => (
          <React.Fragment key={zone.id}>
            <Circle
              center={{ latitude: zone.latitude, longitude: zone.longitude }}
              radius={zone.radius_meters}
              fillColor={zone.zone_type === 'safe' ? '#10B98120' : '#EF444420'}
              strokeColor={zone.zone_type === 'safe' ? '#10B981' : '#EF4444'}
              strokeWidth={2}
            />
            <Marker
              coordinate={{ latitude: zone.latitude, longitude: zone.longitude }}
              anchor={{ x: 0.5, y: 0.5 }}
            >
              <View style={[styles.zoneLabel, {
                backgroundColor: zone.zone_type === 'safe' ? COLORS.green + 'CC' : COLORS.red + 'CC'
              }]}>
                <Text style={styles.zoneLabelText}>{zone.name}</Text>
              </View>
            </Marker>
          </React.Fragment>
        ))}

        {/* New Zone Preview */}
        {newZone && (
          <Circle
            center={{ latitude: newZone.latitude, longitude: newZone.longitude }}
            radius={newZone.radius}
            fillColor={newZone.type === 'safe' ? '#10B98130' : '#EF444430'}
            strokeColor={newZone.type === 'safe' ? COLORS.green : COLORS.red}
            strokeWidth={3}
          />
        )}

        {/* Route Polyline */}
        {showRoute && route.length > 1 && (
          <Polyline
            coordinates={route}
            strokeColor={COLORS.purple}
            strokeWidth={3}
            lineDashPattern={[5, 3]}
          />
        )}

        {/* Child Location Marker */}
        {location && (
          <Marker
            coordinate={{ latitude: location.latitude, longitude: location.longitude }}
            anchor={{ x: 0.5, y: 0.5 }}
          >
            <View style={styles.childMarkerOuter}>
              <View style={styles.childMarkerInner}>
                <Text style={styles.childMarkerIcon}>👶</Text>
              </View>
            </View>
            <Callout style={styles.callout}>
              <Text style={{ color: '#fff', fontWeight: '700' }}>{childInfo?.name || 'Child'}</Text>
              <Text style={{ color: '#94A3B8', fontSize: 11 }}>
                {childInfo?.is_online ? '🟢 Online now' : '🔴 Offline'}
              </Text>
            </Callout>
          </Marker>
        )}
      </MapView>

      {/* ── Accuracy ring (pulsing) ─────────────────────────────────────── */}
      {location?.accuracy && (
        <View style={[styles.accuracyBadge, { right: 20, bottom: 250 }]}>
          <Text style={styles.accuracyText}>±{Math.round(location.accuracy)}m GPS</Text>
        </View>
      )}

      {/* ── Top Controls ───────────────────────────────────────────────── */}
      <View style={styles.topControls}>
        <TouchableOpacity style={styles.controlBtn} onPress={() => navigation.goBack()}>
          <Text style={{ color: COLORS.textPrimary, fontSize: 20 }}>←</Text>
        </TouchableOpacity>
        <Text style={styles.topTitle}>📍 Live Location</Text>
        <TouchableOpacity
          style={[styles.controlBtn, showRoute && styles.controlBtnActive]}
          onPress={() => setShowRoute(!showRoute)}
        >
          <Text style={{ fontSize: 16 }}>🗺️</Text>
        </TouchableOpacity>
      </View>

      {/* ── Geofence Status Bar ─────────────────────────────────────────── */}
      <View style={styles.geofenceBar}>
        <FlatList
          horizontal showsHorizontalScrollIndicator={false}
          data={geofences}
          keyExtractor={(z) => z.id}
          renderItem={({ item }) => (
            <GeofenceBadge zone={item} insideZone={childInZone(item)} />
          )}
          ListEmptyComponent={
            <Text style={{ color: COLORS.textMuted, fontSize: 12 }}>
              Long-press map to add a safety zone
            </Text>
          }
          contentContainerStyle={{ paddingHorizontal: 16, paddingVertical: 8, gap: 8 }}
        />
      </View>

      {/* ── Bottom Info Sheet ────────────────────────────────────────────── */}
      <View style={styles.bottomSheet}>
        <View style={styles.bottomHandle} />
        <View style={styles.locationInfo}>
          <View style={styles.locationRow}>
            <Text style={styles.locationIcon}>📍</Text>
            <View style={{ flex: 1 }}>
              <Text style={styles.locationAddress} numberOfLines={2}>{address}</Text>
              {location && (
                <Text style={styles.locationMeta}>
                  Updated {childInfo?.last_location_time ? timeAgo(childInfo.last_location_time) : 'now'}
                </Text>
              )}
            </View>
          </View>

          <View style={styles.locationStats}>
            {[
              { label: 'Speed', value: location?.speed ? `${(location.speed * 3.6).toFixed(0)} km/h` : '—' },
              { label: 'Battery', value: `${childInfo?.battery_level || '--'}%` },
              { label: 'Status', value: childInfo?.is_online ? '🟢 Live' : '🔴 Last known' },
              { label: 'Accuracy', value: location?.accuracy ? `±${Math.round(location.accuracy)}m` : '—' },
            ].map(({ label, value }) => (
              <View key={label} style={styles.locStat}>
                <Text style={styles.locStatLabel}>{label}</Text>
                <Text style={styles.locStatValue}>{value}</Text>
              </View>
            ))}
          </View>
        </View>
      </View>

      {/* ── Add Zone Dialog ──────────────────────────────────────────────── */}
      {showAddZone && newZone && (
        <View style={styles.zoneDialog}>
          <Text style={styles.zoneDialogTitle}>➕ Add Safety Zone</Text>
          <View style={styles.zoneTypeRow}>
            {['safe', 'restricted'].map((t) => (
              <TouchableOpacity
                key={t}
                style={[styles.zoneTypeBtn, newZone.type === t && {
                  backgroundColor: t === 'safe' ? COLORS.green + '30' : COLORS.red + '30',
                  borderColor: t === 'safe' ? COLORS.green : COLORS.red,
                }]}
                onPress={() => setNewZone({ ...newZone, type: t })}
              >
                <Text style={{ color: COLORS.textPrimary, fontWeight: '700', textTransform: 'capitalize' }}>
                  {t === 'safe' ? '✅ Safe Zone' : '🚫 Restricted'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
          <View style={styles.zoneDialogBtns}>
            <TouchableOpacity style={[styles.zoneBtn, { backgroundColor: COLORS.card }]} onPress={() => setShowAddZone(false)}>
              <Text style={{ color: COLORS.textSec }}>Cancel</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.zoneBtn, { backgroundColor: COLORS.purple }]} onPress={saveGeofence}>
              <Text style={{ color: '#fff', fontWeight: '700' }}>Save Zone</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </View>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────
const { height } = Dimensions.get('window');
const styles = StyleSheet.create({
  container:        { flex: 1, backgroundColor: COLORS.bg },
  map:              { flex: 1 },
  topControls:      { position: 'absolute', top: Platform.OS === 'ios' ? 50 : 30, left: 0, right: 0, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16 },
  controlBtn:       { width: 44, height: 44, borderRadius: 12, backgroundColor: COLORS.card + 'E0', justifyContent: 'center', alignItems: 'center', borderWidth: 1, borderColor: COLORS.cardBorder },
  controlBtnActive: { borderColor: COLORS.purple, backgroundColor: COLORS.purple + '30' },
  topTitle:         { color: COLORS.textPrimary, fontSize: 16, fontWeight: '700', backgroundColor: COLORS.card + 'CC', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 12, borderWidth: 1, borderColor: COLORS.cardBorder },
  geofenceBar:      { position: 'absolute', top: Platform.OS === 'ios' ? 106 : 86, left: 0, right: 0, maxHeight: 50 },
  geofenceBadge:    { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 20, borderWidth: 1, marginHorizontal: 4 },
  accuracyBadge:    { position: 'absolute', backgroundColor: COLORS.card + 'CC', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12, borderWidth: 1, borderColor: COLORS.cardBorder },
  accuracyText:     { color: COLORS.textSec, fontSize: 11 },
  childMarkerOuter: { width: 56, height: 56, borderRadius: 28, backgroundColor: COLORS.purple + '30', justifyContent: 'center', alignItems: 'center', borderWidth: 2, borderColor: COLORS.purple },
  childMarkerInner: { width: 40, height: 40, borderRadius: 20, backgroundColor: COLORS.purple + '60', justifyContent: 'center', alignItems: 'center' },
  childMarkerIcon:  { fontSize: 22 },
  zoneLabel:        { paddingHorizontal: 8, paddingVertical: 4, borderRadius: 6 },
  zoneLabelText:    { color: '#fff', fontSize: 11, fontWeight: '700' },
  callout:          { backgroundColor: COLORS.card, padding: 8, borderRadius: 8, minWidth: 100 },
  bottomSheet:      { position: 'absolute', bottom: 0, left: 0, right: 0, backgroundColor: COLORS.card, borderTopLeftRadius: 24, borderTopRightRadius: 24, paddingBottom: 34, borderTopWidth: 1, borderColor: COLORS.cardBorder },
  bottomHandle:     { width: 40, height: 4, backgroundColor: COLORS.cardBorder, borderRadius: 2, alignSelf: 'center', marginTop: 12, marginBottom: 8 },
  locationInfo:     { paddingHorizontal: 20, paddingTop: 8 },
  locationRow:      { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 16, gap: 12 },
  locationIcon:     { fontSize: 24, marginTop: 2 },
  locationAddress:  { color: COLORS.textPrimary, fontSize: 15, fontWeight: '600', lineHeight: 22 },
  locationMeta:     { color: COLORS.textMuted, fontSize: 12, marginTop: 4 },
  locationStats:    { flexDirection: 'row', gap: 0 },
  locStat:          { flex: 1, alignItems: 'center', paddingVertical: 12, borderRightWidth: 1, borderColor: COLORS.cardBorder },
  locStatLabel:     { color: COLORS.textMuted, fontSize: 11, marginBottom: 4 },
  locStatValue:     { color: COLORS.textPrimary, fontSize: 13, fontWeight: '700' },
  zoneDialog:       { position: 'absolute', bottom: 200, left: 20, right: 20, backgroundColor: COLORS.card, borderRadius: 20, padding: 20, borderWidth: 1, borderColor: COLORS.cardBorder },
  zoneDialogTitle:  { color: COLORS.textPrimary, fontSize: 17, fontWeight: '700', marginBottom: 16, textAlign: 'center' },
  zoneTypeRow:      { flexDirection: 'row', gap: 12, marginBottom: 20 },
  zoneTypeBtn:      { flex: 1, paddingVertical: 12, borderRadius: 12, borderWidth: 1, borderColor: COLORS.cardBorder, alignItems: 'center', backgroundColor: COLORS.bg },
  zoneDialogBtns:   { flexDirection: 'row', gap: 12 },
  zoneBtn:          { flex: 1, paddingVertical: 14, borderRadius: 12, alignItems: 'center' },
});
