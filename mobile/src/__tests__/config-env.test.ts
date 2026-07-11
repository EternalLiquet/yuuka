import { normalizeApiBaseUrl } from '@/config/env';

describe('API environment config', () => {
  it('normalizes whitespace and trailing slashes', () => {
    expect(normalizeApiBaseUrl(' https://yuuka.test/api/v1/ ')).toBe('https://yuuka.test/api/v1');
  });

  it('rejects unsupported URL protocols', () => {
    expect(() => normalizeApiBaseUrl('ftp://yuuka.test/api/v1')).toThrow('HTTP or HTTPS');
  });
});
