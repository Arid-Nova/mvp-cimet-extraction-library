'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { analyze } = require('../analyzer.cjs');
const { createResourceBudget } = require('../lib/source/resources.cjs');
const { discoverSourceFiles, MAX_FILE_BYTES } = require('../lib/source/discovery.cjs');

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cimet-resource-test-'));
  for (const [name, text] of Object.entries(files)) {
    const target = path.join(root, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, text);
  }
  t.after(() => {
    const target = path.resolve(root);
    assert.equal(path.dirname(target), path.resolve(os.tmpdir()));
    assert.ok(path.basename(target).startsWith('cimet-resource-test-'));
    fs.rmSync(target, { recursive: true, force: true });
  });
  return root;
}

const extract = (root, config = {}) => analyze({ root, kind: 'backend', name: 'bounded', config });
const limit = (code) => (error) =>
  error.code === code && error.name === 'AnalysisResourceLimitError';

test('aggregate source admission fails before any source file is opened or AST is created', (t) => {
  const root = fixture(t, { 'first.js': 'export const a=1;', 'second.ts': 'export const b=2;' });
  const resource = createResourceBudget({ maxSourceBytes: 20 });
  const originalOpen = fs.openSync;
  let opened = 0;
  fs.openSync = (...args) => {
    opened++;
    return originalOpen(...args);
  };
  try {
    assert.throws(
      () => discoverSourceFiles({ root, config: {}, diagnostics: [], resource }),
      limit('SOURCE_BYTE_LIMIT'),
    );
  } finally {
    fs.openSync = originalOpen;
  }
  assert.equal(opened, 0);
  assert.equal(resource.usage.astNodes, 0);
  assert.equal(resource.usage.files, 0);
});

test('source-file and directory-entry admission are separate bounded failures', (t) => {
  const root = fixture(t, { 'first.js': 'export const a=1;', 'second.js': 'export const b=2;' });
  assert.throws(() => extract(root, { maxFiles: 1 }), limit('SOURCE_FILE_LIMIT'));
  const assets = fixture(t, { 'one.txt': '', 'two.txt': '', 'three.txt': '' });
  assert.throws(() => extract(assets, { maxDirectoryEntries: 2 }), limit('SOURCE_ENTRY_LIMIT'));
});

test('an AST-node limit rejects the parsed file before retaining its bindings or snapshot', (t) => {
  const root = fixture(t, { 'file.js': 'export function calculate(value){return value+1;}' });
  const resource = createResourceBudget({ maxAstNodes: 2 });
  assert.throws(
    () => discoverSourceFiles({ root, config: {}, diagnostics: [], resource }),
    limit('AST_NODE_LIMIT'),
  );
  assert.equal(resource.usage.astNodes, 2);
  assert.equal(resource.usage.files, 0);
});

test('repository work and evidence-record limits abort rather than return a successful prefix', (t) => {
  const root = fixture(t, {
    'app.js':
      "const app=require('express')();app.get('/one',(req,res)=>res.end());app.post('/two',(req,res)=>res.end());",
  });
  assert.throws(() => extract(root, { maxWorkOperations: 2 }), limit('ANALYSIS_WORK_LIMIT'));
  assert.throws(() => extract(root, { maxRecords: 1 }), limit('ANALYSIS_RECORD_LIMIT'));
});

test('a growing file cannot bypass the aggregate source-read limit', (t) => {
  const root = fixture(t, { 'growing.js': 'export const actualSourceIsLarger=1;' });
  const originalStat = fs.statSync;
  fs.statSync = (filename, ...args) =>
    filename === path.join(root, 'growing.js')
      ? { ...originalStat(filename, ...args), size: 1 }
      : originalStat(filename, ...args);
  const resource = createResourceBudget({ maxSourceBytes: 2 });
  try {
    assert.throws(
      () => discoverSourceFiles({ root, config: {}, diagnostics: [], resource }),
      limit('SOURCE_BYTE_LIMIT'),
    );
  } finally {
    fs.statSync = originalStat;
  }
  assert.equal(resource.usage.astNodes, 0);
});

