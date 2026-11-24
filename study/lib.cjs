'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const EXCLUDED_DIRECTORIES = new Set([
  '.git', 'node_modules', 'vendor', 'dist', 'build', 'target', '.next',
  'coverage', 'upload', 'uploads', 'log', 'logs', 'tmp', 'temp',
]);
const MAX_FILE_BYTES = 4 * 1024 * 1024;
const MAX_SNAPSHOT_BYTES = 128 * 1024 * 1024;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: 'utf8', maxBuffer: 160 * 1024 * 1024,
    windowsHide: true, timeout: 180000, ...options,
  });
  // Do not echo subprocess stderr: target code or dependency tools may include secrets.
  if (result.error || result.status !== 0) {
    throw new Error(`${path.basename(command)} failed (${result.error?.code || result.status}); subprocess output suppressed`);
  }
  return result.stdout;
}

function git(repo, args, options = {}) {
  return run('git', ['-C', repo, ...args], {
    ...options,
    env: { ...process.env, ...options.env, GIT_NO_LAZY_FETCH: '1', GIT_TERMINAL_PROMPT: '0', GIT_NO_REPLACE_OBJECTS: '1' },
  });
}

function pinnedCommit(repo, value) {
  if (typeof value !== 'string' || !/^[a-f0-9]{40}$/i.test(value)) {
    throw new Error('Commits must be full 40-character Git SHAs');
  }
  const resolved = git(repo, ['rev-parse', '--verify', `${value}^{commit}`]).trim();
  if (resolved.toLowerCase() !== value.toLowerCase()) throw new Error('Commit did not resolve to the supplied immutable SHA');
  return resolved;
}

function resolveRevision(repo, value) {
  if (typeof value === 'string') return pinnedCommit(repo, value);
  if (!value || typeof value !== 'object' || !value.parentOf) throw new Error('Invalid revision');
  const parentOf = pinnedCommit(repo, value.parentOf);
  const parent = value.parent ?? 1;
  if (!Number.isInteger(parent) || parent < 1 || parent > 16) throw new Error('Invalid parent number');
  return pinnedCommit(repo, git(repo, ['rev-parse', '--verify', `${parentOf}^${parent}`]).trim());
}

function safeRelativePath(name) {
  if (!name || name.includes('\\') || path.posix.isAbsolute(name)) return false;
  return name.split('/').every(part => part && part !== '.' && part !== '..'
    && !/[<>:"|?*\x00-\x1f]/.test(part) && !/[. ]$/.test(part)
    && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part));
}

function allowedSource(name) {
  const parts = name.split('/');
  if (parts.some(part => EXCLUDED_DIRECTORIES.has(part.toLowerCase()))) return false;
  const basename = parts.at(-1).toLowerCase();
  if (basename.startsWith('.env') || /\.(?:log|pem|key)$/.test(basename)) return false;
  return basename === 'package.json' || /\.(?:js|jsx|mjs|cjs|ts|tsx)$/.test(basename);
}

