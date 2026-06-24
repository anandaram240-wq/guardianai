'use strict';

// Mock node-fetch
jest.mock('node-fetch', () => jest.fn(() => Promise.resolve({
  json: () => Promise.resolve({ success: true })
})));

const fetch = require('node-fetch');
const { sendNtfyPush } = require('./commands');

describe('Commands Router / ntfy Push Service', () => {
  beforeEach(() => {
    fetch.mockClear();
    process.env.NTFY_URL = 'http://test-ntfy-server:9090';
  });

  afterEach(() => {
    delete process.env.NTFY_URL;
  });

  test('should send a simple push notification successfully', async () => {
    await sendNtfyPush(
      'family-123',
      'Test Alert',
      'Test Alert Message',
      'default'
    );

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(
      'http://test-ntfy-server:9090/guardian_family_123',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Title': 'Test Alert',
          'Priority': '3',
          'Tags': 'shield'
        }),
        body: 'Test Alert Message'
      })
    );
  });

  test('should handle higher priorities and convert them to ntfy numbers', async () => {
    await sendNtfyPush(
      'family-123',
      'Urgent Alert',
      'This is urgent',
      'urgent',
      ['warning', 'sos']
    );

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/guardian_family_123'),
      expect.objectContaining({
        headers: expect.objectContaining({
          'Priority': '5',
          'Tags': 'warning,sos'
        })
      })
    );
  });

  test('should append action headers if provided', async () => {
    const actions = [{ action: 'view', label: 'Open App', url: 'https://test.link' }];
    await sendNtfyPush(
      'family-123',
      'Interactive Alert',
      'Click below',
      'default',
      [],
      actions
    );

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({
          'Actions': 'view, Open App, https://test.link'
        })
      })
    );
  });

  test('should gracefully handle network error without crashing', async () => {
    fetch.mockImplementationOnce(() => Promise.reject(new Error('Network error')));
    
    // Should not throw an error
    await expect(sendNtfyPush(
      'family-123',
      'Error Test',
      'Should handle rejection'
    )).resolves.not.toThrow();

    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
