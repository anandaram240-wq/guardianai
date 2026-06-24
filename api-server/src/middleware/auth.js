'use strict';

const jwt = require('jsonwebtoken');
const logger = global.logger || console;

const JWT_SECRET = process.env.JWT_SECRET;
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d';
const CHILD_JWT_EXPIRES_IN = process.env.CHILD_JWT_EXPIRES_IN || '365d';

if (!JWT_SECRET) {
  if (process.env.NODE_ENV === 'production') {
    (logger.error || console.error)('JWT_SECRET environment variable is not set!');
    process.exit(1);
  } else {
    (logger.warn || console.warn)('⚠️  JWT_SECRET not set — using insecure dev fallback!');
  }
}

// ─── Token Generation ─────────────────────────────────────────────────────────

/**
 * Generate a JWT for a parent/guardian user.
 * @param {object} payload - { userId, familyId, email, role }
 * @returns {string} signed JWT
 */
function generateParentToken(payload) {
  return jwt.sign(
    {
      sub: payload.userId,
      familyId: payload.familyId,
      email: payload.email,
      role: 'parent',
      iat: Math.floor(Date.now() / 1000),
    },
    JWT_SECRET,
    { expiresIn: JWT_EXPIRES_IN }
  );
}

/**
 * Generate a JWT for a child device.
 * @param {object} payload - { childId, familyId, deviceName }
 * @returns {string} signed JWT
 */
function generateChildToken(payload) {
  return jwt.sign(
    {
      sub: payload.childId,
      familyId: payload.familyId,
      deviceName: payload.deviceName,
      role: 'child',
      iat: Math.floor(Date.now() / 1000),
    },
    JWT_SECRET,
    { expiresIn: CHILD_JWT_EXPIRES_IN }
  );
}

// ─── Token Verification Helper ────────────────────────────────────────────────

function verifyToken(token) {
  try {
    return { decoded: jwt.verify(token, JWT_SECRET), error: null };
  } catch (err) {
    return { decoded: null, error: err };
  }
}

function extractBearerToken(req) {
  const authHeader = req.headers['authorization'] || req.headers['Authorization'];
  if (!authHeader || !authHeader.startsWith('Bearer ')) return null;
  return authHeader.slice(7).trim();
}

// ─── Middleware ───────────────────────────────────────────────────────────────

/**
 * Middleware: validates parent JWT.
 * Attaches `req.family` = { userId, familyId, email } on success.
 */
function validateParentToken(req, res, next) {
  const token = extractBearerToken(req);
  if (!token) {
    return res.status(401).json({ success: false, error: 'Missing authorization token.' });
  }

  const { decoded, error } = verifyToken(token);

  if (error || !decoded) {
    logger.warn('Parent token validation failed', { error: error?.message });
    return res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }

  if (decoded.role !== 'parent') {
    return res.status(403).json({ success: false, error: 'Forbidden: parent token required.' });
  }

  req.family = {
    userId: decoded.sub,
    familyId: decoded.familyId,
    email: decoded.email,
    role: decoded.role,
  };

  return next();
}

/**
 * Middleware: validates child device JWT.
 * Attaches `req.child` = { childId, familyId, deviceName } on success.
 */
function validateChildToken(req, res, next) {
  const token = extractBearerToken(req);
  if (!token) {
    return res.status(401).json({ success: false, error: 'Missing authorization token.' });
  }

  const { decoded, error } = verifyToken(token);

  if (error || !decoded) {
    logger.warn('Child token validation failed', { error: error?.message });
    return res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }

  if (decoded.role !== 'child') {
    return res.status(403).json({ success: false, error: 'Forbidden: child device token required.' });
  }

  req.child = {
    childId: decoded.sub,
    familyId: decoded.familyId,
    deviceName: decoded.deviceName,
    role: decoded.role,
  };

  return next();
}

/**
 * Middleware: accepts either parent OR child token.
 * Attaches whichever matches as `req.family` or `req.child`.
 */
function validateEitherToken(req, res, next) {
  const token = extractBearerToken(req);
  if (!token) {
    return res.status(401).json({ success: false, error: 'Missing authorization token.' });
  }

  const { decoded, error } = verifyToken(token);

  if (error || !decoded) {
    logger.warn('Token validation failed', { error: error?.message });
    return res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }

  if (decoded.role === 'parent') {
    req.family = {
      userId: decoded.sub,
      familyId: decoded.familyId,
      email: decoded.email,
      role: decoded.role,
    };
  } else if (decoded.role === 'child') {
    req.child = {
      childId: decoded.sub,
      familyId: decoded.familyId,
      deviceName: decoded.deviceName,
      role: decoded.role,
    };
  } else {
    return res.status(403).json({ success: false, error: 'Unknown token role.' });
  }

  return next();
}

module.exports = {
  generateParentToken,
  generateChildToken,
  validateParentToken,
  validateChildToken,
  validateEitherToken,
};