function inside(parent, child) {
  const relative = path.relative(parent, child);
  return !relative || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

function canonicalProspectivePath(target) {
  let ancestor = path.resolve(target);
  const suffix = [];
  while (!fs.existsSync(ancestor)) {
    const parent = path.dirname(ancestor);
    if (parent === ancestor) throw new Error('Cannot resolve output parent');
    suffix.unshift(path.basename(ancestor));
    ancestor = parent;
  }
  return path.join(fs.realpathSync(ancestor), ...suffix);
}

function prepareOutput(target, forbiddenRoots) {
  const output = canonicalProspectivePath(target);
  if (forbiddenRoots.some(root => inside(fs.realpathSync(root), output))) {
    throw new Error('Output must be outside the runner repository and all input repositories');
  }
  if (fs.existsSync(output)) throw new Error('Output directory already exists; choose a fresh directory');
  fs.mkdirSync(output, { recursive: true });
  return output;
}

function writeJson(file, value) {
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
}

/** Materialize committed regular source blobs, without checkout, archive extraction, or hooks. */
function exportSnapshot(repo, commit, destination) {
  pinnedCommit(repo, commit);
  if (fs.existsSync(destination)) throw new Error('Snapshot destination must not exist');
  fs.mkdirSync(destination, { recursive: true });
  const tree = git(repo, ['ls-tree', '-r', '-z', '--long', '--full-tree', commit]);
  const selected = [];
  const omitted = { nonSource: 0, nonRegular: 0, unsafePath: 0, oversized: 0 };
  for (const entry of tree.split('\0').filter(Boolean)) {
    const match = /^(\d+) (\w+) ([a-f0-9]+) +([\d-]+|BAD)\t([\s\S]+)$/.exec(entry);
    if (!match) throw new Error('Unexpected Git tree record');
    const [, mode, type, oid, size, name] = match;
    if (!safeRelativePath(name)) { omitted.unsafePath++; continue; }
    if (!['100644', '100755'].includes(mode) || type !== 'blob') { omitted.nonRegular++; continue; }
    if (!allowedSource(name)) { omitted.nonSource++; continue; }
    if (size === 'BAD') throw new Error(`Required source blob ${oid} is missing locally; hydrate the authorized partial clone separately`);
    if (Number(size) > MAX_FILE_BYTES) { omitted.oversized++; continue; }
    selected.push({ path: name, gitBlob: oid, bytes: Number(size), mode });
  }
  const bytes = selected.reduce((total, file) => total + file.bytes, 0);
  if (bytes > MAX_SNAPSHOT_BYTES) throw new Error('Source snapshot exceeds the 128 MiB bound');
  if (selected.length) {
    const blobs = git(repo, ['cat-file', '--batch'], {
      input: selected.map(file => file.gitBlob).join('\n') + '\n', encoding: null,
    });
    let offset = 0;
    for (const file of selected) {
      const newline = blobs.indexOf(10, offset);
      if (newline < 0) throw new Error('Incomplete Git blob header');
      const header = blobs.subarray(offset, newline).toString('ascii');
      if (header !== `${file.gitBlob} blob ${file.bytes}`) throw new Error('Unexpected Git blob header');
      offset = newline + 1;
      const end = offset + file.bytes;
      if (blobs[end] !== 10) throw new Error('Incomplete Git blob body');
      const output = path.resolve(destination, ...file.path.split('/'));
      if (!inside(path.resolve(destination), output)) throw new Error('Snapshot path escaped destination');
      fs.mkdirSync(path.dirname(output), { recursive: true });
      fs.writeFileSync(output, blobs.subarray(offset, end), { flag: 'wx' });
      // Even tracked executable JavaScript is saved as data and never executed.
      offset = end + 1;
    }
    if (offset !== blobs.length) throw new Error('Unexpected trailing Git blob data');
  }
  return { commit, files: selected, fileCount: selected.length, bytes, omitted };
}

function normalizedRoute(item) {
  // Explicit null is parser evidence of an unsupported/unresolved route.
  // Only fixtures/legacy records with no normalizedPath field may use path.
  if (Object.hasOwn(item, 'normalizedPath') && item.normalizedPath === null) return null;
  const route = item.normalizedPath || item.path;
  if (typeof route !== 'string' || !route.startsWith('/')) return null;
  // A question mark inside a symbolic placeholder is not a query delimiter.
  let depth = 0;
  let end = route.length;
  for (let index = 0; index < route.length; index++) {
    if (route[index] === '{') depth++;
    else if (route[index] === '}') depth = Math.max(0, depth - 1);
    else if (!depth && /[?#]/.test(route[index])) { end = index; break; }
  }
  return route.slice(0, end).replace(/\/:[A-Za-z_][A-Za-z0-9_]*/g, '/{param}')
    .replace(/\{[^}]*\}/g, '{param}').replace(/\/+$/, '') || '/';
}

function dynamic(segment) { return /\{[^}]*\}|^:[^/]+|\*/.test(segment); }
function escapeRegex(value) { return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
function segmentMatches(pattern, literal) {
  let expression = '';
  let offset = 0;
  const tokens = /\{[^}]*\}|^:[^/]+|\*/g;
  for (const match of pattern.matchAll(tokens)) {
    expression += escapeRegex(pattern.slice(offset, match.index)) + '[^/]+';
    offset = match.index + match[0].length;
  }
  expression += escapeRegex(pattern.slice(offset));
  return new RegExp(`^${expression}$`).test(literal);
}

function routeCompatible(a, b) {
  const left = a.split('/');
  const right = b.split('/');
  if (left.length !== right.length) return false;
  return left.every((segment, index) => {
    const other = right[index];
    if (segment === other) return true;
    if (!dynamic(segment) && !dynamic(other)) return false;
    if (!dynamic(segment)) return segmentMatches(other, segment);
    if (!dynamic(other)) return segmentMatches(segment, other);
    // Two symbolic segments are only linked when their normalized shapes agree.
    return segment.replace(/\{[^}]*\}/g, '{param}') === other.replace(/\{[^}]*\}/g, '{param}');
  });
}

