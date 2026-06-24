-- ============================================================
-- GuardianAI Parental Control App - Row Level Security
-- Migration: 002_row_level_security.sql
-- ============================================================
-- All policies use auth.uid() from Supabase Auth.
-- Pattern: <table>_<action>_<who>
--
-- Permission model:
--   Parent user  → auth.users entry whose id = families.user_id
--   Child device → auth.users entry whose id = children.device_user_id
--     (device_user_id is a service-role-provisioned user for each device)
-- ============================================================

-- ============================================================
-- HELPER: get the family_id for the currently authenticated user
-- (used in multiple policies to avoid repeated subqueries)
-- ============================================================

CREATE OR REPLACE FUNCTION auth_family_id()
RETURNS UUID
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id FROM public.families WHERE user_id = auth.uid() LIMIT 1;
$$;

-- ============================================================
-- families
-- ============================================================

ALTER TABLE families ENABLE ROW LEVEL SECURITY;

-- Parents: see only their own family row
CREATE POLICY families_select_owner
    ON families FOR SELECT
    USING (user_id = auth.uid());

-- Parents: update their own family row
CREATE POLICY families_update_owner
    ON families FOR UPDATE
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

-- Parents: insert their own family row (one per user)
CREATE POLICY families_insert_owner
    ON families FOR INSERT
    WITH CHECK (user_id = auth.uid());

-- Parents: delete their own family
CREATE POLICY families_delete_owner
    ON families FOR DELETE
    USING (user_id = auth.uid());

-- ============================================================
-- children
-- ============================================================

ALTER TABLE children ENABLE ROW LEVEL SECURITY;

-- Parents: see only their own children
CREATE POLICY children_select_parent
    ON children FOR SELECT
    USING (family_id = auth_family_id());

-- Parents: add children to their family
CREATE POLICY children_insert_parent
    ON children FOR INSERT
    WITH CHECK (family_id = auth_family_id());

-- Parents: update their children's profiles
CREATE POLICY children_update_parent
    ON children FOR UPDATE
    USING (family_id = auth_family_id())
    WITH CHECK (family_id = auth_family_id());

-- Parents: delete (deregister) children
CREATE POLICY children_delete_parent
    ON children FOR DELETE
    USING (family_id = auth_family_id());

-- ============================================================
-- HELPER: check if auth.uid() owns a given child_id
-- ============================================================

CREATE OR REPLACE FUNCTION auth_owns_child(p_child_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.children c
        JOIN public.families f ON f.id = c.family_id
        WHERE c.id = p_child_id
          AND f.user_id = auth.uid()
    );
$$;

-- ============================================================
-- location_history
-- ============================================================

ALTER TABLE location_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY location_history_select_parent
    ON location_history FOR SELECT
    USING (auth_owns_child(child_id));

-- Device inserts its own location (authenticated via service role or device token)
CREATE POLICY location_history_insert_parent
    ON location_history FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY location_history_delete_parent
    ON location_history FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- geofences
-- ============================================================

ALTER TABLE geofences ENABLE ROW LEVEL SECURITY;

CREATE POLICY geofences_select_parent
    ON geofences FOR SELECT
    USING (family_id = auth_family_id());

CREATE POLICY geofences_insert_parent
    ON geofences FOR INSERT
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY geofences_update_parent
    ON geofences FOR UPDATE
    USING (family_id = auth_family_id())
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY geofences_delete_parent
    ON geofences FOR DELETE
    USING (family_id = auth_family_id());

-- ============================================================
-- geofence_events
-- ============================================================

ALTER TABLE geofence_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY geofence_events_select_parent
    ON geofence_events FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY geofence_events_insert_parent
    ON geofence_events FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY geofence_events_delete_parent
    ON geofence_events FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- alerts
-- ============================================================

ALTER TABLE alerts ENABLE ROW LEVEL SECURITY;

CREATE POLICY alerts_select_parent
    ON alerts FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY alerts_insert_parent
    ON alerts FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY alerts_update_parent
    ON alerts FOR UPDATE
    USING (auth_owns_child(child_id))
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY alerts_delete_parent
    ON alerts FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- app_rules
-- ============================================================

ALTER TABLE app_rules ENABLE ROW LEVEL SECURITY;

CREATE POLICY app_rules_select_parent
    ON app_rules FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY app_rules_insert_parent
    ON app_rules FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY app_rules_update_parent
    ON app_rules FOR UPDATE
    USING (auth_owns_child(child_id))
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY app_rules_delete_parent
    ON app_rules FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- app_usage_logs
