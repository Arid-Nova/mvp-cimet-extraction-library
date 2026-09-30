import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { git, exportSnapshot, resolveRevision, safeRelativePath, allowedSource,
  prepareOutput, linkCandidates, validationCsv } = require('./lib.cjs');
const { loadConfig, parseArguments, changeCandidates } = require('./run.cjs');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cimet-study-test-'));
  // Cleanup is limited to the exact newly created temporary test directory.
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const repo = path.join(root, 'repo');
  fs.mkdirSync(repo);
  git(repo, ['init', '--quiet']);
  git(repo, ['config', 'user.name', 'Synthetic Test']);
  git(repo, ['config', 'user.email', 'synthetic@example.invalid']);
  const put = (name, value) => {
    const file = path.join(repo, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, value);
  };
  const commit = () => {
    git(repo, ['add', '-A']);
    git(repo, ['-c', 'core.hooksPath=/dev/null', 'commit', '--quiet', '-m', 'synthetic fixture']);
    return git(repo, ['rev-parse', 'HEAD']).trim();
  };
  return { root, repo, put, commit };
}

test('exports the pinned blobs while preserving dirty worktree, HEAD and index', t => {
  const f = fixture(t);
  f.put('src/routes.js', 'app.get("/before", handler);\n');
  f.put('src/view.jsx', 'const View = () => <div />;\n');
  f.put('package.json', '{"scripts":{"postinstall":"DO NOT RUN"}}');
  f.put('.env', 'SYNTHETIC_SECRET=excluded');
  f.put('logs/debug.js', 'excluded log');
  f.put('uploads/patient.js', 'excluded upload');
  f.put('node_modules/library/index.js', 'excluded dependency');
  f.put('dist/bundle.js', 'excluded generated bundle');
  const before = f.commit();
  f.put('src/routes.js', 'app.get("/after", handler);\n');
  const after = f.commit();
  f.put('src/routes.js', 'dirty staged source');
  git(f.repo, ['add', 'src/routes.js']);
  f.put('src/routes.js', 'dirty unstaged source');
  f.put('untracked.js', 'must not export');
  const status = git(f.repo, ['status', '--porcelain']);
  const index = git(f.repo, ['write-tree']);
  const destination = path.join(f.root, 'snapshot');
  const result = exportSnapshot(f.repo, before, destination);
  assert.equal(fs.readFileSync(path.join(destination, 'src/routes.js'), 'utf8'), 'app.get("/before", handler);\n');
  assert.deepEqual(result.files.map(file => file.path).sort(), ['package.json', 'src/routes.js', 'src/view.jsx']);
  assert.equal(result.omitted.nonSource, 5);
  assert.equal(resolveRevision(f.repo, { parentOf: after }), before);
  assert.equal(git(f.repo, ['rev-parse', 'HEAD']).trim(), after);
  assert.equal(git(f.repo, ['write-tree']), index);
  assert.equal(git(f.repo, ['status', '--porcelain']), status);
  assert.throws(() => exportSnapshot(f.repo, before, destination), /must not exist/);
});

test('skips Git symlinks and submodules instead of traversing their target', t => {
  const f = fixture(t);
  f.put('safe.js', 'const safe = true;');
  const base = f.commit();
  const oid = git(f.repo, ['hash-object', '-w', '--stdin'], { input: '../../outside/private.js' }).trim();
  git(f.repo, ['update-index', '--add', '--cacheinfo', `120000,${oid},linked.js`]);
  git(f.repo, ['update-index', '--add', '--cacheinfo', `160000,${base},submodule`]);
  git(f.repo, ['-c', 'core.hooksPath=/dev/null', 'commit', '--quiet', '-m', 'synthetic links']);
  const snapshot = exportSnapshot(f.repo, git(f.repo, ['rev-parse', 'HEAD']).trim(), path.join(f.root, 'snapshot'));
  assert.equal(snapshot.omitted.nonRegular, 2);
  assert.deepEqual(snapshot.files.map(file => file.path), ['safe.js']);
});

