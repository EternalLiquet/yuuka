import { z } from 'zod';

import { apiRequest, expectNoContent, parseApiResponse } from '@/api/api-client';
import { ApiError } from '@/api/api-error';

function response({
  body,
  contentType = 'application/json',
  json,
  ok,
  status,
}: {
  body?: unknown;
  contentType?: string;
  json?: () => Promise<unknown>;
  ok: boolean;
  status: number;
}): Response {
  return {
    headers: { get: () => contentType } as unknown as Headers,
    json: json ?? jest.fn().mockResolvedValue(body),
    ok,
    status,
  } as unknown as Response;
}

describe('API client', () => {
  afterEach(() => jest.restoreAllMocks());

  it('enforces secure fetch defaults', async () => {
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(response({ body: {}, ok: true, status: 200 }));

    await apiRequest('https://yuuka.test/api/v1/me', {
      headers: { Authorization: 'Bearer token' },
    });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://yuuka.test/api/v1/me',
      expect.objectContaining({
        cache: 'no-store',
        credentials: 'omit',
        redirect: 'error',
        headers: { Accept: 'application/json', Authorization: 'Bearer token' },
      }),
    );
  });

  it('parses successful JSON with the supplied contract', async () => {
    await expect(
      parseApiResponse(
        response({ body: { value: 3 }, ok: true, status: 200 }),
        z.object({ value: z.number() }),
      ),
    ).resolves.toEqual({ value: 3 });
  });

  it('maps JSON errors and malformed non-JSON responses safely', async () => {
    await expect(
      parseApiResponse(
        response({
          body: { code: 'CONFLICT', fieldErrors: {}, message: 'Refresh.', traceId: 'trace' },
          ok: false,
          status: 409,
        }),
        z.object({ value: z.number() }),
      ),
    ).rejects.toMatchObject({ code: 'CONFLICT', status: 409 });

    await expect(
      expectNoContent(response({ contentType: 'text/html', ok: false, status: 502 })),
    ).rejects.toEqual(expect.any(ApiError));
  });

  it('accepts an empty successful response', async () => {
    await expect(expectNoContent(response({ ok: true, status: 204 }))).resolves.toBeUndefined();
  });

  it('treats malformed JSON as an empty payload', async () => {
    await expect(
      expectNoContent(
        response({
          json: jest.fn<Promise<unknown>, []>().mockRejectedValue(new Error('bad json')),
          ok: false,
          status: 502,
        }),
      ),
    ).rejects.toMatchObject({ code: 'HTTP_502' });
  });
});
