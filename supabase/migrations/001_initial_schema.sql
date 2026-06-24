-- ============================================================
-- GuardianAI — Supabase Database Schema
-- 100% Free (Supabase free tier: 500MB DB, 1GB storage)
-- Run in: Supabase Dashboard → SQL Editor → New Query
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- FAMILIES
CREATE TABLE IF NOT EXISTS families (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_name   TEXT NOT NULL,
  parent_email  TEXT UNIQUE NOT NULL,
  parent_phone  TEXT,
  pin_hash      TEXT NOT NULL,
  ntfy_topic    TEXT UNIQUE DEFAULT encode(gen_random_bytes(16), 'hex'),
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW()
);

-- CHILDREN
CREATE TABLE IF NOT EXISTS children (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  family_id       UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  name            TEXT NOT NULL,
  age             INTEGER CHECK (age BETWEEN 1 AND 18),
  device_id       TEXT UNIQUE,
  device_model    TEXT,
  android_version TEXT,
  risk_score      INTEGER DEFAULT 0 CHECK (risk_score BETWEEN 0 AND 100),
  is_online       BOOLEAN DEFAULT FALSE,
  battery_level   INTEGER CHECK (battery_level BETWEEN 0 AND 100),
  last_seen       TIMESTAMPTZ,
  is_device_owner BOOLEAN DEFAULT FALSE,
  dns_server      TEXT,
  metadata        JSONB DEFAULT '{}',
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_children_family ON children(family_id);

-- LOCATION HISTORY
CREATE TABLE IF NOT EXISTS location_history (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id     UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  latitude     DOUBLE PRECISION NOT NULL,
  longitude    DOUBLE PRECISION NOT NULL,
  accuracy     REAL,
  speed        REAL,
  heading      REAL,
  altitude     REAL,
  provider     TEXT,
  address      TEXT,
  recorded_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_location_child      ON location_history(child_id);
CREATE INDEX IF NOT EXISTS idx_location_time       ON location_history(recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_location_child_time ON location_history(child_id, recorded_at DESC);

-- GEOFENCES
CREATE TABLE IF NOT EXISTS geofences (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  family_id      UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  latitude       DOUBLE PRECISION NOT NULL,
  longitude      DOUBLE PRECISION NOT NULL,
  radius_meters  INTEGER DEFAULT 200 CHECK (radius_meters BETWEEN 50 AND 5000),
  zone_type      TEXT DEFAULT 'safe' CHECK (zone_type IN ('safe', 'restricted')),
  color          TEXT DEFAULT '#4CAF50',
  is_active      BOOLEAN DEFAULT TRUE,
  created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_geofences_family ON geofences(family_id);

-- GEOFENCE EVENTS
CREATE TABLE IF NOT EXISTS geofence_events (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id     UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  geofence_id  UUID NOT NULL REFERENCES geofences(id) ON DELETE CASCADE,
  event_type   TEXT NOT NULL CHECK (event_type IN ('enter', 'exit', 'dwell')),
  location_lat DOUBLE PRECISION,
  location_lng DOUBLE PRECISION,
  triggered_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_geofence_events_child ON geofence_events(child_id);

-- ALERTS
CREATE TABLE IF NOT EXISTS alerts (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id       UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  type           TEXT NOT NULL CHECK (type IN (
                   'adult_content','geofence_breach','dangerous_keyword',
                   'unknown_contact','sos','late_night','new_app',
                   'cyberbullying','self_harm','grooming','device_tampering')),
  severity       TEXT NOT NULL CHECK (severity IN ('critical','warning','info')),
  title          TEXT NOT NULL,
  body           TEXT,
  screenshot_url TEXT,
  audio_url      TEXT,
  metadata       JSONB DEFAULT '{}',
  resolved       BOOLEAN DEFAULT FALSE,
  resolved_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_alerts_child    ON alerts(child_id);
CREATE INDEX IF NOT EXISTS idx_alerts_severity ON alerts(severity);
CREATE INDEX IF NOT EXISTS idx_alerts_time     ON alerts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_resolved ON alerts(resolved);

-- APP RULES
CREATE TABLE IF NOT EXISTS app_rules (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id             UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  app_package          TEXT NOT NULL,
  app_name             TEXT NOT NULL,
  app_category         TEXT,
  is_blocked           BOOLEAN DEFAULT FALSE,
  daily_limit_minutes  INTEGER,
  allowed_start_time   TIME,
  allowed_end_time     TIME,
  is_system_app        BOOLEAN DEFAULT FALSE,
  created_at           TIMESTAMPTZ DEFAULT NOW(),
  updated_at           TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(child_id, app_package)
);
CREATE INDEX IF NOT EXISTS idx_app_rules_child ON app_rules(child_id);

-- APP USAGE LOGS
CREATE TABLE IF NOT EXISTS app_usage_logs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id          UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  app_package       TEXT NOT NULL,
  app_name          TEXT NOT NULL,
  usage_date        DATE NOT NULL DEFAULT CURRENT_DATE,
  duration_seconds  INTEGER DEFAULT 0,
  open_count        INTEGER DEFAULT 0,
  last_used         TIMESTAMPTZ,
  UNIQUE(child_id, app_package, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_app_usage_child_date ON app_usage_logs(child_id, usage_date DESC);

-- BLOCKED EVENTS
CREATE TABLE IF NOT EXISTS blocked_events (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id     UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  block_type   TEXT NOT NULL CHECK (block_type IN ('dns','app','image','popup','keyword','time_limit')),
  content      TEXT,
  details      TEXT,
  blocked_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_blocked_child ON blocked_events(child_id);
CREATE INDEX IF NOT EXISTS idx_blocked_time  ON blocked_events(blocked_at DESC);

-- CALL LOGS
CREATE TABLE IF NOT EXISTS call_logs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id         UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  phone_number     TEXT NOT NULL,
  contact_name     TEXT,
  call_type        TEXT NOT NULL CHECK (call_type IN ('incoming','outgoing','missed')),
  duration_seconds INTEGER DEFAULT 0,
  is_flagged       BOOLEAN DEFAULT FALSE,
  called_at        TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_calls_child ON call_logs(child_id);

-- SMS LOGS
CREATE TABLE IF NOT EXISTS sms_logs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id          UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  phone_number      TEXT NOT NULL,
  contact_name      TEXT,
  message_preview   TEXT,
  direction         TEXT NOT NULL CHECK (direction IN ('incoming','outgoing')),
  ai_risk_score     REAL DEFAULT 0.0,
  flagged_keywords  TEXT[],
  is_flagged        BOOLEAN DEFAULT FALSE,
  sent_at           TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sms_child ON sms_logs(child_id);

-- SOCIAL SCAN RESULTS
CREATE TABLE IF NOT EXISTS social_scan_results (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id        UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  platform        TEXT NOT NULL CHECK (platform IN ('whatsapp','instagram','tiktok','telegram','snapchat','youtube','other')),
  content_preview TEXT,
  ai_risk_score   REAL DEFAULT 0.0,
  risk_category   TEXT,
  screenshot_url  TEXT,
  scanned_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_social_child ON social_scan_results(child_id);

-- DEVICE COMMANDS
CREATE TABLE IF NOT EXISTS device_commands (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id     UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  command      TEXT NOT NULL CHECK (command IN (
                 'lock_device','unlock_device','take_photo_front','take_photo_back',
                 'start_audio','stop_audio','start_camera','stop_camera',
                 'emergency_alert','refresh_blocklist','refresh_rules','wipe_device')),
  status       TEXT DEFAULT 'pending' CHECK (status IN ('pending','executing','completed','failed')),
  issued_at    TIMESTAMPTZ DEFAULT NOW(),
  executed_at  TIMESTAMPTZ,
  result       JSONB DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_commands_child  ON device_commands(child_id);
CREATE INDEX IF NOT EXISTS idx_commands_status ON device_commands(status);

-- BEHAVIOR REPORTS
CREATE TABLE IF NOT EXISTS behavior_reports (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  child_id           UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  report_date        DATE NOT NULL DEFAULT CURRENT_DATE,
  risk_score         INTEGER DEFAULT 0,
  total_screen_time  INTEGER DEFAULT 0,
  top_apps           JSONB DEFAULT '[]',
  blocked_count      INTEGER DEFAULT 0,
  location_summary   JSONB DEFAULT '{}',
  anomalies          JSONB DEFAULT '[]',
  recommendations    TEXT[],
  generated_at       TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(child_id, report_date)
);

-- EMERGENCY CONTACTS
CREATE TABLE IF NOT EXISTS emergency_contacts (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  family_id    UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  phone        TEXT NOT NULL,
  relationship TEXT,
  is_active    BOOLEAN DEFAULT TRUE,
  added_at     TIMESTAMPTZ DEFAULT NOW()
);

-- TRUSTED WIFI
CREATE TABLE IF NOT EXISTS trusted_wifi_networks (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  family_id  UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  ssid       TEXT NOT NULL,
  bssid      TEXT,
  is_trusted BOOLEAN DEFAULT TRUE,
  added_at   TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(family_id, ssid)
);

-- UPDATED_AT TRIGGERS
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_families_updated  BEFORE UPDATE ON families  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_children_updated  BEFORE UPDATE ON children  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_app_rules_updated BEFORE UPDATE ON app_rules FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- HAVERSINE DISTANCE FUNCTION
CREATE OR REPLACE FUNCTION haversine_meters(
  lat1 DOUBLE PRECISION, lng1 DOUBLE PRECISION,
  lat2 DOUBLE PRECISION, lng2 DOUBLE PRECISION
) RETURNS DOUBLE PRECISION AS $$
DECLARE r DOUBLE PRECISION := 6371000; dlat DOUBLE PRECISION; dlng DOUBLE PRECISION; a DOUBLE PRECISION;
BEGIN
  dlat := RADIANS(lat2 - lat1); dlng := RADIANS(lng2 - lng1);
  a := SIN(dlat/2)^2 + COS(RADIANS(lat1)) * COS(RADIANS(lat2)) * SIN(dlng/2)^2;
  RETURN r * 2 * ASIN(SQRT(a));
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- CALCULATE RISK SCORE
CREATE OR REPLACE FUNCTION calculate_risk_score(p_child_id UUID)
RETURNS INTEGER AS $$
DECLARE
  base_score     INTEGER := 0;
  critical_count INTEGER;
  warning_count  INTEGER;
  sos_count      INTEGER;
BEGIN
  SELECT COUNT(*) INTO critical_count FROM alerts
  WHERE child_id = p_child_id AND severity = 'critical' AND created_at > NOW() - INTERVAL '7 days';
  base_score := base_score + LEAST(critical_count * 15, 60);

  SELECT COUNT(*) INTO warning_count FROM alerts
  WHERE child_id = p_child_id AND severity = 'warning' AND created_at > NOW() - INTERVAL '7 days';
  base_score := base_score + LEAST(warning_count * 5, 25);

  SELECT COUNT(*) INTO sos_count FROM alerts
  WHERE child_id = p_child_id AND type = 'sos' AND created_at > NOW() - INTERVAL '30 days';
  base_score := base_score + LEAST(sos_count * 25, 50);

  RETURN LEAST(base_score, 100);
END;
$$ LANGUAGE plpgsql;

-- CHILD CURRENT STATUS VIEW
CREATE OR REPLACE VIEW child_current_status AS
SELECT
  c.id, c.family_id, c.name, c.age, c.risk_score,
  c.is_online, c.battery_level, c.last_seen, c.is_device_owner,
  lh.latitude AS last_lat, lh.longitude AS last_lng,
  lh.accuracy AS last_accuracy, lh.address AS last_address,
  lh.recorded_at AS last_location_time,
  (SELECT COUNT(*) FROM alerts a WHERE a.child_id = c.id AND a.resolved = FALSE
     AND a.created_at > NOW() - INTERVAL '24 hours') AS unread_alerts,
  (SELECT COALESCE(SUM(duration_seconds),0) FROM app_usage_logs u
     WHERE u.child_id = c.id AND u.usage_date = CURRENT_DATE) AS today_screen_time,
  (SELECT COUNT(*) FROM blocked_events b
     WHERE b.child_id = c.id AND b.blocked_at > NOW() - INTERVAL '24 hours') AS today_blocked_count
FROM children c
LEFT JOIN LATERAL (
  SELECT latitude, longitude, accuracy, address, recorded_at
  FROM location_history WHERE child_id = c.id ORDER BY recorded_at DESC LIMIT 1
) lh ON true;