-- ============================================================

ALTER TABLE app_usage_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY app_usage_logs_select_parent
    ON app_usage_logs FOR SELECT
    USING (auth_owns_child(child_id));

-- Device SDK can insert usage records
CREATE POLICY app_usage_logs_insert_parent
    ON app_usage_logs FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY app_usage_logs_delete_parent
    ON app_usage_logs FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- blocked_events
-- ============================================================

ALTER TABLE blocked_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY blocked_events_select_parent
    ON blocked_events FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY blocked_events_insert_parent
    ON blocked_events FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY blocked_events_delete_parent
    ON blocked_events FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- call_logs
-- ============================================================

ALTER TABLE call_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY call_logs_select_parent
    ON call_logs FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY call_logs_insert_parent
    ON call_logs FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY call_logs_delete_parent
    ON call_logs FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- sms_logs
-- ============================================================

ALTER TABLE sms_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY sms_logs_select_parent
    ON sms_logs FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY sms_logs_insert_parent
    ON sms_logs FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY sms_logs_delete_parent
    ON sms_logs FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- social_scan_results
-- ============================================================

ALTER TABLE social_scan_results ENABLE ROW LEVEL SECURITY;

CREATE POLICY social_scan_results_select_parent
    ON social_scan_results FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY social_scan_results_insert_parent
    ON social_scan_results FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY social_scan_results_delete_parent
    ON social_scan_results FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- device_commands
-- ============================================================

ALTER TABLE device_commands ENABLE ROW LEVEL SECURITY;

-- Parents: can see commands they issued for their children
CREATE POLICY device_commands_select_parent
    ON device_commands FOR SELECT
    USING (auth_owns_child(child_id));

-- Parents: can issue new commands
CREATE POLICY device_commands_insert_parent
    ON device_commands FOR INSERT
    WITH CHECK (
        auth_owns_child(child_id)
        AND issued_by = auth.uid()
    );

-- Parents: can cancel/update commands
CREATE POLICY device_commands_update_parent
    ON device_commands FOR UPDATE
    USING (auth_owns_child(child_id))
    WITH CHECK (auth_owns_child(child_id));

-- Parents: can delete old commands
CREATE POLICY device_commands_delete_parent
    ON device_commands FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- behavior_reports
-- ============================================================

ALTER TABLE behavior_reports ENABLE ROW LEVEL SECURITY;

CREATE POLICY behavior_reports_select_parent
    ON behavior_reports FOR SELECT
    USING (auth_owns_child(child_id));

CREATE POLICY behavior_reports_insert_parent
    ON behavior_reports FOR INSERT
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY behavior_reports_update_parent
    ON behavior_reports FOR UPDATE
    USING (auth_owns_child(child_id))
    WITH CHECK (auth_owns_child(child_id));

CREATE POLICY behavior_reports_delete_parent
    ON behavior_reports FOR DELETE
    USING (auth_owns_child(child_id));

-- ============================================================
-- emergency_contacts
-- ============================================================

ALTER TABLE emergency_contacts ENABLE ROW LEVEL SECURITY;

CREATE POLICY emergency_contacts_select_parent
    ON emergency_contacts FOR SELECT
    USING (family_id = auth_family_id());

CREATE POLICY emergency_contacts_insert_parent
    ON emergency_contacts FOR INSERT
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY emergency_contacts_update_parent
    ON emergency_contacts FOR UPDATE
    USING (family_id = auth_family_id())
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY emergency_contacts_delete_parent
    ON emergency_contacts FOR DELETE
    USING (family_id = auth_family_id());

-- ============================================================
-- trusted_wifi_networks
-- ============================================================

ALTER TABLE trusted_wifi_networks ENABLE ROW LEVEL SECURITY;

CREATE POLICY trusted_wifi_networks_select_parent
    ON trusted_wifi_networks FOR SELECT
    USING (family_id = auth_family_id());

CREATE POLICY trusted_wifi_networks_insert_parent
    ON trusted_wifi_networks FOR INSERT
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY trusted_wifi_networks_update_parent
    ON trusted_wifi_networks FOR UPDATE
    USING (family_id = auth_family_id())
    WITH CHECK (family_id = auth_family_id());

CREATE POLICY trusted_wifi_networks_delete_parent
    ON trusted_wifi_networks FOR DELETE
    USING (family_id = auth_family_id());
