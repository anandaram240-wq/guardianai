-- ============================================================
-- GuardianAI — Realtime + Edge Function Support
-- Run after 001 and 002 migrations
-- ============================================================

-- Enable Supabase Realtime on key tables
-- (Go to Supabase Dashboard → Database → Replication to enable these)

-- These are the tables that need real-time updates in parent app:
-- 1. location_history  → live map tracking
-- 2. alerts           → instant alert notifications  
-- 3. device_commands  → command execution status
-- 4. children         → online/offline/battery status

-- ─────────────────────────────────────────────────────────
-- ADDITIONAL INDEXES for performance
-- ─────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_alerts_child_unresolved
  ON alerts(child_id, created_at DESC)
  WHERE resolved = FALSE;

CREATE INDEX IF NOT EXISTS idx_location_history_recent
  ON location_history(child_id, recorded_at DESC)
  WHERE recorded_at > NOW() - INTERVAL '7 days';

CREATE INDEX IF NOT EXISTS idx_commands_pending
  ON device_commands(child_id, issued_at DESC)
  WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_app_usage_today
  ON app_usage_logs(child_id, usage_date)
  WHERE usage_date = CURRENT_DATE;

-- ─────────────────────────────────────────────────────────
-- FUNCTION: get_child_daily_summary
-- Returns a complete summary for a child for a given date
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION get_child_daily_summary(
  p_child_id UUID,
  p_date     DATE DEFAULT CURRENT_DATE
) RETURNS JSONB AS $$
DECLARE
  result JSONB;
BEGIN
  SELECT jsonb_build_object(
    'date',            p_date,
    'screen_time_sec', COALESCE((
      SELECT SUM(duration_seconds) FROM app_usage_logs
      WHERE child_id = p_child_id AND usage_date = p_date
    ), 0),
    'top_apps', COALESCE((
      SELECT jsonb_agg(jsonb_build_object(
        'app_name',    app_name,
        'app_package', app_package,
        'duration',    duration_seconds,
        'opens',       open_count
      ) ORDER BY duration_seconds DESC)
      FROM app_usage_logs
      WHERE child_id = p_child_id AND usage_date = p_date
      LIMIT 10
    ), '[]'::jsonb),
    'blocked_count', COALESCE((
      SELECT COUNT(*) FROM blocked_events
      WHERE child_id = p_child_id
        AND blocked_at >= p_date::TIMESTAMPTZ
        AND blocked_at < (p_date + 1)::TIMESTAMPTZ
    ), 0),
    'blocked_by_type', COALESCE((
      SELECT jsonb_object_agg(block_type, cnt)
      FROM (
        SELECT block_type, COUNT(*) AS cnt
        FROM blocked_events
        WHERE child_id = p_child_id
          AND blocked_at >= p_date::TIMESTAMPTZ
          AND blocked_at < (p_date + 1)::TIMESTAMPTZ
        GROUP BY block_type
      ) t
    ), '{}'::jsonb),
    'alert_count', COALESCE((
      SELECT COUNT(*) FROM alerts
      WHERE child_id = p_child_id
        AND created_at >= p_date::TIMESTAMPTZ
        AND created_at < (p_date + 1)::TIMESTAMPTZ
    ), 0),
    'critical_alerts', COALESCE((
      SELECT COUNT(*) FROM alerts
      WHERE child_id = p_child_id
        AND severity = 'critical'
        AND created_at >= p_date::TIMESTAMPTZ
        AND created_at < (p_date + 1)::TIMESTAMPTZ
    ), 0),
    'location_count', COALESCE((
      SELECT COUNT(*) FROM location_history
      WHERE child_id = p_child_id
        AND recorded_at >= p_date::TIMESTAMPTZ
        AND recorded_at < (p_date + 1)::TIMESTAMPTZ
    ), 0),
    'risk_score', COALESCE((
      SELECT calculate_risk_score(p_child_id)
    ), 0)
  ) INTO result;

  RETURN result;
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────
-- FUNCTION: get_location_route
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION get_location_route(
  p_child_id   UUID,
  p_start_time TIMESTAMPTZ,
  p_end_time   TIMESTAMPTZ
) RETURNS TABLE (
  latitude    DOUBLE PRECISION,
  longitude   DOUBLE PRECISION,
  recorded_at TIMESTAMPTZ,
  speed       REAL,
  address     TEXT
) AS $$
BEGIN
  RETURN QUERY
  SELECT lh.latitude, lh.longitude, lh.recorded_at, lh.speed, lh.address
  FROM location_history lh
  WHERE lh.child_id = p_child_id
    AND lh.recorded_at BETWEEN p_start_time AND p_end_time
  ORDER BY lh.recorded_at ASC;
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────
-- FUNCTION: get_family_overview
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION get_family_overview(p_family_id UUID)
RETURNS TABLE (
  child_id     UUID,
  child_name   TEXT,
  is_online    BOOLEAN,
  battery      INTEGER,
  risk_score   INTEGER,
  last_lat     DOUBLE PRECISION,
  last_lng     DOUBLE PRECISION,
  last_seen    TIMESTAMPTZ,
  alert_count  BIGINT
) AS $$
BEGIN
  RETURN QUERY
  SELECT
    c.id,
    c.name,
    c.is_online,
    c.battery_level,
    c.risk_score,
    lh.latitude,
    lh.longitude,
    c.last_seen,
    (SELECT COUNT(*) FROM alerts a WHERE a.child_id = c.id AND a.resolved = FALSE) AS alert_count
  FROM children c
  LEFT JOIN LATERAL (
    SELECT latitude, longitude FROM location_history
    WHERE child_id = c.id ORDER BY recorded_at DESC LIMIT 1
  ) lh ON true
  WHERE c.family_id = p_family_id;
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────
-- FUNCTION: mark_alert_resolved
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION mark_alert_resolved(p_alert_id UUID)
RETURNS VOID AS $$
BEGIN
  UPDATE alerts SET resolved = TRUE, resolved_at = NOW()
  WHERE id = p_alert_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ─────────────────────────────────────────────────────────
