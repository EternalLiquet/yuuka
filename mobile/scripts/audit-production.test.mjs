import assert from 'node:assert/strict';
import test from 'node:test';

import {
  evaluateAuditReport,
  evaluateDependencyTreeInspection,
  evaluateImageSizeDependencyTree,
} from './audit-production.mjs';

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

const imageSizeNode = { version: '1.2.1' };

function approvedProductionTree(additionalRootDependencies = {}) {
  return {
    name: 'yuuka-mobile',
    version: '1.0.0',
    dependencies: {
      expo: {
        version: '57.0.12',
        dependencies: {
          '@expo/metro': {
            version: '56.0.0',
            dependencies: {
              metro: {
                version: '0.84.4',
                dependencies: { 'image-size': imageSizeNode },
              },
            },
          },
        },
      },
      ...additionalRootDependencies,
    },
  };
}

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

test('accepts the current Metro-only production path to image-size', () => {
  const outcome = evaluateImageSizeDependencyTree(approvedProductionTree());

  assert.deepEqual(outcome.errors, []);
  assert.deepEqual(outcome.paths, ['yuuka-mobile > expo > @expo/metro > metro > image-size']);
});

test('rejects image-size as a direct root dependency', () => {
  const outcome = evaluateImageSizeDependencyTree(
    approvedProductionTree({ 'image-size': imageSizeNode }),
  );

  assert.match(outcome.errors[0], /yuuka-mobile > image-size/);
});

test('rejects an unrelated runtime path to image-size', () => {
  const outcome = evaluateImageSizeDependencyTree({
    name: 'yuuka-mobile',
    dependencies: {
      'runtime-image-parser': {
        version: '1.0.0',
        dependencies: { 'image-size': imageSizeNode },
      },
    },
  });

  assert.match(
    outcome.errors[0],
    /Unexpected production dependency path to image-size: yuuka-mobile > runtime-image-parser > image-size/,
  );
});

test('rejects an unrelated path alongside the approved Metro path', () => {
  const outcome = evaluateImageSizeDependencyTree(
    approvedProductionTree({
      'runtime-image-parser': {
        version: '1.0.0',
        dependencies: { 'image-size': imageSizeNode },
      },
    }),
  );

  assert.deepEqual(outcome.paths, [
    'yuuka-mobile > expo > @expo/metro > metro > image-size',
    'yuuka-mobile > runtime-image-parser > image-size',
  ]);
  assert.equal(outcome.errors.length, 1);
  assert.match(outcome.errors[0], /runtime-image-parser > image-size/);
});

test('rejects dependency-tree inspection failure', () => {
  const outcome = evaluateDependencyTreeInspection({
    error: new Error('npm ls could not start'),
    status: null,
    stderr: '',
    stdout: '',
  });

  assert.match(outcome.errors[0], /Unable to inspect.*npm ls could not start/);
});

test('rejects a nonzero dependency-tree inspection result', () => {
  const outcome = evaluateDependencyTreeInspection({
    error: undefined,
    status: 1,
    stderr: 'npm ls found an invalid dependency tree',
    stdout: '{}',
  });

  assert.match(outcome.errors[0], /Unable to inspect.*invalid dependency tree/);
});

test('rejects malformed dependency-tree JSON', () => {
  const outcome = evaluateDependencyTreeInspection({
    error: undefined,
    status: 0,
    stderr: '',
    stdout: '{not-json',
  });

  assert.match(outcome.errors[0], /not valid JSON/);
});

test('rejects malformed dependency-tree data', () => {
  const outcome = evaluateImageSizeDependencyTree({
    name: 'yuuka-mobile',
    dependencies: { expo: 'not-an-object' },
  });

  assert.match(outcome.errors[0], /malformed at: yuuka-mobile > expo/);
});
