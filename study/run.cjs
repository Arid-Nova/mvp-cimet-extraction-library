#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {
  run, git, resolveRevision, prepareOutput, exportSnapshot, writeJson,
  linkCandidates, validationCsv,
} = require('./lib.cjs');

const TOOL_ROOT = path.resolve(__dirname, '..');
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const elapsed = start => Number(process.hrtime.bigint() - start) / 1e6;

function loadConfig(configFile) {
  const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
  if (config.schemaVersion !== 1 || typeof config.name !== 'string' || !config.name.trim()
      || !Array.isArray(config.components) || !config.components.length) {
    throw new Error('Config requires schemaVersion 1, a nonempty name, and components');
  }
  const seen = new Set();
  const components = config.components.map(component => {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(component.name || '') || seen.has(component.name.toLowerCase())) {
      throw new Error('Component names must be unique safe directory names');
    }
    seen.add(component.name.toLowerCase());
    if (!['backend', 'frontend'].includes(component.kind)) throw new Error('Invalid component kind');
    if (typeof component.repo !== 'string' || !component.repo.trim()) throw new Error('Repository path must not be empty');
    const repo = fs.realpathSync(path.resolve(path.dirname(configFile), component.repo));
    const commit = resolveRevision(repo, component.commit);
    let repositoryURL = null;
    if (component.repositoryURL !== undefined) {
      if (typeof component.repositoryURL !== 'string' || !component.repositoryURL) throw new Error('Repository URL must be a nonempty string');
      if (/^local:[a-zA-Z0-9._-]+$/.test(component.repositoryURL)) repositoryURL = component.repositoryURL;
      else {
        let url;
        try { url = new URL(component.repositoryURL); } catch { throw new Error('Invalid repository URL'); }
        if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
          throw new Error('Repository URL must be plain HTTP(S), without credentials, query, or fragment');
        }
        repositoryURL = url.href;
      }
    }
    const adapterConfig = component.adapterConfig
      ? path.resolve(path.dirname(configFile), component.adapterConfig) : null;
    if (adapterConfig && !fs.statSync(adapterConfig).isFile()) throw new Error('Missing adapter configuration');
    return { name: component.name, kind: component.kind, repo, commit, repositoryURL, adapterConfig };
  });
  if (!components.some(component => component.kind === 'backend')
      || !components.some(component => component.kind === 'frontend')) {
    throw new Error('Study requires at least one backend and one frontend component');
  }
  const cohortNames = new Set();
  const historicalCohorts = (config.historicalCohorts || []).map(cohort => {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(cohort.name || '') || cohortNames.has(cohort.name.toLowerCase())) {
      throw new Error('Historical cohort names must be unique safe directory names');
    }
    cohortNames.add(cohort.name.toLowerCase());
    if (!Array.isArray(cohort.components) || !cohort.components.length) throw new Error('Historical cohort is empty');
    const cohortComponents = new Set();
    const revisions = cohort.components.map(revision => {
      const component = components.find(item => item.name === revision.name);
      if (!component || cohortComponents.has(revision.name)) throw new Error('Unknown or duplicate historical component');
      cohortComponents.add(revision.name);
      return { component, before: resolveRevision(component.repo, revision.before), after: resolveRevision(component.repo, revision.after) };
    });
    return { name: cohort.name, association: 'candidate_not_release_verified', revisions };
  });
  if (config.historyCheckpoints !== undefined && !Array.isArray(config.historyCheckpoints)) {
    throw new Error('History checkpoints must be an array');
  }
  const checkpointNames = new Set();
  const previousCommits = new Map();
  let previousCutoff = -Infinity;
  const historyCheckpoints = (config.historyCheckpoints || []).map((checkpoint, index, all) => {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$/.test(checkpoint.name || '')
        || checkpointNames.has(checkpoint.name.toLowerCase())) throw new Error('Checkpoint names must be unique safe directory names');
    checkpointNames.add(checkpoint.name.toLowerCase());
    if (!Array.isArray(checkpoint.components) || checkpoint.components.length !== components.length) {
      throw new Error('Each checkpoint must pin every configured component exactly once');
    }
    const cutoff = checkpoint.cutoff ?? null;
    if (cutoff === null) {
      if (index !== all.length - 1) throw new Error('Only the last checkpoint may omit its cutoff');
    } else {
      if (typeof cutoff !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(cutoff)
          || !Number.isFinite(Date.parse(cutoff)) || Date.parse(cutoff) <= previousCutoff
          || new Date(cutoff).toISOString().replace('.000Z', 'Z') !== cutoff) {
        throw new Error('Checkpoint UTC cutoffs must be valid and strictly increasing');
      }
      previousCutoff = Date.parse(cutoff);
    }
    const seenComponents = new Set();
    const revisions = checkpoint.components.map(revision => {
      const component = components.find(item => item.name === revision.name);
      if (!component || seenComponents.has(revision.name)) throw new Error('Unknown or duplicate checkpoint component');
      seenComponents.add(revision.name);
      const commit = resolveRevision(component.repo, revision.commit);
      try {
        git(component.repo, ['merge-base', '--is-ancestor', commit, component.commit]);
        if (previousCommits.has(component.name)) git(component.repo,
          ['merge-base', '--is-ancestor', previousCommits.get(component.name), commit]);
      } catch { throw new Error('Checkpoint commits must follow ancestor order ending in the configured component lineage'); }
      previousCommits.set(component.name, commit);
      return { component, commit };
    });
    return { name: checkpoint.name, cutoff, association: 'candidate_not_release_verified', revisions };
  });
  return { name: config.name, association: 'candidate_not_release_verified', components, historicalCohorts,
    associationNote: typeof config.association === 'string' ? config.association : null,
    historyCheckpoints, historySelection: typeof config.historySelection === 'string' ? config.historySelection : null };
}

