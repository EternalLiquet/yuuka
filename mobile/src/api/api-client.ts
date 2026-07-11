import { z } from 'zod';

import { mapApiError } from './api-error';

export type ApiRequestOptions = Omit<RequestInit, 'headers'> & {
  headers?: Record<string, string>;
  timeoutMs?: number;
};

export async function apiRequest(url: string, options: ApiRequestOptions = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? 15_000);
  try {
    return await fetch(url, {
      ...options,
      cache: 'no-store',
      credentials: 'omit',
      headers: { Accept: 'application/json', ...options.headers },
      redirect: 'error',
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timeout);
  }
}

export async function parseApiResponse<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  const payload = await readPayload(response);
  if (!response.ok) {
    throw mapApiError(response.status, payload);
  }
  return schema.parse(payload);
}

export async function expectNoContent(response: Response): Promise<void> {
  if (response.ok) {
    return;
  }
  throw mapApiError(response.status, await readPayload(response));
}

async function readPayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type')?.toLowerCase() ?? '';
  if (!contentType.includes('application/json')) {
    return null;
  }
  try {
    return await response.json();
  } catch {
    return null;
  }
}
