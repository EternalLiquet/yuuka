export function normalizeApiBaseUrl(value: string): string {
  const url = new URL(value.trim());
  const isE2e = process.env.EXPO_PUBLIC_E2E === '1';
  const isLoopback = url.hostname === 'localhost' || url.hostname === '127.0.0.1';
  const allowedE2eHttp = isE2e && isLoopback && url.protocol === 'http:';

  if (!__DEV__ && url.protocol !== 'https:' && !allowedE2eHttp) {
    throw new Error('The Yuuka API URL must use HTTPS outside development.');
  }
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('The Yuuka API URL must use HTTP or HTTPS.');
  }
  return url.toString().replace(/\/$/, '');
}

export const defaultApiBaseUrl = normalizeApiBaseUrl(
  process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080/api/v1',
);
