import assert from 'node:assert/strict';
import test from 'node:test';

import { evaluateAuditReport } from './audit-production.mjs';

const acceptedRoot = {
  name: 'image-size',
  severity: 'high',
  via: [
    {
      dependency: 'image-size',
      name: 'image-size',
      title: 'image-size parser denial of service',
      url: 'https://github.com/advisories/GHSA-w3rx-r6r6-pgpr',
    },
  ],
};

test('accepts a propagated finding rooted only in an active exception', () => {
  const outcome = evaluateAuditReport(
    {
      vulnerabilities: {
        'image-size': acceptedRoot,
        metro: { name: 'metro', severity: 'high', via: ['image-size'] },
        'metro-config': { name: 'metro-config', severity: 'high', via: ['metro'] },
      },
    },
    new Date('2026-08-10T12:00:00Z'),
  );

  assert.deepEqual(outcome.errors, []);
  assert.equal(outcome.accepted.length, 1);
});

test('rejects an unaccepted root advisory', () => {
  const outcome = evaluateAuditReport(
    {
      vulnerabilities: {
        unsafe: {
          name: 'unsafe',
          severity: 'critical',
          via: [
            {
              dependency: 'unsafe',
              name: 'unsafe',
              title: 'new vulnerability',
              url: 'https://github.com/advisories/GHSA-xxxx-yyyy-zzzz',
            },
          ],
        },
      },
    },
    new Date('2026-08-10T12:00:00Z'),
  );

  assert.match(outcome.errors[0], /unaccepted advisory/);
});

test('rejects an accepted advisory after its review deadline', () => {
  const outcome = evaluateAuditReport(
    { vulnerabilities: { 'image-size': acceptedRoot } },
    new Date('2026-09-11T00:00:00Z'),
  );

  assert.match(outcome.errors[0], /expired on 2026-09-10/);
});

test('rejects a blocking dependency chain without a resolvable advisory', () => {
  const outcome = evaluateAuditReport(
    {
      vulnerabilities: {
        metro: { name: 'metro', severity: 'high', via: ['missing-dependency'] },
      },
    },
    new Date('2026-08-10T12:00:00Z'),
  );

  assert.match(outcome.errors[0], /could not resolve/);
});
