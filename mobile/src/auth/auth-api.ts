import { apiRequest, expectNoContent, parseApiResponse } from '@/api/api-client';

import { SignInFormValues } from './schemas';
import { AuthSession, authSessionSchema } from './session';

export async function login(apiBaseUrl: string, values: SignInFormValues): Promise<AuthSession> {
  const response = await apiRequest(`${apiBaseUrl}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(values),
  });
  return parseApiResponse(response, authSessionSchema);
}

export async function refreshSession(
  apiBaseUrl: string,
  refreshToken: string,
): Promise<AuthSession> {
  const response = await apiRequest(`${apiBaseUrl}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  return parseApiResponse(response, authSessionSchema);
}

export async function logout(apiBaseUrl: string, refreshToken: string): Promise<void> {
  const response = await apiRequest(`${apiBaseUrl}/auth/logout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  await expectNoContent(response);
}