test('rejects unsafe paths, mutable/empty refs, empty configuration, and output in repositories', t => {
  const f = fixture(t);
  f.put('safe.js', 'const safe = true;');
  const commit = f.commit();
  for (const candidate of ['', '../escape.js', '/abs.js', 'C:/escape.js', 'a/../b.js', 'a\\b.js', 'nul.js']) {
    assert.equal(safeRelativePath(candidate), false, candidate);
  }
  assert.equal(safeRelativePath('src/components/view.jsx'), true);
  for (const candidate of ['.env.js', 'uploads/data.ts', 'logs/data.js', 'node_modules/x.js']) assert.equal(allowedSource(candidate), false);
  for (const ref of ['', 'HEAD', 'main', commit.slice(0, 8)]) assert.throws(() => resolveRevision(f.repo, ref), /full 40-character/);
  assert.throws(() => prepareOutput(path.join(f.repo, 'out'), [f.repo]), /outside/);
  assert.throws(() => prepareOutput(f.root, [f.repo]), /already exists/);
  const config = path.join(f.root, 'empty.json');
  fs.writeFileSync(config, JSON.stringify({ schemaVersion: 1, name: 'test', components: [] }));
  assert.throws(() => loadConfig(config), /components/);
  const baseConfig = { schemaVersion: 1, name: 'test', components: [
    { name: 'api', kind: 'backend', repo: f.repo, commit },
    { name: 'web', kind: 'frontend', repo: f.repo, commit },
  ] };
  for (const repositoryURL of ['https://user:password@example.invalid/repo', 'https://example.invalid/repo?token=x', 'file:///tmp/repo']) {
    baseConfig.components[0].repositoryURL = repositoryURL;
    fs.writeFileSync(config, JSON.stringify(baseConfig));
    assert.throws(() => loadConfig(config), /plain HTTP/);
  }
  baseConfig.components[0].repositoryURL = 'https://example.invalid/repo';
  fs.writeFileSync(config, JSON.stringify(baseConfig));
  assert.equal(loadConfig(config).components[0].repositoryURL, 'https://example.invalid/repo');
  assert.throws(() => parseArguments(['--output']), /options with values/);
});

test('rejects symlinked output parent that resolves inside an input repository', t => {
  const f = fixture(t);
  const link = path.join(f.root, 'alias');
  try { fs.symlinkSync(f.repo, link, process.platform === 'win32' ? 'junction' : 'dir'); }
  catch (error) { if (['EPERM', 'EACCES'].includes(error.code)) { t.skip('OS denies symlink creation'); return; } throw error; }
  assert.throws(() => prepareOutput(path.join(link, 'out'), [f.repo]), /outside/);
});

const endpoint = (id, method, route) => ({ id, method, path: route, file: 'routes.js', line: 1 });
const call = (id, method, route, extra = {}) => ({ id, method, path: route, file: 'view.jsx', line: 2,
  target: 'backend', resolution: route?.includes('{') ? 'template' : 'literal', ...extra });
const backend = endpoints => ({ component: { name: 'api', kind: 'backend' }, endpoints });
const frontend = calls => ({ component: { name: 'web', kind: 'frontend' }, calls });

test('candidate matching distinguishes exact, template, ambiguous and unresolved without inventing targets', () => {
  const candidates = linkCandidates([backend([
    endpoint('health', 'GET', '/health'), endpoint('item', 'GET', '/items/:id'),
    endpoint('specific', 'GET', '/items/latest'), endpoint('save', 'POST', '/items/:id'),
  ])], [frontend([
    call('a', 'GET', '/health'), call('b', 'GET', '/items/123?expand=yes'),
    call('c', 'GET', '/items/latest'), call('d', 'POST', '/items/{?}'),
    call('e', 'GET', null, { resolution: 'unresolved' }),
    call('f', 'GET', '/health', { target: 'external' }),
    call('g', 'DELETE', '/items/123'), call('h', 'POST', '/{computed}'),
  ])]);
  assert.deepEqual(candidates.summary, { exact: 1, template: 2, ambiguous: 1, unresolved: 4 });
  assert.equal(candidates.links[3].endpoints[0].id, 'save');
  assert.equal(candidates.links[5].reason, 'external_target');
  assert.equal(candidates.links[7].reason, 'wholly_dynamic_path');
  assert.equal(candidates.links[2].endpoints.length, 2);
});

test('duplicate matching endpoints remain ambiguous across backend applications', () => {
  const one = backend([endpoint('a', 'GET', '/health')]);
  const two = { ...backend([endpoint('b', 'GET', '/health')]), component: { name: 'other-api', kind: 'backend' } };
  const links = linkCandidates([one, two], [frontend([call('c', 'GET', '/health')])]);
  assert.equal(links.links[0].classification, 'ambiguous');
});

test('does not reinterpret parser-rejected wildcard routes as endpoint candidates', () => {
  const unsupported = { ...endpoint('unsupported', 'GET', '/uploads/*/:file'), normalizedPath: null };
  const links = linkCandidates([backend([unsupported])], [frontend([call('caller', 'GET', '/uploads/a/file')])]);
  assert.equal(links.links[0].classification, 'unresolved');
  assert.equal(links.links[0].reason, 'no_endpoint_candidate');
  assert.deepEqual(links.links[0].endpoints, []);
});

test('CSV leaves maintainer judgments blank and change links stay candidate evidence', () => {
  const candidates = linkCandidates([backend([endpoint('added', 'POST', '/new')])], [frontend([call('caller', 'POST', '/new')])]);
  const csv = validationCsv([{ name: 'after', candidates }]);
  const lines = csv.trim().split('\r\n');
  assert.equal(lines.length, 2);
  assert.ok(lines[1].endsWith(',"","","",""'));
  const changes = changeCandidates({ endpoints: { added: [endpoint('added', 'POST', '/new')] } }, { links: [] }, candidates);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].association, 'candidate_not_release_verified');
});
