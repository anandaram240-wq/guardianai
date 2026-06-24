'use strict';

const express = require('express');
const { createClient } = require('@supabase/supabase-js');
const { validateParentToken, validateChildToken } = require('../middleware/auth');

const router = express.Router();
const logger = global.logger || console;

function getSupabase() {
  return createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });
}

// ─── GET /api/child/list ──────────────────────────────────────────────────────
// Parent: list all child devices in their family
router.get('/list', validateParentToken, async (req, res) => {
  try {
    const { familyId } = req.family;
    const supabase = getSupabase();

    const { data: children, error } = await supabase
      .from('children')
      .select('id, device_name, device_model, android_version, is_active, last_seen, created_at')
      .eq('family_id', familyId)
      .order('created_at', { ascending: true });

    if (error) {
      logger.error('Failed to fetch children', { error: error.message, familyId });
      return res.status(500).json({ success: false, error: 'Failed to fetch devices.' });
    }

    return res.json({ success: true, children: children || [] });
  } catch (err) {
    logger.error('Child list error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── GET /api/child/:childId ──────────────────────────────────────────────────
// Parent: get details for a specific child device
router.get('/:childId', validateParentToken, async (req, res) => {
  try {
    const { familyId } = req.family;
    const { childId } = req.params;
    const supabase = getSupabase();

    const { data: child, error } = await supabase
      .from('children')
      .select('id, device_name, device_model, android_version, is_active, last_seen, created_at')
      .eq('id', childId)
      .eq('family_id', familyId)
      .single();

    if (error || !child) {
      return res.status(404).json({ success: false, error: 'Device not found.' });
    }

    return res.json({ success: true, child });
  } catch (err) {
    logger.error('Child detail error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── POST /api/child/heartbeat ────────────────────────────────────────────────
// Child device: update last_seen timestamp and battery/location info
router.post('/heartbeat', validateChildToken, async (req, res) => {
  try {
    const { childId, familyId } = req.child;
    const { batteryLevel, latitude, longitude, locationAccuracy } = req.body;
    const supabase = getSupabase();

    const updatePayload = {
      last_seen: new Date().toISOString(),
      is_active: true,
    };

    if (batteryLevel !== undefined) updatePayload.battery_level = batteryLevel;
    if (latitude !== undefined && longitude !== undefined) {
      updatePayload.last_latitude = latitude;
      updatePayload.last_longitude = longitude;
      updatePayload.location_accuracy = locationAccuracy || null;
    }

    const { error } = await supabase
      .from('children')
      .update(updatePayload)
      .eq('id', childId)
      .eq('family_id', familyId);

    if (error) {
      logger.error('Heartbeat update failed', { error: error.message, childId });
      return res.status(500).json({ success: false, error: 'Heartbeat update failed.' });
    }

    return res.json({ success: true, message: 'Heartbeat received.' });
  } catch (err) {
    logger.error('Heartbeat error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── DELETE /api/child/:childId ───────────────────────────────────────────────
// Parent: deactivate / remove a child device
router.delete('/:childId', validateParentToken, async (req, res) => {
  try {
    const { familyId } = req.family;
    const { childId } = req.params;
    const supabase = getSupabase();

    const { error } = await supabase
      .from('children')
      .update({ is_active: false })
      .eq('id', childId)
      .eq('family_id', familyId);

    if (error) {
      logger.error('Failed to deactivate child', { error: error.message, childId });
      return res.status(500).json({ success: false, error: 'Failed to deactivate device.' });
    }

    logger.info('Child device deactivated', { childId, familyId });
    return res.json({ success: true, message: 'Device deactivated.' });
  } catch (err) {
    logger.error('Delete child error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
