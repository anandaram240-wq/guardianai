'use strict';

// Set JWT_SECRET before loading the module to prevent process.exit(1)
process.env.JWT_SECRET = 'my-super-secret-test-key-12345';

const {
  generateParentToken,
  generateChildToken,
  validateParentToken,
  validateChildToken,
  validateEitherToken
} = require('./auth');
const jwt = require('jsonwebtoken');

describe('Auth Middleware', () => {
  const mockPayloadParent = {
    userId: 'parent-123',
    familyId: 'family-456',
    email: 'parent@example.com'
  };

  const mockPayloadChild = {
    childId: 'child-789',
    familyId: 'family-456',
    deviceName: 'Child-Pixel-6'
  };

  describe('Token Generation', () => {
    test('should generate a valid parent token', () => {
      const token = generateParentToken(mockPayloadParent);
      expect(token).toBeDefined();
      
      const decoded = jwt.verify(token, process.env.JWT_SECRET);
      expect(decoded.sub).toBe(mockPayloadParent.userId);
      expect(decoded.familyId).toBe(mockPayloadParent.familyId);
      expect(decoded.email).toBe(mockPayloadParent.email);
      expect(decoded.role).toBe('parent');
    });

    test('should generate a valid child token', () => {
      const token = generateChildToken(mockPayloadChild);
      expect(token).toBeDefined();

      const decoded = jwt.verify(token, process.env.JWT_SECRET);
      expect(decoded.sub).toBe(mockPayloadChild.childId);
      expect(decoded.familyId).toBe(mockPayloadChild.familyId);
      expect(decoded.deviceName).toBe(mockPayloadChild.deviceName);
      expect(decoded.role).toBe('child');
    });
  });

  describe('validateParentToken Middleware', () => {
    let req, res, next;

    beforeEach(() => {
      req = {
        headers: {}
      };
      res = {
        status: jest.fn().mockReturnThis(),
        json: jest.fn()
      };
      next = jest.fn();
    });

    test('should pass if a valid parent token is provided', () => {
      const token = generateParentToken(mockPayloadParent);
      req.headers['authorization'] = `Bearer ${token}`;

      validateParentToken(req, res, next);

      expect(next).toHaveBeenCalled();
      expect(req.family).toBeDefined();
      expect(req.family.userId).toBe(mockPayloadParent.userId);
      expect(req.family.role).toBe('parent');
      expect(res.status).not.toHaveBeenCalled();
    });

    test('should fail if authorization header is missing', () => {
      validateParentToken(req, res, next);

      expect(next).not.toHaveBeenCalled();
      expect(res.status).toHaveBeenCalledWith(401);
      expect(res.json).toHaveBeenCalledWith(
        expect.objectContaining({ error: 'Missing authorization token.' })
      );
    });

    test('should fail if token is invalid', () => {
      req.headers['authorization'] = 'Bearer invalid-token-here';

      validateParentToken(req, res, next);

      expect(next).not.toHaveBeenCalled();
      expect(res.status).toHaveBeenCalledWith(401);
      expect(res.json).toHaveBeenCalledWith(
        expect.objectContaining({ error: 'Invalid or expired token.' })
      );
    });

    test('should fail if role is child instead of parent', () => {
      const token = generateChildToken(mockPayloadChild);
      req.headers['authorization'] = `Bearer ${token}`;

      validateParentToken(req, res, next);

      expect(next).not.toHaveBeenCalled();
      expect(res.status).toHaveBeenCalledWith(403);
      expect(res.json).toHaveBeenCalledWith(
        expect.objectContaining({ error: 'Forbidden: parent token required.' })
      );
    });
  });

  describe('validateChildToken Middleware', () => {
    let req, res, next;

    beforeEach(() => {
      req = {
        headers: {}
      };
      res = {
        status: jest.fn().mockReturnThis(),
        json: jest.fn()
      };
      next = jest.fn();
    });

    test('should pass if a valid child token is provided', () => {
      const token = generateChildToken(mockPayloadChild);
      req.headers['authorization'] = `Bearer ${token}`;

      validateChildToken(req, res, next);

      expect(next).toHaveBeenCalled();
      expect(req.child).toBeDefined();
      expect(req.child.childId).toBe(mockPayloadChild.childId);
      expect(req.child.role).toBe('child');
    });

    test('should fail if role is parent instead of child', () => {
      const token = generateParentToken(mockPayloadParent);
      req.headers['authorization'] = `Bearer ${token}`;

      validateChildToken(req, res, next);

      expect(next).not.toHaveBeenCalled();
      expect(res.status).toHaveBeenCalledWith(403);
    });
  });

  describe('validateEitherToken Middleware', () => {
    let req, res, next;

    beforeEach(() => {
      req = {
        headers: {}
      };
      res = {
        status: jest.fn().mockReturnThis(),
        json: jest.fn()
      };
      next = jest.fn();
    });

    test('should accept parent token and populate req.family', () => {
      const token = generateParentToken(mockPayloadParent);
      req.headers['authorization'] = `Bearer ${token}`;

      validateEitherToken(req, res, next);

      expect(next).toHaveBeenCalled();
      expect(req.family).toBeDefined();
      expect(req.child).toBeUndefined();
    });

    test('should accept child token and populate req.child', () => {
      const token = generateChildToken(mockPayloadChild);
      req.headers['authorization'] = `Bearer ${token}`;

      validateEitherToken(req, res, next);

      expect(next).toHaveBeenCalled();
      expect(req.child).toBeDefined();
      expect(req.family).toBeUndefined();
    });
  });
});
