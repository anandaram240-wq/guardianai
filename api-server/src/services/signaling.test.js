'use strict';

const jwt = require('jsonwebtoken');

// Mock supabase-js so it doesn't complain
jest.mock('@supabase/supabase-js', () => ({
  createClient: jest.fn()
}));

const JWT_SECRET = 'change-this-secret';
process.env.JWT_SECRET = JWT_SECRET;

const { setupSignaling, getConnectedChildren } = require('./signaling');

describe('WebRTC Signaling Service', () => {
  let ioMock;
  let middlewareFn;
  let connectionCallback;

  beforeEach(() => {
    ioMock = {
      use: jest.fn((fn) => { middlewareFn = fn; }),
      on: jest.fn((event, cb) => {
        if (event === 'connection') {
          connectionCallback = cb;
        }
      }),
      to: jest.fn().mockReturnValue({
        emit: jest.fn()
      })
    };
  });

  test('should register connection events and middleware', () => {
    setupSignaling(ioMock);
    expect(ioMock.use).toHaveBeenCalledTimes(1);
    expect(ioMock.on).toHaveBeenCalledWith('connection', expect.any(Function));
  });

  describe('Socket Middleware Authentication', () => {
    test('should fail if no token is provided', () => {
      setupSignaling(ioMock);
      const socket = { handshake: { auth: {} } };
      const next = jest.fn();

      middlewareFn(socket, next);
      expect(next).toHaveBeenCalledWith(expect.any(Error));
      expect(next.mock.calls[0][0].message).toBe('No token');
    });

    test('should fail if token is invalid', () => {
      setupSignaling(ioMock);
      const socket = { handshake: { auth: { token: 'invalid-token' } } };
      const next = jest.fn();

      middlewareFn(socket, next);
      expect(next).toHaveBeenCalledWith(expect.any(Error));
      expect(next.mock.calls[0][0].message).toBe('Invalid token');
    });

    test('should succeed and extract properties if token is valid', () => {
      setupSignaling(ioMock);
      const payload = { id: 'user-123', role: 'parent', familyId: 'family-456' };
      const token = jwt.sign(payload, JWT_SECRET);
      
      const socket = { handshake: { auth: { token } } };
      const next = jest.fn();

      middlewareFn(socket, next);
      expect(next).toHaveBeenCalledWith();
      expect(socket.userId).toBe(payload.id);
      expect(socket.role).toBe(payload.role);
      expect(socket.familyId).toBe(payload.familyId);
    });
  });

  describe('Socket Connection Handlers', () => {
    let mockEmit;

    beforeEach(() => {
      mockEmit = jest.fn();
      ioMock.to = jest.fn().mockReturnValue({ emit: mockEmit });
      setupSignaling(ioMock);
    });

    test('should handle child connection and status notification', () => {
      const childSocket = {
        role: 'child',
        childId: 'child-789',
        familyId: 'family-456',
        id: 'socket-child-1',
        handshake: { auth: { battery: 85 } },
        join: jest.fn(),
        on: jest.fn(),
        emit: jest.fn()
      };

      connectionCallback(childSocket);

      expect(childSocket.join).toHaveBeenCalledWith('child:child-789');
      expect(childSocket.join).toHaveBeenCalledWith('family:family-456');
      expect(ioMock.to).toHaveBeenCalledWith('family:family-456');
      expect(mockEmit).toHaveBeenCalledWith('child-status', {
        childId: 'child-789',
        online: true,
        batteryLevel: 85
      });
    });

    test('should handle parent connection and starting camera command routing', () => {
      const parentSocket = {
        role: 'parent',
        familyId: 'family-456',
        id: 'socket-parent-1',
        join: jest.fn(),
        on: jest.fn((event, handler) => {
          if (event === 'start-camera') {
            handler({ childId: 'child-789', cameraFacing: 'back' });
          }
        }),
        emit: jest.fn()
      };

      connectionCallback(parentSocket);

      expect(parentSocket.join).toHaveBeenCalledWith('family:family-456');
      expect(ioMock.to).toHaveBeenCalledWith('child:child-789');
      expect(mockEmit).toHaveBeenCalledWith('command', {
        command: 'start_camera',
        params: { facing: 'back' }
      });
    });
  });
});
