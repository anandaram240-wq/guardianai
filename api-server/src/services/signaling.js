/**
 * WebRTC Signaling via Socket.io
 *
 * Handles all real-time communication:
 *  - Parent↔Child WebRTC signaling (offer/answer/ICE)
 *  - Command delivery (instant, no polling needed)
 *  - Online status tracking
 *  - Live location streaming
 */

const { createClient } = require('@supabase/supabase-js');
const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'change-this-secret';

// Track online devices
const connectedChildren = new Map();  // childId → socketId
const connectedParents  = new Map();  // familyId → socketId[]

function setupSignaling(io) {

  // ── Authentication middleware ────────────────────────────────────────────
  io.use((socket, next) => {
    const token = socket.handshake.auth?.token;
    if (!token) return next(new Error('No token'));

    try {
      const decoded = jwt.verify(token, JWT_SECRET);
      socket.userId   = decoded.id;
      socket.role      = decoded.role;  // 'parent' or 'child'
      socket.familyId  = decoded.familyId;
      socket.childId   = decoded.childId;
      next();
    } catch (err) {
      next(new Error('Invalid token'));
    }
  });

  io.on('connection', (socket) => {
    console.log(`✅ ${socket.role} connected: ${socket.id}`);

    // ── CHILD CONNECTS ───────────────────────────────────────────────────
    if (socket.role === 'child') {
      const childId = socket.childId;
      connectedChildren.set(childId, socket.id);
      socket.join(`child:${childId}`);
      socket.join(`family:${socket.familyId}`);

      // Notify parent that child is online
      io.to(`family:${socket.familyId}`).emit('child-status', {
        childId, online: true, batteryLevel: socket.handshake.auth?.battery
      });

      // Handle real-time location from child
      socket.on('location-update', (data) => {
        io.to(`family:${socket.familyId}`).emit('location-update', {
          childId, ...data
        });
      });

      // Handle alert from child
      socket.on('alert', (data) => {
        io.to(`family:${socket.familyId}`).emit('alert', { childId, ...data });
      });

      // WebRTC: child sends offer (for camera stream)
      socket.on('offer', (data) => {
        io.to(`family:${socket.familyId}`).emit('offer', {
          childId, offer: data.offer
        });
      });

      // WebRTC: child sends ICE candidate
      socket.on('ice-candidate', (data) => {
        io.to(`family:${socket.familyId}`).emit('ice-candidate', {
          childId, candidate: data.candidate
        });
      });

      // Command acknowledgment
      socket.on('command-ack', (data) => {
        io.to(`family:${socket.familyId}`).emit('command-ack', {
          childId, commandId: data.commandId, status: data.status, result: data.result
        });
      });

      socket.on('disconnect', () => {
        connectedChildren.delete(childId);
        io.to(`family:${socket.familyId}`).emit('child-status', {
          childId, online: false
        });
        console.log(`📴 Child disconnected: ${childId}`);
      });
    }

    // ── PARENT CONNECTS ──────────────────────────────────────────────────
    if (socket.role === 'parent') {
      const familyId = socket.familyId;
      if (!connectedParents.has(familyId)) connectedParents.set(familyId, []);
      connectedParents.get(familyId).push(socket.id);
      socket.join(`family:${familyId}`);

      // Send current online children status
      for (const [childId, sockId] of connectedChildren) {
        socket.emit('child-status', { childId, online: true });
      }

      // ── WEBRTC SIGNALING FROM PARENT ─────────────────────────────────

      // Parent requests camera stream
      socket.on('start-camera', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'start_camera', params: { facing: data.cameraFacing || 'front' }
        });
      });

      socket.on('stop-camera', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'stop_camera'
        });
      });

      socket.on('start-audio', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'start_audio'
        });
      });

      socket.on('stop-audio', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'stop_audio'
        });
      });

      socket.on('switch-camera', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'switch_camera', params: { facing: data.facing }
        });
      });

      socket.on('take-snapshot', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: 'take_photo_front'
        });
      });

      // WebRTC: parent sends answer
      socket.on('answer', (data) => {
        io.to(`child:${data.childId}`).emit('answer', {
          answer: data.answer
        });
      });

      // WebRTC: parent sends ICE candidate
      socket.on('ice-candidate', (data) => {
        io.to(`child:${data.childId}`).emit('ice-candidate', {
          candidate: data.candidate
        });
      });

      // Parent sends command to specific child
      socket.on('send-command', (data) => {
        io.to(`child:${data.childId}`).emit('command', {
          command: data.command, params: data.params
        });
      });

      socket.on('disconnect', () => {
        const ids = connectedParents.get(familyId) || [];
        connectedParents.set(familyId, ids.filter(id => id !== socket.id));
        console.log(`📴 Parent disconnected: ${familyId}`);
      });
    }
  });
}

function getConnectedChildren() {
  return Object.fromEntries(connectedChildren);
}

module.exports = { setupSignaling, getConnectedChildren };
