import { normalizeApiBaseUrl } from '@/config/env';

describe('API environment config', () => {
  const originalDev = (globalThis as typeof globalThis & { __DEV__?: boolean }).__DEV__;
  const originalE2e = process.env.EXPO_PUBLIC_E2E;

  afterEach(() => {
    Object.defineProperty(globalThis, '__DEV__', {
      configurable: true,
      value: originalDev,
      writable: true,
    });

    if (originalE2e === undefined) {
      delete process.env.EXPO_PUBLIC_E2E;
    } else {
      process.env.EXPO_PUBLIC_E2E = originalE2e;
    }
  });

  function setRuntime({ dev, e2e }: { dev: boolean; e2e?: string }) {
    Object.defineProperty(globalThis, '__DEV__', {
      configurable: true,
      value: dev,
      writable: true,
    });

    if (e2e === undefined) {
      delete process.env.EXPO_PUBLIC_E2E;
    } else {
      process.env.EXPO_PUBLIC_E2E = e2e;
    }
  }

  it('normalizes whitespace and trailing slashes', () => {
    setRuntime({ dev: false });

    expect(normalizeApiBaseUrl(' https://yuuka.test/api/v1/ ')).toBe('https://yuuka.test/api/v1');
  });

  it('allows development HTTP URLs', () => {
    setRuntime({ dev: true });

    expect(normalizeApiBaseUrl('http://dev.yuuka.test/api/v1')).toBe(
      'http://dev.yuuka.test/api/v1',
    );
  });

  it('allows production HTTPS URLs', () => {
    setRuntime({ dev: false });

    expect(normalizeApiBaseUrl('https://yuuka.test/api/v1')).toBe('https://yuuka.test/api/v1');
  });

  it('rejects production HTTP URLs', () => {
    setRuntime({ dev: false });

    expect(() => normalizeApiBaseUrl('http://yuuka.test/api/v1')).toThrow(
      'HTTPS outside development',
    );
  });

  it('allows E2E release HTTP URLs for localhost', () => {
    setRuntime({ dev: false, e2e: '1' });

    expect(normalizeApiBaseUrl('http://localhost:8080/api/v1')).toBe(
      'http://localhost:8080/api/v1',
    );
  });

  it('allows E2E release HTTP URLs for 127.0.0.1', () => {
    setRuntime({ dev: false, e2e: '1' });

    expect(normalizeApiBaseUrl('http://127.0.0.1:8080/api/v1')).toBe(
      'http://127.0.0.1:8080/api/v1',
    );
  });

  it('rejects E2E release HTTP URLs for remote hosts', () => {
    setRuntime({ dev: false, e2e: '1' });

    expect(() => normalizeApiBaseUrl('http://api.yuuka.test/api/v1')).toThrow(
      'HTTPS outside development',
    );
  });

  it('rejects unsupported URL protocols', () => {
    setRuntime({ dev: true, e2e: '1' });

    expect(() => normalizeApiBaseUrl('ftp://yuuka.test/api/v1')).toThrow('HTTP or HTTPS');
  });
});
