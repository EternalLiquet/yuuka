import { z } from 'zod';

const apiErrorSchema = z.object({
  code: z.string().min(1).max(80),
  message: z.string().min(1).max(500),
  details: z.record(z.string(), z.unknown()).default({}),
  fieldErrors: z.record(z.string(), z.string()).default({}),
  traceId: z.string().min(1).max(120),
});

export class ApiError extends Error {
  readonly code: string;
  readonly details: Record<string, unknown>;
  readonly fieldErrors: Record<string, string>;
  readonly status: number;
  readonly traceId?: string;

  constructor(args: {
    code: string;
    details?: Record<string, unknown>;
    fieldErrors?: Record<string, string>;
    message: string;
    status: number;
    traceId?: string;
  }) {
    super(args.message);
    this.name = 'ApiError';
    this.code = args.code;
    this.details = args.details ?? {};
    this.fieldErrors = args.fieldErrors ?? {};
    this.status = args.status;
    this.traceId = args.traceId;
  }
}

export function mapApiError(status: number, payload: unknown): ApiError {
  const parsed = apiErrorSchema.safeParse(payload);
  if (parsed.success) {
    return new ApiError({ status, ...parsed.data });
  }
  return new ApiError({
    status,
    code: `HTTP_${status}`,
    message: 'Yuuka could not complete the request.',
  });
}
