import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('committed backend contract', () => {
  const contract = JSON.parse(
    readFileSync(resolve(__dirname, '../../../docs/openapi.json'), 'utf8'),
  ) as { paths: Record<string, Record<string, unknown>> };

  it.each([
    ['/api/v1/auth/login', 'post'],
    ['/api/v1/auth/refresh', 'post'],
    ['/api/v1/paychecks', 'post'],
    ['/api/v1/paychecks/active', 'get'],
    ['/api/v1/paychecks/history', 'get'],
    ['/api/v1/spending-buckets/performance/rolling', 'get'],
    ['/api/v1/spending-buckets/performance/rolling-90-days', 'get'],
    ['/api/v1/search/entries', 'get'],
    ['/api/v1/paychecks/from-draft', 'post'],
    ['/api/v1/paychecks/from-template', 'post'],
    ['/api/v1/paychecks/{paycheckId}/leftover-entry', 'post'],
    ['/api/v1/paychecks/{paycheckId}/entries', 'post'],
    ['/api/v1/entries/{entryId}/status', 'post'],
    ['/api/v1/entries/{entryId}/status-history', 'get'],
    ['/api/v1/entries/{entryId}/bucket-transactions', 'post'],
    ['/api/v1/paybacks', 'get'],
    ['/api/v1/paybacks', 'post'],
    ['/api/v1/paybacks/{paybackId}', 'get'],
    ['/api/v1/paybacks/{paybackId}', 'patch'],
    ['/api/v1/paybacks/{paybackId}/repayments', 'get'],
    ['/api/v1/templates', 'get'],
  ])('contains %s %s', (path, method) => {
    expect(contract.paths[path]?.[method]).toBeDefined();
  });

  it('marks rolling spending bucket parameters as optional with supported days', () => {
    const operation = contract.paths['/api/v1/spending-buckets/performance/rolling']?.get as
      | {
          parameters?: {
            name?: string;
            required?: boolean;
            schema?: { default?: unknown; enum?: unknown[]; format?: string; type?: string };
          }[];
        }
      | undefined;

    const days = operation?.parameters?.find((parameter) => parameter.name === 'days');
    expect(days).toMatchObject({
      required: false,
    });
    expect(days?.schema).toMatchObject({
      default: 30,
      enum: [30, 90],
      format: 'int32',
      type: 'integer',
    });
    expect(operation?.parameters?.find((parameter) => parameter.name === 'asOfDate')).toMatchObject(
      {
        required: false,
      },
    );
  });
});
