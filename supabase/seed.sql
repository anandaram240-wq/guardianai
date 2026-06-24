-- ═══════════════════════════════════════════════
-- DEMO SEED DATA — For testing only
-- Remove before production!
-- ═══════════════════════════════════════════════

-- DEMO FAMILY
INSERT INTO families (id, parent_email, parent_name, pin_hash) VALUES (
  '11111111-1111-1111-1111-111111111111',
  'parent@demo.com',
  'Demo Parent',
  '$2a$10$demoHashedPinHere'
);

-- DEMO CHILD
INSERT INTO children (id, family_id, name, device_id, device_model, android_version) VALUES (
  '22222222-2222-2222-2222-222222222222',
  '11111111-1111-1111-1111-111111111111',
  'Demo Child',
  'device_demo_001',
  'Samsung Galaxy A54',
  '14'
);

-- DEMO GEOFENCES
INSERT INTO geofences (family_id, name, latitude, longitude, radius_meters, zone_type, color) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Home',   28.6139, 77.2090, 200, 'safe',       '#10B981'),
  ('11111111-1111-1111-1111-111111111111', 'School', 28.6200, 77.2150, 300, 'safe',       '#3B82F6'),
  ('11111111-1111-1111-1111-111111111111', 'Mall',   28.6300, 77.2200, 500, 'restricted', '#EF4444');

-- DEMO APP RULES
INSERT INTO app_rules (child_id, app_package, app_name, is_blocked, daily_limit_minutes, allowed_start_time, allowed_end_time) VALUES
  ('22222222-2222-2222-2222-222222222222', 'com.google.android.youtube',  'YouTube',   false, 60,  '08:00', '21:00'),
  ('22222222-2222-2222-2222-222222222222', 'com.zhiliaoapp.musically',    'TikTok',    true,  -1,  '00:00', '23:59'),
  ('22222222-2222-2222-2222-222222222222', 'com.instagram.android',       'Instagram', false, 30,  '10:00', '20:00'),
  ('22222222-2222-2222-2222-222222222222', 'com.supercell.clashofclans',  'Clash of Clans', false, 45, '15:00', '21:00');

-- DEMO EMERGENCY CONTACTS
INSERT INTO emergency_contacts (family_id, name, phone, relationship) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Papa',    '+911234567890', 'Father'),
  ('11111111-1111-1111-1111-111111111111', 'Mama',    '+910987654321', 'Mother'),
  ('11111111-1111-1111-1111-111111111111', 'Chacha',  '+911122334455', 'Uncle');

-- DEMO TRUSTED WIFI
INSERT INTO trusted_wifi_networks (family_id, ssid, is_trusted) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Home_WiFi_5G', true),
  ('11111111-1111-1111-1111-111111111111', 'School-Network', true);
