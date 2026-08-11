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
const approvedImageSizePaths = new Set(['yuuka-mobile > expo > @expo/metro > metro > image-size']);
const npmSpawnOptions = {
  encoding: 'utf8',
  maxBuffer: 10 * 1024 * 1024,
};

export function createNpmInvocation(
  npmArguments,
  {
    platform = process.platform,
    npmExecPath = process.env.npm_execpath,
    nodeExecPath = process.execPath,
    comSpec = process.env.ComSpec,
  } = {},
) {
  const args = [...npmArguments];
  if (typeof npmExecPath === 'string' && npmExecPath.trim() !== '') {
    return {
      command: nodeExecPath,
      args: [npmExecPath, ...args],
    };
  }

  if (platform === 'win32') {
    return {
      command: comSpec || 'cmd.exe',
      args: ['/d', '/s', '/c', 'npm.cmd', ...args],
    };
  }

  return { command: 'npm', args };
}

export function invokeNpm(
  npmArguments,
  {
    spawn = spawnSync,
    platform = process.platform,
    npmExecPath = process.env.npm_execpath,
    nodeExecPath = process.execPath,
    comSpec = process.env.ComSpec,
  } = {},
) {
  const invocation = createNpmInvocation(npmArguments, {
    platform,
    npmExecPath,
    nodeExecPath,
    comSpec,
  });
  return spawn(invocation.command, invocation.args, npmSpawnOptions);
}

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

export function evaluateDependencyTreeInspection(result) {
  if (result.error) {
    return {
      errors: [`Unable to inspect production image-size paths: ${result.error.message}`],
      paths: [],
    };
  }
  if (result.status !== 0) {
    const detail = result.stderr?.trim() || `npm ls exited with status ${result.status}.`;
    return { errors: [`Unable to inspect production image-size paths: ${detail}`], paths: [] };
  }

  let tree;
  try {
    tree = JSON.parse(result.stdout);
  } catch {
    return { errors: ['Production image-size dependency tree is not valid JSON.'], paths: [] };
  }

  return evaluateImageSizeDependencyTree(tree);
}

export function evaluateImageSizeDependencyTree(tree) {
  if (!isRecord(tree) || typeof tree.name !== 'string' || !isRecord(tree.dependencies)) {
    return {
      errors: ['Production image-size dependency tree has an unsupported shape.'],
      paths: [],
    };
  }

  const paths = [];
  const malformedPaths = [];
  collectImageSizePaths(tree.dependencies, [tree.name], paths, malformedPaths);

  if (malformedPaths.length > 0) {
    return {
      errors: malformedPaths.map(
        (path) => `Production image-size dependency tree is malformed at: ${formatPath(path)}.`,
      ),
      paths,
    };
  }
  if (paths.length === 0) {
    return { errors: ['No production dependency path to image-size was found.'], paths: [] };
  }

  const errors = paths
    .map(formatPath)
    .filter((path) => !approvedImageSizePaths.has(path))
    .map(
      (path) =>
        `Unexpected production dependency path to image-size: ${path}. ` +
        `Approved path: ${[...approvedImageSizePaths].join(', ')}.`,
    );

  return { errors, paths: paths.map(formatPath) };
}

function collectImageSizePaths(dependencies, parentPath, paths, malformedPaths) {
  for (const [dependencyName, dependency] of Object.entries(dependencies)) {
    const path = [...parentPath, dependencyName];
    if (!isRecord(dependency) || typeof dependency.version !== 'string') {
      malformedPaths.push(path);
      continue;
    }
    if (dependencyName === 'image-size') {
      paths.push(path);
      continue;
    }
    if (dependency.dependencies === undefined) continue;
    if (!isRecord(dependency.dependencies)) {
      malformedPaths.push(path);
      continue;
    }
    collectImageSizePaths(dependency.dependencies, path, paths, malformedPaths);
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function formatPath(path) {
  return path.join(' > ');
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

export function runProductionAudit({
  invokeNpmCommand = invokeNpm,
  now = new Date(),
  output = console,
} = {}) {
  const result = invokeNpmCommand(['audit', '--omit=dev', '--json']);

  if (result.error) {
    output.error(`Unable to run npm audit: ${result.error.message}`);
    return 1;
  }

  let report;
  try {
    report = JSON.parse(result.stdout);
  } catch {
    output.error('npm audit did not return valid JSON.');
    if (result.stderr) output.error(result.stderr.trim());
    return 1;
  }

  const outcome = evaluateAuditReport(report, now);
  if (outcome.errors.length > 0) {
    output.error('Production dependency audit failed:');
    for (const error of outcome.errors) output.error(`- ${error}`);
    return 1;
  }

  if (outcome.accepted.length === 0) {
    output.log('Production dependency audit passed with no high or critical findings.');
    return 0;
  }

  const dependencyTreeResult = invokeNpmCommand([
    'ls',
    'image-size',
    '--omit=dev',
    '--all',
    '--json',
  ]);
  const dependencyTreeOutcome = evaluateDependencyTreeInspection(dependencyTreeResult);
  if (dependencyTreeOutcome.errors.length > 0) {
    output.error('Production dependency audit failed:');
    for (const error of dependencyTreeOutcome.errors) output.error(`- ${error}`);
    return 1;
  }

  output.log('Production dependency audit passed with temporary accepted advisories:');
  for (const advisory of outcome.accepted) {
    output.log(`- ${advisory.url} (${advisory.dependency}), expires ${advisory.expiresOn}`);
    output.log(`  ${advisory.reason}`);
  }
  output.log('Approved production dependency paths:');
  for (const path of dependencyTreeOutcome.paths) output.log(`- ${path}`);
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = runProductionAudit();
}