test('a file growing past the individual limit remains excluded with explicit diagnostics', (t) => {
  const root = fixture(t, { 'growing.js': ' '.repeat(MAX_FILE_BYTES + 1) });
  const originalStat = fs.statSync;
  fs.statSync = (filename, ...args) =>
    filename === path.join(root, 'growing.js')
      ? { ...originalStat(filename, ...args), size: 1 }
      : originalStat(filename, ...args);
  const diagnostics = [];
  try {
    const files = discoverSourceFiles({
      root,
      config: {},
      diagnostics,
      resource: createResourceBudget({}),
    });
    assert.equal(files.size, 0);
    assert.equal(diagnostics[0].code, 'FILE_TOO_LARGE');
  } finally {
    fs.statSync = originalStat;
  }
});

test('invalid trusted resource settings fail explicitly instead of disabling a limit', (t) => {
  const root = fixture(t, { 'file.js': 'export const value=1;' });
  for (const value of [0, -1, '100', Infinity, NaN, 1.5, 20_001]) {
    assert.throws(
      () => extract(root, { maxFiles: value }),
      /Adapter configuration has an invalid maxFiles/,
    );
  }
});

test('CLI emits a fixed resource code with no source, path, config value or target execution', (t) => {
  const root = fixture(t, {});
  const marker = path.join(root, 'MUST_NOT_RUN');
  fs.writeFileSync(
    path.join(root, 'SECRET_SOURCE.js'),
    `require('fs').writeFileSync(${JSON.stringify(marker)},'SECRET_PAYLOAD');`,
  );
  const config = path.join(root, 'operator.json');
  fs.writeFileSync(config, JSON.stringify({ maxSourceBytes: 1 }));
  const run = spawnSync(
    process.execPath,
    [
      path.resolve(__dirname, '../analyzer.cjs'),
      '--root',
      root,
      '--kind',
      'backend',
      '--name',
      'bounded',
      '--config',
      config,
    ],
    { encoding: 'utf8', timeout: 10_000 },
  );
  assert.equal(run.status, 1);
  assert.equal(run.stdout, '');
  assert.match(
    run.stderr,
    /^JavaScript analysis failed \[SOURCE_BYTE_LIMIT\]: The analysis exceeded its aggregate source-byte limit\.\r?\n$/,
  );
  assert.ok(!run.stderr.includes(root));
  assert.ok(!run.stderr.includes('SECRET'));
  assert.equal(fs.existsSync(marker), false);
});

test('hundreds of cross-file routes preserve imports, mounts, handler identities and reported admission counts', (t) => {
  for (const count of [10, 100, 300]) {
    const sources = {
      'app.js':
        "const app=require('express')();" +
        Array.from(
          { length: count },
          (_, index) => `app.use('/api${index}',require('./routes/r${index}'));`,
        ).join(''),
    };
    for (let index = 0; index < count; index++)
      sources[`routes/r${index}.js`] =
        `const router=require('express').Router();function handle(req,res){res.json({ok:true});}router.get('/items',handle);module.exports=router;`;
    const root = fixture(t, sources);
    const result = extract(root);
    assert.equal(result.endpoints.length, count);
    assert.equal(result.architecture.endpointHandlers.length, count);
    assert.equal(result.stats.resourceUsage.files, count + 1);
    assert.equal(
      result.stats.resourceUsage.sourceBytes,
      Object.values(sources).reduce((sum, value) => sum + Buffer.byteLength(value), 0),
    );
    assert.ok(result.stats.resourceUsage.astNodes <= result.stats.resourceLimits.maxAstNodes);
    assert.ok(
      result.stats.resourceUsage.workOperations <= result.stats.resourceLimits.maxWorkOperations,
    );
    assert.equal(new Set(result.endpoints.map((endpoint) => endpoint.id)).size, count);
    assert.ok(result.endpoints.some((endpoint) => endpoint.path === `/api${count - 1}/items`));
  }
});

test('a rejected scan does not poison a later invocation of the public API', (t) => {
  const root = fixture(t, {
    'app.js': "const app=require('express')();app.get('/ok',(req,res)=>res.end());",
  });
  assert.throws(() => extract(root, { maxAstNodes: 1 }), limit('AST_NODE_LIMIT'));
  const result = extract(root);
  assert.deepEqual(
    result.endpoints.map((endpoint) => endpoint.path),
    ['/ok'],
  );
  assert.equal(result.diagnostics.length, 0);
});