-- FUNCTION: Auto-update child risk score on new alert
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION on_new_alert_update_risk_score()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE children
  SET risk_score = calculate_risk_score(NEW.child_id)
  WHERE id = NEW.child_id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_alert_update_risk_score
  AFTER INSERT ON alerts
  FOR EACH ROW EXECUTE FUNCTION on_new_alert_update_risk_score();

-- ─────────────────────────────────────────────────────────
-- FUNCTION: Auto-cleanup old location history (keep 30 days)
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION cleanup_old_locations()
RETURNS void AS $$
BEGIN
  DELETE FROM location_history
  WHERE recorded_at < NOW() - INTERVAL '30 days';
END;
$$ LANGUAGE plpgsql;

-- Auto-cleanup blocked events older than 90 days
CREATE OR REPLACE FUNCTION cleanup_old_blocked_events()
RETURNS void AS $$
BEGIN
  DELETE FROM blocked_events WHERE blocked_at < NOW() - INTERVAL '90 days';
  DELETE FROM call_logs      WHERE called_at  < NOW() - INTERVAL '90 days';
  DELETE FROM sms_logs       WHERE sent_at    < NOW() - INTERVAL '90 days';
END;
$$ LANGUAGE plpgsql;

-- ─────────────────────────────────────────────────────────
-- WEEKLY BEHAVIOR REPORT FUNCTION
-- Called by Edge Function scheduled cron
-- ─────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION generate_weekly_report(p_child_id UUID)
RETURNS UUID AS $$
DECLARE
  report_id UUID;
  week_start DATE := CURRENT_DATE - 7;
  total_time INTEGER;
  blocked_cnt INTEGER;
  risk INTEGER;
  top_apps_json JSONB;
  anomalies_json JSONB := '[]'::jsonb;
  recommendations TEXT[] := '{}';
BEGIN
  -- Total screen time last 7 days
  SELECT COALESCE(SUM(duration_seconds), 0) INTO total_time
  FROM app_usage_logs
  WHERE child_id = p_child_id AND usage_date >= week_start;

  -- Total blocks last 7 days
  SELECT COALESCE(COUNT(*), 0) INTO blocked_cnt
  FROM blocked_events
  WHERE child_id = p_child_id AND blocked_at >= week_start::TIMESTAMPTZ;

  -- Current risk score
  SELECT calculate_risk_score(p_child_id) INTO risk;

  -- Top 5 apps
  SELECT COALESCE(jsonb_agg(a ORDER BY a->>'total_time' DESC), '[]'::jsonb) INTO top_apps_json
  FROM (
    SELECT jsonb_build_object(
      'app_name', app_name,
      'app_package', app_package,
      'total_time', SUM(duration_seconds)
    ) AS a
    FROM app_usage_logs
    WHERE child_id = p_child_id AND usage_date >= week_start
    GROUP BY app_name, app_package
    LIMIT 5
  ) sub;

  -- Anomaly: high screen time (> 8 hours/day average)
  IF total_time > (8 * 3600 * 7) THEN
    anomalies_json := anomalies_json || '["High screen time detected"]'::jsonb;
    recommendations := recommendations || 'Consider reducing daily screen time limits';
  END IF;

  -- Anomaly: many blocks detected
  IF blocked_cnt > 50 THEN
    anomalies_json := anomalies_json || '["High number of blocked content attempts"]'::jsonb;
    recommendations := recommendations || 'Review browsing habits with your child';
  END IF;

  -- Upsert behavior report
  INSERT INTO behavior_reports (
    child_id, report_date, risk_score, total_screen_time,
    top_apps, blocked_count, anomalies, recommendations
  ) VALUES (
    p_child_id, CURRENT_DATE, risk, total_time,
    top_apps_json, blocked_cnt, anomalies_json, recommendations
  )
  ON CONFLICT (child_id, report_date) DO UPDATE SET
    risk_score       = EXCLUDED.risk_score,
    total_screen_time = EXCLUDED.total_screen_time,
    top_apps         = EXCLUDED.top_apps,
    blocked_count    = EXCLUDED.blocked_count,
    anomalies        = EXCLUDED.anomalies,
    recommendations  = EXCLUDED.recommendations,
    generated_at     = NOW()
  RETURNING id INTO report_id;

  RETURN report_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
