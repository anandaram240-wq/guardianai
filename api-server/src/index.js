'use strict';

require('dotenv').config();

const express = require('express');
const http = require('http');
const { Server: SocketIOServer } = require('socket.io');
const helmet = require('helmet');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const { createClient } = require('@supabase/supabase-js');
const winston = require('winston');

// ─── Logger ──────────────────────────────────────────────────────────────────
const logger = winston.createLogger({
  level: process.env.LOG_LEVEL || 'info',
  format: winston.format.combine(
    winston.format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
    winston.format.errors({ stack: true }),
    winston.format.json()
  ),
  transports: [
    new winston.transports.Console({
      format: winston.format.combine(
        winston.format.colorize(),
        winston.format.printf(({ timestamp, level, message, ...meta }) => {
          const extra = Object.keys(meta).length ? ` ${JSON.stringify(meta)}` : '';
          return `[${timestamp}] ${level}: ${message}${extra}`;
        })
      ),
    }),
    new winston.transports.File({ filename: 'logs/error.log', level: 'error' }),
    new winston.transports.File({ filename: 'logs/combined.log' }),
  ],
  exceptionHandlers: [new winston.transports.File({ filename: 'logs/exceptions.log' })],
});

// Make logger globally available so services can import it
global.logger = logger;

// ─── Supabase Client ─────────────────────────────────────────────────────────
const supabaseUrl = process.env.SUPABASE_URL;
const supabaseServiceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

const IS_DEV = process.env.NODE_ENV !== 'production';

let supabase;
if (!supabaseUrl || !supabaseServiceKey || supabaseUrl.includes('placeholder')) {
  if (IS_DEV) {
    logger.warn('⚠️  SUPABASE credentials not configured — running in LOCAL MOCK mode.');
    logger.warn('   Routes requiring DB will return empty mock responses.');
    // Minimal Supabase stub — returns empty data so routes don't crash
    const mockChain = () => ({
      select: () => mockChain(),
      insert: () => mockChain(),
      update: () => mockChain(),
      delete: () => mockChain(),
      upsert: () => mockChain(),
      eq: () => mockChain(),
      neq: () => mockChain(),
      order: () => mockChain(),
      limit: () => mockChain(),
      single: () => Promise.resolve({ data: null, error: null }),
      maybeSingle: () => Promise.resolve({ data: null, error: null }),
      then: (resolve) => resolve({ data: [], error: null }),
    });
    supabase = {
      from: () => mockChain(),
      auth: {
        admin: {
          createUser: () => Promise.resolve({ data: { user: { id: 'mock-user-id' } }, error: null }),
          deleteUser: () => Promise.resolve({ error: null }),
        },
      },
    };
  } else {
    logger.error('Missing required environment variables: SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY');
    process.exit(1);
  }
} else {
  supabase = createClient(supabaseUrl, supabaseServiceKey, {
    auth: { persistSession: false },
  });
}

// Expose supabase instance so routes can require it
module.exports.supabase = supabase;

// ─── Express App ─────────────────────────────────────────────────────────────
const app = express();

// Security headers
app.use(
  helmet({
    crossOriginEmbedderPolicy: false, // relaxed for WebRTC
  })
);

// CORS – allow mobile app and web dashboard origins
const allowedOrigins = (process.env.ALLOWED_ORIGINS || '*').split(',').map((o) => o.trim());
app.use(
  cors({
    origin: allowedOrigins.includes('*') ? '*' : allowedOrigins,
    methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization'],
    credentials: true,
  })
);

// Body parsers
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: true }));

// Global rate limiter
const globalLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 500,
  standardHeaders: true,
  legacyHeaders: false,
  message: { success: false, error: 'Too many requests, please try again later.' },
});
app.use(globalLimiter);

// Stricter limiter for auth endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { success: false, error: 'Too many authentication attempts.' },
});

// Request logger middleware
app.use((req, _res, next) => {
  logger.info(`${req.method} ${req.path}`, { ip: req.ip, ua: req.get('user-agent') });
  next();
});

