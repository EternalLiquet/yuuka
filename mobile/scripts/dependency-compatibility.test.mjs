import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const require = createRequire(import.meta.url);
const { loadNycConfig, isLoading } = require('@istanbuljs/load-nyc-config');

async function fixture(t, files = {}) {
  const cwd = await mkdtemp(path.join(tmpdir(), 'yuuka-nyc-'));
  t.after(() => rm(cwd, { recursive: true, force: true }));
  for (const [name, contents] of Object.entries({ 'package.json': '{}', ...files })) {
    const filename = path.join(cwd, name);
    await mkdir(path.dirname(filename), { recursive: true });
    await writeFile(filename, contents);
  }
  return cwd;
}

test('NYC parser no longer installs the argparse 1 to sprintf-js dependency chain', async () => {
  const lock = JSON.parse(await readFile(new URL('../package-lock.json', import.meta.url), 'utf8'));
  assert.deepEqual(
    Object.keys(lock.packages).filter((name) => name.endsWith('/sprintf-js')),
    [],
  );
  const fromLoader = createRequire(require.resolve('@istanbuljs/load-nyc-config'));
  const fromYaml = createRequire(fromLoader.resolve('js-yaml'));
  const argparse = fromYaml('argparse/package.json');
  assert.equal(argparse.dependencies?.['sprintf-js'], undefined);
  assert.throws(() => fromYaml.resolve('sprintf-js'), { code: 'MODULE_NOT_FOUND' });
});

test('NYC without configuration retains its project cwd', async (t) => {
  const cwd = await fixture(t);
  assert.deepEqual(await loadNycConfig({ cwd }), { cwd });
  assert.equal(isLoading(), false);
});

test('NYC discovers JSON configuration and normalizes names and scalar arrays', async (t) => {
  const cwd = await fixture(t, {
    '.nycrc.json': JSON.stringify({
      'check-coverage': true,
      include: 'src/**/*.ts',
      exclude: ['**/*.test.ts'],
      extension: '.ts',
      require: './setup.cjs',
      lines: 85,
    }),
  });
  assert.deepEqual(await loadNycConfig({ cwd }), {
    cwd,
    checkCoverage: true,
    include: ['src/**/*.ts'],
    exclude: ['**/*.test.ts'],
    extension: ['.ts'],
    require: ['./setup.cjs'],
    lines: 85,
  });
});

for (const extension of ['yaml', 'yml']) {
  test(`NYC discovers ${extension} with anchors, merges, sequences and ordinary scalars`, async (t) => {
    const cwd = await fixture(t, {
      [`.nycrc.${extension}`]: `
defaults: &defaults
  check-coverage: true
  lines: 85
  branches: 80
<<: *defaults
include: ['src/**/*.ts', 'src/**/*.tsx']
exclude:
  - '**/*.test.ts'
extension: .ts
reporter: [text, lcov]
all: false
report-dir: 'coverage/008'
`,
    });
    assert.deepEqual(await loadNycConfig({ cwd }), {
      cwd,
      defaults: { 'check-coverage': true, lines: 85, branches: 80 },
      checkCoverage: true,
      lines: 85,
      branches: 80,
      include: ['src/**/*.ts', 'src/**/*.tsx'],
      exclude: ['**/*.test.ts'],
      extension: ['.ts'],
      reporter: ['text', 'lcov'],
      all: false,
      reportDir: 'coverage/008',
    });
  });
}

test('NYC preserves YAML to JSON extends resolution, relative cwd and precedence', async (t) => {
  const cwd = await fixture(t, {
    'package.json': JSON.stringify({ nyc: { lines: 70, branches: 75 } }),
    '.nycrc.yml': 'extends: ./config/base.json\nlines: 90\ninclude: src/**/*.ts\n',
    'config/base.json': JSON.stringify({ cwd: '..', lines: 85, 'check-coverage': true }),
  });
  assert.deepEqual(await loadNycConfig({ cwd }), {
    cwd,
    lines: 85,
    branches: 75,
    include: ['src/**/*.ts'],
    checkCoverage: true,
  });
});

test('NYC preserves JSON to YAML extends and multiple inherited configurations', async (t) => {
  const cwd = await fixture(t, {
    '.nycrc': JSON.stringify({ extends: ['./base.yml', './extra.json'] }),
    'base.yml': 'include: src/**/*.ts\nlines: 80\n',
    'extra.json': JSON.stringify({ lines: 85, extension: ['.ts', '.tsx'] }),
  });
  assert.deepEqual(await loadNycConfig({ cwd }), {
    cwd,
    include: ['src/**/*.ts'],
    lines: 85,
    extension: ['.ts', '.tsx'],
  });
});

test('NYC rejects malformed YAML and clears its loading state', async (t) => {
  const cwd = await fixture(t, { '.nycrc.yml': 'include: [unterminated\n' });
  await assert.rejects(loadNycConfig({ cwd }), { name: 'YAMLException' });
  assert.equal(isLoading(), false);
});

test('NYC rejects malformed JSON and clears its loading state', async (t) => {
  const cwd = await fixture(t, { '.nycrc.json': '{' });
  await assert.rejects(loadNycConfig({ cwd }), SyntaxError);
  assert.equal(isLoading(), false);
});

test('NYC rejects an explicitly requested missing file', async (t) => {
  const cwd = await fixture(t);
  await assert.rejects(
    loadNycConfig({ cwd, nycrcPath: 'missing.yml' }),
    /Requested configuration file missing.yml not found/,
  );
});

for (const [contents, expected] of [
  ['extends: 42\n', /invalid 'extends' option/],
  ['extends: ./missing.yml\n', /Could not resolve configuration file/],
  ['extends: .\/.nycrc.yml\n', /Circular extended configurations/],
]) {
  test(`NYC rejects invalid YAML inheritance: ${contents.trim()}`, async (t) => {
    const cwd = await fixture(t, { '.nycrc.yml': contents });
    await assert.rejects(loadNycConfig({ cwd }), expected);
  });
}
