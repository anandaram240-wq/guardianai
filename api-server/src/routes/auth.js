'use strict';

const express = require('express');
const crypto = require('crypto');
const { createClient } = require('@supabase/supabase-js');
const { generateParentToken, generateChildToken, validateParentToken } = require('../middleware/auth');

const router = express.Router();
const logger = global.logger || console;

// Use the shared supabase instance from index (loaded after server starts)
function getSupabase() {
  return createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });
}

// ─── Utility: hash PIN with crypto (SHA-256 + salt) ───────────────────────────

function hashPin(pin, salt) {
  const s = salt || crypto.randomBytes(16).toString('hex');
  const hash = crypto.createHmac('sha256', s).update(String(pin)).digest('hex');
  return { hash, salt: s };
}

function verifyPin(pin, storedHash, salt) {
  const { hash } = hashPin(pin, salt);
  return crypto.timingSafeEqual(Buffer.from(hash), Buffer.from(storedHash));
}

// ─── POST /api/auth/register ──────────────────────────────────────────────────
// Creates a family account (parent user)
router.post('/register', async (req, res) => {
  try {
    const { email, password, pin, parentName, familyName, ntfyTopic } = req.body;

    if (!email || !password || !pin || !parentName || !familyName) {
      return res.status(400).json({
        success: false,
        error: 'email, password, pin, parentName, and familyName are required.',
      });
    }

    if (String(pin).length < 4 || String(pin).length > 8) {
      return res.status(400).json({ success: false, error: 'PIN must be 4–8 digits.' });
    }

    const supabase = getSupabase();

    // Create Supabase Auth user
    const { data: authData, error: authError } = await supabase.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
      user_metadata: { parentName, familyName },
    });

    if (authError) {
      logger.error('Supabase auth user creation failed', { error: authError.message });
      if (authError.message.includes('already registered')) {
        return res.status(409).json({ success: false, error: 'Email already registered.' });
      }
      return res.status(400).json({ success: false, error: authError.message });
    }

    const userId = authData.user.id;
    const { hash: pinHash, salt: pinSalt } = hashPin(pin);

    // Insert family record
    const { data: family, error: familyError } = await supabase
      .from('families')
      .insert({
        parent_user_id: userId,
        family_name: familyName,
        parent_name: parentName,
        pin_hash: pinHash,
        pin_salt: pinSalt,
        ntfy_topic: ntfyTopic || null,
        created_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (familyError) {
      logger.error('Failed to create family record', { error: familyError.message, userId });
      // Rollback auth user
      await supabase.auth.admin.deleteUser(userId);
      return res.status(500).json({ success: false, error: 'Failed to create family profile.' });
    }

    const token = generateParentToken({
      userId,
      familyId: family.id,
      email,
    });

    logger.info('New family registered', { familyId: family.id, email });

    return res.status(201).json({
      success: true,
      message: 'Family account created successfully.',
      token,
      family: {
        id: family.id,
        familyName: family.family_name,
        parentName: family.parent_name,
      },
    });
  } catch (err) {
    logger.error('Register error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── POST /api/auth/login ─────────────────────────────────────────────────────
// Verifies parent credentials, returns JWT
router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res.status(400).json({ success: false, error: 'email and password are required.' });
    }

    const supabase = getSupabase();

    // Authenticate via Supabase Auth
    const { data: authData, error: authError } = await supabase.auth.signInWithPassword({
      email,
      password,
    });

    if (authError || !authData.user) {
      logger.warn('Login failed', { email, error: authError?.message });
      return res.status(401).json({ success: false, error: 'Invalid email or password.' });
    }

    const userId = authData.user.id;

    // Fetch family record
    const { data: family, error: familyError } = await supabase
      .from('families')
      .select('id, family_name, parent_name, ntfy_topic')
      .eq('parent_user_id', userId)
      .single();

    if (familyError || !family) {
      logger.error('Family record not found after login', { userId });
      return res.status(404).json({ success: false, error: 'Family profile not found.' });
    }

    const token = generateParentToken({
      userId,
      familyId: family.id,
      email,
    });

    logger.info('Parent logged in', { familyId: family.id, email });

    return res.json({
      success: true,
      token,
      family: {
        id: family.id,
        familyName: family.family_name,
        parentName: family.parent_name,
        ntfyTopic: family.ntfy_topic,
      },
    });
  } catch (err) {
    logger.error('Login error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── POST /api/auth/child/register ───────────────────────────────────────────
// Registers a child device under the authenticated family
router.post('/child/register', validateParentToken, async (req, res) => {
  try {
    const { deviceName, deviceModel, androidVersion } = req.body;
    const { familyId } = req.family;

    if (!deviceName) {
      return res.status(400).json({ success: false, error: 'deviceName is required.' });
    }

    const supabase = getSupabase();

    const { data: child, error: childError } = await supabase
      .from('children')
      .insert({
        family_id: familyId,
        device_name: deviceName,
        device_model: deviceModel || null,
        android_version: androidVersion || null,
        is_active: true,
        created_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (childError) {
      logger.error('Failed to register child device', { error: childError.message, familyId });
      return res.status(500).json({ success: false, error: 'Failed to register device.' });
    }

    const childToken = generateChildToken({
      childId: child.id,
      familyId,
      deviceName,
    });

    logger.info('Child device registered', { childId: child.id, familyId, deviceName });

    return res.status(201).json({
      success: true,
      message: 'Child device registered successfully.',
      childToken,
      child: {
        id: child.id,
        deviceName: child.device_name,
        familyId: child.family_id,
      },
    });
  } catch (err) {
    logger.error('Child register error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

// ─── POST /api/auth/verify ────────────────────────────────────────────────────
// Verifies any JWT token and returns its decoded payload
router.post('/verify', async (req, res) => {
  try {
    const { token } = req.body;

    if (!token) {
      return res.status(400).json({ success: false, error: 'token is required.' });
    }

    const jwt = require('jsonwebtoken');
    const decoded = jwt.verify(token, process.env.JWT_SECRET);

    return res.json({
      success: true,
      valid: true,
      payload: {
        sub: decoded.sub,
        familyId: decoded.familyId,
        role: decoded.role,
        email: decoded.email || null,
        deviceName: decoded.deviceName || null,
        expiresAt: new Date(decoded.exp * 1000).toISOString(),
      },
    });
  } catch (err) {
    if (err.name === 'TokenExpiredError') {
      return res.status(401).json({ success: false, valid: false, error: 'Token has expired.' });
    }
    if (err.name === 'JsonWebTokenError') {
      return res.status(401).json({ success: false, valid: false, error: 'Invalid token.' });
    }
    logger.error('Verify error', { error: err.message });
    return res.status(500).json({ success: false, error: 'Internal server error.' });
  }
});

module.exports = router;