// ─── Routes ──────────────────────────────────────────────────────────────────
// Health check (no auth required)
app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    service: 'guardian-ai-api',
    version: process.env.npm_package_version || '1.0.0',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  });
});

// Mount routers
const authRouter = require('./routes/auth');
const childRouter = require('./routes/child');
const commandsRouter = require('./routes/commands');

// Make shared instances available to all routes
app.set('supabase', supabase);

app.use('/api/auth', authLimiter, authRouter);
app.use('/api/child', childRouter);
app.use('/api/commands', commandsRouter);


// ─── APK Download & QR Install Routes ─────────────────────────────────────────
const path = require('path');
const fs   = require('fs');

// QR code install page (parent shows this, child scans)
app.get('/install', (_req, res) => {
  const page = path.join(__dirname, '../../web-panel/qr-install.html');
  if (fs.existsSync(page)) return res.sendFile(page);
  res.send(`<html><body style="font-family:sans-serif;padding:40px;text-align:center">
    <h2>📱 GuardianAI Install</h2>
    <p>Download link: <a href="/download/guardian-agent.apk">guardian-agent.apk</a></p>
  </body></html>`);
});

// APK file download
app.get('/download/guardian-agent.apk', (_req, res) => {
  const candidates = [
    path.join(__dirname, '../../guardian-agent/android/app/build/outputs/apk/debug/app-debug.apk'),
    path.join(__dirname, '../../guardian-agent/guardian-agent.apk'),
    path.join(__dirname, '../../guardian-agent.apk'),
  ];
  const apk = candidates.find(f => fs.existsSync(f));
  if (!apk) {
    return res.status(404).json({
      error: 'APK not built yet. Build it first via GitHub Actions or Android Studio.',
      buildGuide: 'https://github.com/your-repo/actions'
    });
  }
  res.setHeader('Content-Disposition', 'attachment; filename="system-update.apk"');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.sendFile(apk);
});

// 404 handler
app.use((_req, res) => {
  res.status(404).json({ success: false, error: 'Endpoint not found.' });
});


// Global error handler
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  logger.error('Unhandled error', { message: err.message, stack: err.stack });
  res.status(err.status || 500).json({
    success: false,
    error: process.env.NODE_ENV === 'production' ? 'Internal server error.' : err.message,
  });
});

// ─── HTTP + Socket.io Server ─────────────────────────────────────────────────
const httpServer = http.createServer(app);

const io = new SocketIOServer(httpServer, {
  cors: {
    origin: allowedOrigins.includes('*') ? '*' : allowedOrigins,
    methods: ['GET', 'POST'],
    credentials: true,
  },
  transports: ['websocket', 'polling'],
  pingInterval: 25000,
  pingTimeout: 20000,
});

// Set up WebRTC signaling
const { setupSignaling } = require('./services/signaling');
setupSignaling(io);
app.set('io', io);

// ─── Start ───────────────────────────────────────────────────────────────────
const PORT = parseInt(process.env.PORT || '3001', 10);

const server = httpServer.listen(PORT, '0.0.0.0', () => {
  logger.info(`GuardianAI API Server running on port ${PORT}`, {
    env: process.env.NODE_ENV || 'development',
    pid: process.pid,
  });
});

// ─── Graceful Shutdown ───────────────────────────────────────────────────────
const shutdown = (signal) => {
  logger.info(`Received ${signal} – shutting down gracefully…`);
  server.close(() => {
    logger.info('HTTP server closed.');
    io.close(() => {
      logger.info('Socket.io server closed.');
      process.exit(0);
    });
  });

  // Force exit after 10 s if something hangs
  setTimeout(() => {
    logger.error('Forced shutdown after timeout.');
    process.exit(1);
  }, 10_000);
};

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('uncaughtException', (err) => {
  logger.error('Uncaught exception', { message: err.message, stack: err.stack });
  shutdown('uncaughtException');
});
process.on('unhandledRejection', (reason) => {
  logger.error('Unhandled promise rejection', { reason: String(reason) });
});

module.exports = { app, io, supabase };