function reference(item, component) {
  return { id: item.id, component, file: item.file, line: item.line, endLine: item.endLine,
    method: item.method, path: item.path ?? null, normalizedPath: item.normalizedPath ?? null };
}

/** Syntactic candidates only; never promote a URL match into a runtime/service edge. */
function linkCandidates(backendSnapshots, frontendSnapshots) {
  const endpoints = backendSnapshots.flatMap(snapshot => (snapshot.endpoints || []).map(endpoint => ({
    endpoint, component: snapshot.component.name,
  })));
  const links = [];
  for (const snapshot of frontendSnapshots) for (const call of snapshot.calls || []) {
    const caller = reference(call, snapshot.component.name);
    const route = normalizedRoute(call);
    const method = (call.method || '').toUpperCase();
    let reason;
    if (call.target !== 'backend') reason = call.target === 'external' ? 'external_target' : 'unknown_target';
    else if (!route || call.resolution === 'unresolved') reason = 'unresolved_path';
    else if (!/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)$/.test(method)) reason = 'unresolved_method';
    else if (route.split('/').filter(Boolean).every(dynamic)) reason = 'wholly_dynamic_path';
    if (reason) { links.push({ caller, classification: 'unresolved', reason, endpoints: [] }); continue; }
    const matched = endpoints.filter(({ endpoint }) => {
      const endpointRoute = normalizedRoute(endpoint);
      return endpointRoute && ['ALL', method].includes((endpoint.method || '').toUpperCase())
        && routeCompatible(route, endpointRoute);
    });
    const classification = matched.length > 1 ? 'ambiguous' : !matched.length ? 'unresolved'
      : !route.split('/').some(dynamic) && !normalizedRoute(matched[0].endpoint).split('/').some(dynamic)
        && matched[0].endpoint.method.toUpperCase() === method ? 'exact' : 'template';
    links.push({ caller, classification,
      reason: matched.length > 1 ? 'multiple_endpoint_candidates' : !matched.length ? 'no_endpoint_candidate' : 'syntactic_method_path_match',
      endpoints: matched.map(({ endpoint, component }) => reference(endpoint, component)),
    });
  }
  return { schemaVersion: 1, claim: 'Unvalidated syntactic caller-endpoint candidates; not runtime or causal evidence',
    summary: Object.fromEntries(['exact', 'template', 'ambiguous', 'unresolved'].map(type => [type, links.filter(link => link.classification === type).length])), links };
}

function csvCell(value) {
  let result = String(value ?? '');
  if (/^[=+@-]/.test(result)) result = `'${result}`;
  return `"${result.replaceAll('"', '""')}"`;
}

function validationCsv(cohorts) {
  const columns = ['cohort', 'association', 'classification', 'reason', 'caller_component', 'caller_id',
    'caller_file', 'caller_line', 'method', 'caller_path', 'endpoint_component', 'endpoint_id',
    'endpoint_file', 'endpoint_line', 'endpoint_path', 'maintainer_link_valid',
    'maintainer_change_relevant', 'maintainer_evidence', 'maintainer_notes'];
  const lines = [columns.map(csvCell).join(',')];
  for (const { name, candidates } of cohorts) for (const link of candidates.links) {
    for (const endpoint of link.endpoints.length ? link.endpoints : [null]) {
      lines.push([name, 'candidate_not_release_verified', link.classification, link.reason,
        link.caller.component, link.caller.id, link.caller.file, link.caller.line,
        link.caller.method, link.caller.path, endpoint?.component, endpoint?.id,
        endpoint?.file, endpoint?.line, endpoint?.path, '', '', '', ''].map(csvCell).join(','));
    }
  }
  return lines.join('\r\n') + '\r\n';
}

module.exports = { run, git, pinnedCommit, resolveRevision, safeRelativePath, allowedSource,
  prepareOutput, exportSnapshot, writeJson, linkCandidates, validationCsv, routeCompatible };
