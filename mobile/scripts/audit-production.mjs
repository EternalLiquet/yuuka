import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const acceptedAdvisories = new Map([
  [
    'https://github.com/advisories/GHSA-w3rx-r6r6-pgpr',
    {
      dependency: 'image-size',
      expiresOn: '2026-09-10',
      reason: 'Metro only parses repository-controlled build assets; no patched release exists.',
    },
  ],
  [
    'https://github.com/advisories/GHSA-5p2g-fcmc-qvqq',
    {
      dependency: 'image-size',
      expiresOn: '2026-09-10',
      reason: 'Metro only parses repository-controlled build assets; no patched release exists.',
    },
  ],
]);

const blockingSeverities = new Set(['high', 'critical']);

export function evaluateAuditReport(report, now = new Date()) {
  const vulnerabilities = report?.vulnerabilities;
  if (!vulnerabilities || typeof vulnerabilities !== 'object') {
    return { errors: ['npm audit returned an unsupported report shape.'], accepted: [] };
  }

  const blockingNames = Object.entries(vulnerabilities)
    .filter(([, vulnerability]) => blockingSeverities.has(vulnerability.severity))
    .map(([name]) => name);

  const accepted = new Map();
  const errors = [];

  for (const name of blockingNames) {
    const roots = findRootAdvisories(name, vulnerabilities, new Set());
    if (roots.length === 0) {
      errors.push(`${name}: could not resolve the finding to a root advisory.`);
      continue;
    }

    for (const root of roots) {
      const policy = acceptedAdvisories.get(root.url);
      if (!policy) {
        errors.push(`${name}: unaccepted advisory ${root.url ?? root.title ?? 'unknown'}.`);
        continue;
      }
      if (root.dependency !== policy.dependency) {
        errors.push(
          `${name}: advisory ${root.url} applies to ${root.dependency}, not ${policy.dependency}.`,
        );
        continue;
      }
      if (isExpired(policy.expiresOn, now)) {
        errors.push(`${name}: acceptance for ${root.url} expired on ${policy.expiresOn}.`);
        continue;
      }
      accepted.set(root.url, policy);
    }
  }

  return {
    errors: [...new Set(errors)],
    accepted: [...accepted.entries()].map(([url, policy]) => ({ url, ...policy })),
  };
}

function findRootAdvisories(name, vulnerabilities, visiting) {
  if (visiting.has(name)) return [];
  const vulnerability = vulnerabilities[name];
  if (!vulnerability || !Array.isArray(vulnerability.via)) return [];

  const nextVisiting = new Set(visiting).add(name);
  return deduplicateRoots(
    vulnerability.via.flatMap((via) => {
      if (typeof via === 'string') {
        return findRootAdvisories(via, vulnerabilities, nextVisiting);
      }
      if (via && typeof via === 'object') {
        return [
          {
            dependency: via.dependency ?? via.name,
            title: via.title,
            url: via.url,
          },
        ];
      }
      return [];
    }),
  );
}

function deduplicateRoots(roots) {
  return [...new Map(roots.map((root) => [`${root.dependency}:${root.url}`, root])).values()];
}

function isExpired(expiresOn, now) {
  const endOfDayUtc = new Date(`${expiresOn}T23:59:59.999Z`);
  return Number.isNaN(endOfDayUtc.getTime()) || now > endOfDayUtc;
}

function run() {
  const result = spawnSync('npm', ['audit', '--omit=dev', '--json'], {
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
  });

  if (result.error) {
    console.error(`Unable to run npm audit: ${result.error.message}`);
    process.exit(1);
  }

  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    console.error('npm audit did not return valid JSON.');
    if (result.stderr) console.error(result.stderr.trim());
    process.exit(1);
  }

  const outcome = evaluateAuditReport(report);
  if (outcome.errors.length > 0) {
    console.error('Production dependency audit failed:');
    for (const error of outcome.errors) console.error(`- ${error}`);
    process.exit(1);
  }

  if (outcome.accepted.length === 0) {
    console.log('Production dependency audit passed with no high or critical findings.');
    return;
  }

  console.log('Production dependency audit passed with temporary accepted advisories:');
  for (const advisory of outcome.accepted) {
    console.log(`- ${advisory.url} (${advisory.dependency}), expires ${advisory.expiresOn}`);
    console.log(`  ${advisory.reason}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run();
}