function parseArguments(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index++) {
    const name = argv[index];
    if (name === '--help') { options.help = true; continue; }
    if (!['--config', '--output', '--jar', '--adapter', '--java', '--node', '--java-heap-mib'].includes(name)
        || options[name.slice(2)] !== undefined || !argv[index + 1] || argv[index + 1].startsWith('--')) {
      throw new Error('Expected unique supported options with values');
    }
    options[name.slice(2)] = argv[++index];
  }
  return options;
}

function checkedSnapshot(file, component, commit) {
  const value = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (value.schemaVersion !== 1 || value.component?.name !== component.name
      || value.component?.kind !== component.kind || !Array.isArray(value.endpoints)
      || !Array.isArray(value.calls)) throw new Error('Unexpected JavaScript snapshot schema');
  for (const item of [...value.endpoints, ...value.calls]) {
    if (typeof item.id !== 'string' || typeof item.file !== 'string' || path.isAbsolute(item.file)
        || item.file.split(/[\\/]/).includes('..')) throw new Error('Snapshot lacks repository-relative provenance');
  }
  return value;
}

function groupedCounts(items, key) {
  const counts = {};
  for (const item of items || []) counts[item[key] ?? 'unknown'] = (counts[item[key] ?? 'unknown'] || 0) + 1;
  return counts;
}

function snapshotSummary(snapshot) {
  return {
    filesAnalyzed: snapshot.filesAnalyzed, endpoints: snapshot.endpoints.length,
    calls: snapshot.calls.length, callTargets: groupedCounts(snapshot.calls, 'target'),
    callResolutions: groupedCounts(snapshot.calls, 'resolution'),
    diagnostics: Array.isArray(snapshot.diagnostics) ? snapshot.diagnostics.length : null,
    parserStats: snapshot.stats ?? null,
    memoryScope: 'Parser Node process only when reported; JVM and combined peak RSS were not measured',
  };
}

/** Architecture commands consume saved JVM snapshots; they never parse target code again. */
function architectureArguments(files, name, output) {
  if (!Array.isArray(files) || !files.length) throw new Error('Architecture analysis requires saved snapshots');
  return ['architecture', ...files.flatMap(file => ['--snapshot', file]), '--name', name, '--output', output];
}

function architectureDeltaArguments(before, after, output) {
  return ['architecture-delta', '--old', before, '--new', after, '--output', output];
}

function checkedArchitecture(file) {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (data.schemaVersion !== 1 || data.analysis !== 'static-javascript-architecture'
      || !data.graphs || !Array.isArray(data.capabilities) || !Array.isArray(data.findings)) {
    throw new Error('Unexpected JVM architecture report schema');
  }
  return data;
}

function architectureSummary(data) {
  return {
    graphs: Object.fromEntries(Object.entries(data.graphs).map(([name, graph]) => [name,
      { status: graph.status, metrics: graph.metrics ?? null }])),
    http: data.http?.summary ?? null,
    findings: data.findings.length, findingKinds: groupedCounts(data.findings, 'kind'),
    capabilities: data.capabilities,
  };
}

