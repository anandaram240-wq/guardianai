const express = require('express');
const router  = express.Router();
const fetch   = require('node-fetch');

/**
 * API routes for device commands + ntfy push notifications.
 */

// ─── SEND COMMAND TO CHILD DEVICE ─────────────────────────────────────────────
router.post('/send', async (req, res) => {
  try {
    const { childId, command, params } = req.body;
    const supabase = req.app.get('supabase');

    const validCommands = [
      'lock_device', 'take_photo_front', 'take_photo_back',
      'start_audio', 'stop_audio', 'start_camera', 'stop_camera',
      'emergency_alert', 'refresh_blocklist', 'refresh_rules',
      'wipe_device', 'start_screen_stream', 'stop_screen_stream',
      'grant_bonus_time'
    ];

    if (!validCommands.includes(command)) {
      return res.status(400).json({ error: 'Invalid command' });
    }

    const { data, error } = await supabase
      .from('device_commands')
      .insert({
        child_id: childId,
        command,
        status:  'pending',
        result:  params || {},
      })
      .select()
      .single();

    if (error) return res.status(500).json({ error: error.message });

    // Also emit via Socket.io for instant delivery
    const io = req.app.get('io');
    io.to(`child:${childId}`).emit('command', { commandId: data.id, command, params });

    // Send push via ntfy for guaranteed delivery
    await sendNtfyPush(
      childId,
      `Command: ${command}`,
      `A ${command} command has been sent to the device.`,
      'default'
    );

    res.json({ success: true, commandId: data.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── GET PENDING COMMANDS (child polls this) ──────────────────────────────────
router.get('/pending/:childId', async (req, res) => {
  try {
    const supabase = req.app.get('supabase');
    const { data, error } = await supabase
      .from('device_commands')
      .select('*')
      .eq('child_id', req.params.childId)
      .eq('status', 'pending')
      .order('issued_at', { ascending: true });

    if (error) return res.status(500).json({ error: error.message });
    res.json(data || []);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── MARK COMMAND COMPLETE (child calls this) ─────────────────────────────────
router.put('/:commandId/complete', async (req, res) => {
  try {
    const supabase = req.app.get('supabase');
    const { result, status } = req.body;

    const { error } = await supabase
      .from('device_commands')
      .update({
        status:     status || 'completed',
        executed_at: new Date().toISOString(),
        result:     result || {},
      })
      .eq('id', req.params.commandId);

    if (error) return res.status(500).json({ error: error.message });
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── SOS TRIGGER (child calls this) ──────────────────────────────────────────
router.post('/sos', async (req, res) => {
  try {
    const { childId, trigger, latitude, longitude, photoUrls, audioUrl } = req.body;
    const supabase = req.app.get('supabase');

    // 1. Create CRITICAL alert
    await supabase.from('alerts').insert({
      child_id:  childId,
      type:      'sos',
      severity:  'critical',
      title:     '🆘 SOS EMERGENCY — IMMEDIATE ACTION REQUIRED',
      body:      `Child triggered SOS via ${trigger}. Location: https://maps.google.com/?q=${latitude},${longitude}`,
      metadata:  { trigger, latitude, longitude, photoUrls, audioUrl },
    });

    // 2. Get child info
    const { data: child } = await supabase
      .from('children')
      .select('name, family_id')
      .eq('id', childId)
      .single();

    // 3. Get ALL emergency contacts
    const { data: contacts } = await supabase
      .from('emergency_contacts')
      .select('*')
      .eq('family_id', child?.family_id)
      .eq('is_active', true);

    // 4. Send URGENT push to ALL contacts
    const locationLink = `https://maps.google.com/?q=${latitude},${longitude}`;
    await sendNtfyPush(
      child?.family_id,
      `🆘 ${child?.name || 'Child'} NEEDS HELP!`,
      `Emergency triggered via ${trigger}!\n📍 Location: ${locationLink}\nImmediate action required.`,
      'urgent',
      ['warning', 'rotating_light'],
      [{ action: 'view', label: 'Open Map', url: locationLink }]
    );

    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ─── NTFY PUSH NOTIFICATION SERVICE ──────────────────────────────────────────

async function sendNtfyPush(topic, title, message, priority = 'default', tags = [], actions = []) {
  const NTFY_URL = process.env.NTFY_URL || 'http://localhost:8080';
  const topicName = `guardian_${topic}`.replace(/-/g, '_');

  const priorityMap = { urgent: '5', high: '4', default: '3', low: '2', min: '1' };

  const headers = {
    'Title':    title,
    'Priority': priorityMap[priority] || '3',
    'Tags':     tags.join(',') || 'shield',
  };

  if (actions.length > 0) {
    headers['Actions'] = actions.map(a =>
      `${a.action}, ${a.label}, ${a.url}`
    ).join('; ');
  }

  try {
    await fetch(`${NTFY_URL}/${topicName}`, {
      method:  'POST',
      headers,
      body:    message,
    });
  } catch (err) {
    console.error('ntfy push failed:', err.message);
  }
}

module.exports = router;
module.exports.sendNtfyPush = sendNtfyPush;
