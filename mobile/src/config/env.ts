export function normalizeApiBaseUrl(value: string): string {
  const url = new URL(value.trim());
  if (!__DEV__ && url.protocol !== 'https:') {
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