/** Keep null/unsupported measurements visible rather than turning them into zero. */
function historyRows(checkpoint, scope, commits, report, data) {
  const common = { checkpoint: checkpoint.name, cutoff: checkpoint.cutoff,
    scope, commits, association: 'candidate_not_release_verified', report };
  const metrics = [];
  for (const [graph, value] of Object.entries(data.graphs)) {
    for (const [metric, measurement] of Object.entries(value.metrics || {})) {
      metrics.push({ ...common, graph, status: value.status, metric, value: measurement });
    }
    if (!Object.keys(value.metrics || {}).length) metrics.push({ ...common, graph, status: value.status, metric: null, value: null });
  }
  const capabilities = data.capabilities.map(item => ({ ...common, id: item.id, status: item.status, reason: item.reason ?? null }));
  return { metrics, capabilities };
}

function rowsCsv(rows, columns) {
  const cell = value => {
    let text = value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value);
    if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
    return `"${text.replace(/"/g, '""')}"`;
  };
  return [columns.map(cell).join(','), ...rows.map(row => columns.map(column => cell(row[column])).join(','))].join('\r\n') + '\r\n';
}

function adapterFiles(adapter) {
  return fs.readdirSync(path.dirname(adapter), { withFileTypes: true })
    .filter(item => /\.(?:js|cjs|mjs)$/.test(item.name) || ['package.json', 'package-lock.json'].includes(item.name))
    .sort((a, b) => a.name.localeCompare(b.name, 'en'))
    .map(item => {
      if (!item.isFile()) throw new Error('Adapter helper inputs must be regular files');
      return path.join(path.dirname(adapter), item.name);
    });
}

function changeCandidates(delta, beforeLinks, afterLinks) {
  const output = [];
  function add(type, endpoint, phase, links) {
    if (!endpoint?.id) return;
    for (const link of links.links) if (link.endpoints.some(candidate => candidate.id === endpoint.id)) {
      output.push({ change: type, phase, endpointId: endpoint.id, caller: link.caller,
        classification: link.classification, association: 'candidate_not_release_verified' });
    }
  }
  for (const endpoint of delta.endpoints?.added || []) add('added', endpoint, 'after', afterLinks);
  for (const endpoint of delta.endpoints?.removed || []) add('removed', endpoint, 'before', beforeLinks);
  for (const type of ['contractChanged', 'implementationChanged']) {
    for (const changed of delta.endpoints?.[type] || []) {
      add(type, changed.before, 'before', beforeLinks);
      add(type, changed.after, 'after', afterLinks);
    }
  }
  return output;
}

