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
    ['/api/v1/paychecks/from-template', 'post'],
    ['/api/v1/paychecks/{paycheckId}/leftover-entry', 'post'],
    ['/api/v1/paychecks/{paycheckId}/entries', 'post'],
    ['/api/v1/entries/{entryId}/status', 'post'],
    ['/api/v1/entries/{entryId}/status-history', 'get'],
    ['/api/v1/entries/{entryId}/bucket-transactions', 'post'],
    ['/api/v1/templates', 'get'],
  ])('contains %s %s', (path, method) => {
    expect(contract.paths[path]?.[method]).toBeDefined();
  });
});