function executeStudy(options, { invokeJvm = run } = {}) {
  const start = process.hrtime.bigint();
  const configFile = path.resolve(options.config || path.join(__dirname, 'nutrosal.config.json'));
  const config = loadConfig(configFile);
  if (!options.output) throw new Error('--output must name a fresh directory outside all input/tool repositories');
  const jar = path.resolve(options.jar || path.join(TOOL_ROOT, 'target', 'cimet-javascript.jar'));
  const adapterArgument = path.resolve(options.adapter || path.join(TOOL_ROOT, 'adapter', 'javascript'));
  const adapter = fs.statSync(adapterArgument).isDirectory() ? path.join(adapterArgument, 'analyzer.cjs') : adapterArgument;
  if (!fs.statSync(jar).isFile() || !fs.statSync(adapter).isFile()) throw new Error('Build the JAR and install the adapter before running the study');
  const java = options.java || 'java';
  const node = options.node || process.execPath;
  const javaHeapMiB = Number(options['java-heap-mib'] || 256);
  if (!Number.isInteger(javaHeapMiB) || javaHeapMiB < 128 || javaHeapMiB > 8192) throw new Error('Java heap must be an integer from 128 to 8192 MiB');
  const toolFiles = { runnerSha256: __filename, librarySha256: path.join(__dirname, 'lib.cjs'),
    configSha256: configFile, jarSha256: jar, adapterSha256: adapter };
  const adapterInputs = adapterFiles(adapter);
  for (const file of adapterInputs) toolFiles[`adapterFileSha256:${path.basename(file)}`] = file;
  for (const component of config.components) {
    if (component.adapterConfig) toolFiles[`adapterConfigSha256:${component.name}`] = component.adapterConfig;
  }
  const inputHashes = Object.fromEntries(Object.entries(toolFiles).map(([key, file]) => [key, hash(file)]));
  const runnerCommit = git(TOOL_ROOT, ['rev-parse', 'HEAD']).trim();
  const runnerWorkingTreeDirty = Boolean(git(TOOL_ROOT, ['status', '--porcelain']).trim());
  const output = prepareOutput(options.output, [TOOL_ROOT, ...config.components.map(component => component.repo)]);
  const sourceDir = path.join(output, 'source');
  const resultsDir = path.join(output, 'results');
  fs.mkdirSync(sourceDir); fs.mkdirSync(resultsDir);
  const timing = [];
  const cache = new Map();
  const architectureCache = new Map();
  const cohorts = [];
  const manifests = [];
  function timed(label, action) {
    const operationStart = process.hrtime.bigint();
    const result = action();
    timing.push({ operation: label, elapsedMs: elapsed(operationStart) });
    return result;
  }
  function jvm(label, args) {
    return timed(label, () => invokeJvm(java, [`-Xmx${javaHeapMiB}m`, '-jar', jar, ...args], { timeout: 600000 }));
  }
  function architecture(name, snapshots) {
    const key = snapshots.map(item => item.file).sort().join('\n');
    if (architectureCache.has(key)) return architectureCache.get(key);
    const file = path.join(resultsDir, `${name}.architecture.json`);
    // Keep system identity stable across revisions; checkpoint labels belong in filenames/provenance.
    const systemName = snapshots.length === 1 ? snapshots[0].data.component.name : config.name;
    jvm(`architecture:${name}`, architectureArguments(snapshots.map(item => item.file), systemName, file));
    const data = checkedArchitecture(file);
    const result = { file, report: `results/${path.basename(file)}`, data, summary: architectureSummary(data) };
    architectureCache.set(key, result);
    return result;
  }
  function architectureDelta(name, before, after) {
    const file = path.join(resultsDir, `${name}.architecture-delta.json`);
    jvm(`architecture-delta:${name}`, architectureDeltaArguments(before.file, after.file, file));
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (data.schemaVersion !== 1 || data.analysis !== 'static-javascript-architecture-delta') {
      throw new Error('Unexpected JVM architecture delta schema');
    }
    return { file: `results/${path.basename(file)}`, summary: data.summary ?? null };
  }
  function snapshot(component, commit) {
    const key = `${component.name}-${commit}`;
    if (cache.has(key)) return cache.get(key);
    const sourceRoot = path.join(sourceDir, key);
    const manifest = timed(`export:${key}`, () => exportSnapshot(component.repo, commit, sourceRoot));
    const manifestFile = `${key}.source-manifest.json`;
    writeJson(path.join(resultsDir, manifestFile), { component: component.name, ...manifest });
    const snapshotFile = path.join(resultsDir, `${key}.snapshot.json`);
    const args = ['snapshot', '--root', sourceRoot, '--kind', component.kind, '--name', component.name,
      '--adapter', path.dirname(adapter), '--output', snapshotFile, '--commit', commit, '--node', node];
    if (component.adapterConfig) args.push('--config', component.adapterConfig);
    if (component.repositoryURL) args.push('--repo-url', component.repositoryURL);
    jvm(`snapshot:${key}`, args);
    const data = checkedSnapshot(snapshotFile, component, commit);
    const item = { component: component.name, kind: component.kind, commit,
      committedAt: git(component.repo, ['show', '-s', '--format=%cI', commit]).trim(), repositoryURL: component.repositoryURL,
      source: `source/${key}`, manifest: `results/${manifestFile}`,
      snapshot: `results/${path.basename(snapshotFile)}`, summary: snapshotSummary(data),
      adapterConfigSha256: component.adapterConfig ? hash(component.adapterConfig) : null };
    manifests.push(item);
    const result = { data, file: snapshotFile, summary: item };
    result.architecture = architecture(key, [result]);
    item.architecture = { file: result.architecture.report, summary: result.architecture.summary };
    cache.set(key, result);
    return result;
  }
  function cohort(name, snapshots) {
    const data = snapshots.map(snapshot => snapshot.data);
    const candidates = linkCandidates(data.filter(item => item.component.kind === 'backend'),
      data.filter(item => item.component.kind === 'frontend'));
    writeJson(path.join(resultsDir, `${name}.candidate-links.json`), candidates);
    const irFile = path.join(resultsDir, `${name}.native-ir.json`);
    jvm(`native-ir:${name}`, ['ir', ...snapshots.flatMap(snapshot => ['--snapshot', snapshot.file]),
      '--name', `${config.name}-${name}`, '--output', irFile]);
    const analysis = architecture(name, snapshots);
    const record = { name, candidates, architecture: analysis };
    cohorts.push(record);
    return record;
  }
  const current = config.components.map(component => snapshot(component, component.commit));
  cohort('current', current);
  const historical = [];
  for (const group of config.historicalCohorts) {
    const before = group.revisions.map(item => snapshot(item.component, item.before));
    const after = group.revisions.map(item => snapshot(item.component, item.after));
    const beforeCohort = cohort(`${group.name}-before`, before);
    const afterCohort = cohort(`${group.name}-after`, after);
    const cohortArchitectureDelta = architectureDelta(group.name, beforeCohort.architecture, afterCohort.architecture);
    const changes = [];
    const deltas = [];
    group.revisions.forEach((revision, index) => {
      const deltaFile = path.join(resultsDir, `${group.name}-${revision.component.name}.delta.json`);
      jvm(`delta:${group.name}:${revision.component.name}`, ['delta', '--old', before[index].file,
        '--new', after[index].file, '--output', deltaFile]);
      const delta = JSON.parse(fs.readFileSync(deltaFile, 'utf8'));
      deltas.push({ component: revision.component.name, before: revision.before, after: revision.after,
        file: `results/${path.basename(deltaFile)}`, summary: delta.summary ?? null,
        architectureDelta: architectureDelta(`${group.name}-${revision.component.name}`, before[index].architecture, after[index].architecture) });
      changes.push(...changeCandidates(delta, beforeCohort.candidates, afterCohort.candidates));
    });
    const changeFile = `${group.name}.candidate-change-links.json`;
    writeJson(path.join(resultsDir, changeFile), { schemaVersion: 1,
      association: 'candidate_not_release_verified', claim: 'Endpoint-change/caller candidates, not observed regressions or causal impact', changes });
    historical.push({ name: group.name, association: 'candidate_not_release_verified', deltas,
      architectureDelta: cohortArchitectureDelta,
      candidateChangeLinks: changes.length, candidateChangeLinksFile: `results/${changeFile}` });
  }
  const history = [];
  const trendMetrics = [];
  const trendCapabilities = [];
  let previousCheckpoint = null;
  for (const checkpoint of config.historyCheckpoints) {
    const snapshots = checkpoint.revisions.map(item => snapshot(item.component, item.commit));
    const record = cohort(`history-${checkpoint.name}`, snapshots);
    const commits = Object.fromEntries(snapshots.map(item => [item.summary.component,
      { commit: item.summary.commit, committedAt: item.summary.committedAt }]));
    const checkpointRecord = { name: checkpoint.name, cutoff: checkpoint.cutoff,
      association: 'candidate_not_release_verified', commits, cohort: record.name,
      architecture: { file: record.architecture.report, summary: record.architecture.summary } };
    const analyses = [{ scope: 'combined-source-cohort', commits, architecture: record.architecture },
      ...snapshots.map(item => ({ scope: item.summary.component,
        commits: { [item.summary.component]: commits[item.summary.component] }, architecture: item.architecture }))];
    for (const analysis of analyses) {
      const rows = historyRows(checkpoint, analysis.scope, analysis.commits, analysis.architecture.report, analysis.architecture.data);
      trendMetrics.push(...rows.metrics); trendCapabilities.push(...rows.capabilities);
    }
    if (previousCheckpoint) {
      const deltaName = `history-${previousCheckpoint.checkpoint.name}-to-${checkpoint.name}`;
      checkpointRecord.fromPrevious = { before: previousCheckpoint.checkpoint.name,
        architectureDelta: architectureDelta(deltaName, previousCheckpoint.record.architecture, record.architecture),
        components: snapshots.map(item => {
          const before = previousCheckpoint.snapshots.find(old => old.summary.component === item.summary.component);
          return { component: item.summary.component, before: before.summary.commit, after: item.summary.commit,
            architectureDelta: architectureDelta(`${deltaName}-${item.summary.component}`, before.architecture, item.architecture) };
        }) };
    }
    history.push(checkpointRecord);
    previousCheckpoint = { checkpoint, record, snapshots };
  }
  if (history.length) {
    const evidence = { schemaVersion: 1, association: 'candidate_not_release_verified',
      selection: config.historySelection, checkpoints: history, metrics: trendMetrics, capabilities: trendCapabilities };
    writeJson(path.join(resultsDir, 'history-trends.json'), evidence);
    fs.writeFileSync(path.join(resultsDir, 'history-metrics.csv'), rowsCsv(trendMetrics,
      ['checkpoint', 'cutoff', 'scope', 'commits', 'association', 'graph', 'status', 'metric', 'value', 'report']), { flag: 'wx' });
    fs.writeFileSync(path.join(resultsDir, 'history-capabilities.csv'), rowsCsv(trendCapabilities,
      ['checkpoint', 'cutoff', 'scope', 'commits', 'association', 'id', 'status', 'reason', 'report']), { flag: 'wx' });
  }
  fs.writeFileSync(path.join(resultsDir, 'maintainer-validation.csv'), validationCsv(cohorts), { flag: 'wx' });
  if (JSON.stringify(adapterFiles(adapter)) !== JSON.stringify(adapterInputs)) {
    throw new Error('Adapter helper inventory changed during the run; choose a fresh output and rerun');
  }
  for (const [key, file] of Object.entries(toolFiles)) {
    if (hash(file) !== inputHashes[key]) throw new Error(`Study tool/config changed during the run (${key}); choose a fresh output and rerun`);
  }
  if (git(TOOL_ROOT, ['rev-parse', 'HEAD']).trim() !== runnerCommit) throw new Error('Runner Git revision changed during the run; rerun with a stable tool checkout');
  const manifest = {
    schemaVersion: 1, name: config.name, completedAt: new Date().toISOString(),
    association: 'candidate_not_release_verified', associationNote: config.associationNote,
    tools: { node: process.version, javaMaxHeapMiB: javaHeapMiB, runnerCommit,
      runnerWorkingTreeDirty, ...inputHashes, inputHashesStableThroughRun: true },
    sourcePolicy: { include: ['*.js', '*.jsx', '*.mjs', '*.cjs', '*.ts', '*.tsx', 'package.json'],
      excluded: ['symlinks', 'submodules', '.env*', 'logs', 'uploads', 'dependencies', 'build outputs'],
      gitReadsOnly: true, targetCodeExecuted: false },
    snapshots: manifests, cohorts: cohorts.map(item => ({ name: item.name, candidateLinks: item.candidates.summary,
      architecture: { file: item.architecture.report, summary: item.architecture.summary } })),
    historical, history, historySelection: config.historySelection,
    historyTrends: history.length ? { json: 'results/history-trends.json', metricsCsv: 'results/history-metrics.csv',
      capabilitiesCsv: 'results/history-capabilities.csv' } : null,
    timing, elapsedMs: elapsed(start),
    combinedPeakRssKiB: null,
    limitations: ['Counts are static extraction evidence, not accuracy or runtime reachability.',
      'Cross-repository cohorts require maintainer validation; they are not verified releases.',
      'Calendar checkpoints sample each component history; adjacent observations do not isolate one change or causal effect.',
      'Architecture capabilities and graph scopes must be read with their reported status and limitations.',
      'Candidate links compare HTTP method/path syntax, not authorization, payload, or response contracts.',
      'One backend component represents one application boundary, not one service per endpoint.',
      'Combined JVM/Node peak memory is not measured. Parser-only memory is retained when reported.'],
  };
  writeJson(path.join(output, 'manifest.json'), manifest);
  fs.writeFileSync(path.join(output, 'LOCAL-OUTPUT.txt'),
    'Local private source snapshots and derived study evidence. Source is in source/; results contain repository-relative provenance.\n'
    + 'Keep this directory outside public repositories. Maintainer judgments are intentionally blank.\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, snapshots: manifests.length, historicalCohorts: historical.length, historyCheckpoints: history.length,
    currentCandidates: cohorts[0].candidates.summary, elapsedMs: manifest.elapsedMs }, null, 2));
  return manifest;
}

if (require.main === module) {
  try {
    const options = parseArguments(process.argv.slice(2));
    if (options.help) console.log('node study/run.cjs --config study/nutrosal.config.json --output /fresh/local/output [--jar target/cimet-javascript.jar] [--adapter adapter/javascript] [--java java] [--node node] [--java-heap-mib 256]');
    else executeStudy(options);
  } catch (error) {
    // Do not echo raw JSON parse errors: a local config may contain sensitive values.
    const safe = error instanceof SyntaxError ? 'Invalid JSON input' : error.code ? `Filesystem/tool error (${error.code})` : error.message;
    console.error(`Study failed: ${safe}`);
    process.exitCode = 1;
  }
}

module.exports = { loadConfig, parseArguments, executeStudy, changeCandidates,
  architectureArguments, architectureDeltaArguments, architectureSummary, historyRows, rowsCsv, adapterFiles };
