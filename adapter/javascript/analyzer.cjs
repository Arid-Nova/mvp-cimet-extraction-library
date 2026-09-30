#!/usr/bin/env node
'use strict';

// Only this pinned parser is loaded. Target modules, package scripts, tsconfig plugins,
// .env files, and dependencies are never executed or loaded by this adapter.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const ts = require('typescript');
const { buildArchitecture } = require('./architecture.cjs');
const HTTP = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'all']);
const REQUEST_METHODS = new Set([...HTTP].filter(method => method !== 'all'));
const IGNORED = new Set(['.git', 'node_modules', 'vendor', 'dist', 'build', 'coverage', '.next', 'target', 'uploads', 'public', 'test', 'tests', '__tests__', '__mocks__']);
const EXTENSIONS = ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs'];
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const slash = value => value.split(path.sep).join('/');
const literal = node => node && ts.isStringLiteralLike(node) ? node.text : null;
const isFunction = node => ts.isFunctionLike(node) && !!node.body;
const memberName = node => node && (ts.isIdentifier(node) || ts.isStringLiteralLike(node)) ? node.text : null;
function dotted(node) {
  if (!node) return '';
  if (ts.isIdentifier(node)) return node.text;
  if (node.kind === ts.SyntaxKind.ThisKeyword) return 'this';
  if (ts.isMetaProperty(node)) return 'import.meta';
  if (ts.isPropertyAccessExpression(node)) return `${dotted(node.expression)}.${node.name.text}`;
  return '';
}
function unwrap(node) {
  while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isNonNullExpression(node) || ts.isTypeAssertionExpression(node) || ts.isSatisfiesExpression(node))) node = node.expression;
  return node;
}
function semanticHash(node) {
  if (!node) return hash('unresolved');
  const scanner = ts.createScanner(ts.ScriptTarget.Latest, true, ts.LanguageVariant.JSX, node.getText());
  const output = [];
  for (let token = scanner.scan(); token !== ts.SyntaxKind.EndOfFileToken; token = scanner.scan()) output.push(`${token}:${scanner.getTokenText()}`);
  const shape = [];
  function visit(current) {
    shape.push(`(${current.kind}`);
    ts.forEachChild(current, visit);
    shape.push(')');
  }
  visit(node);
  return hash(JSON.stringify([output, shape]));
}

function analyze({ root, kind, name, config = {} }) {
  const started = performance.now();
  if (!path.isAbsolute(root) || !['backend', 'frontend'].includes(kind) || typeof name !== 'string' || !name.trim()) throw new Error('Provide absolute root, backend|frontend kind, and a component name.');
  root = fs.realpathSync(root);
  if (!fs.statSync(root).isDirectory()) throw new Error('The analysis root must be a directory.');
  const diagnostics = [];
  const files = new Map();
  const scopes = new WeakMap();
  const forcedRoles = new Map();
  const roles = new Map();
  const activeRoles = new Set();
  const routers = new Map();
  const endpointCandidates = [];
  const calls = [];
  const mounts = [];
  const consignLoads = [];
  const endpointHandlerNodes = new WeakMap();
  const wrappers = config.wrappers || [
    { module: 'server_requests', kind: 'server' },
    { module: 'dataUtility', kind: 'method-first', relativeTarget: 'backend', methodPolicy: 'get-or-post' },
    { module: 'dataUtilityImage', kind: 'method-first' },
  ];
  const backendEnv = new Set(config.backendEnv || ['VITE_NUTROSAL_BE', 'VITE_LOCAL_URL']);
  const backendOrigins = new Set((config.backendOrigins || []).map(value => new URL(value).origin));
  if (!Array.isArray(wrappers) || wrappers.some(w => typeof w.module !== 'string' || !['server', 'method-first'].includes(w.kind))) throw new Error('Invalid wrapper configuration.');
  const fileLimit = config.maxFiles || 20_000;
  const excluded = new Set([...IGNORED, ...(config.excludeDirectories || [])]);
  const relative = absolute => slash(path.relative(root, absolute));
  const inside = absolute => { const rel = path.relative(root, absolute); return rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel); };
  function diag(file, node, code, message) {
    diagnostics.push({ file: file?.rel || '', line: node ? file.sf.getLineAndCharacterOfPosition(node.getStart(file.sf)).line + 1 : 1, code, message });
  }
  function discover(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      const absolute = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) { diagnostics.push({ file: relative(absolute), line: 1, code: 'SYMLINK_SKIPPED', message: 'Symbolic links are not followed.' }); continue; }
      if (entry.isDirectory()) { if (!excluded.has(entry.name) && !entry.name.startsWith('.')) discover(absolute); continue; }
      if (!EXTENSIONS.includes(path.extname(entry.name)) || /\.(?:test|spec|d)\.[cm]?[jt]sx?$/.test(entry.name) || entry.name.startsWith('.')) continue;
      if (files.size >= fileLimit) throw new Error('Source file count exceeds the configured limit.');
      if (fs.statSync(absolute).size > 5 * 1024 * 1024) { diagnostics.push({ file: relative(absolute), line: 1, code: 'FILE_TOO_LARGE', message: 'Source file exceeds the 5 MiB parser limit.' }); continue; }
      const sf = ts.createSourceFile(absolute, fs.readFileSync(absolute, 'utf8'), ts.ScriptTarget.Latest, true, /\.tsx$/.test(entry.name) ? ts.ScriptKind.TSX : /\.(?:ts|mts|cts)$/.test(entry.name) ? ts.ScriptKind.TS : ts.ScriptKind.JSX);
      const file = { absolute, rel: relative(absolute), sf, exports: new Map(), bindings: [], assignments: [], calls: [], bad: sf.parseDiagnostics.length > 0 };
      files.set(absolute, file);
      for (const issue of sf.parseDiagnostics) diagnostics.push({ file: file.rel, line: sf.getLineAndCharacterOfPosition(issue.start || 0).line + 1, code: `PARSE_${issue.code}`, message: 'Parser reported invalid or unsupported syntax; this file is excluded from extraction.' });
    }
  }
  discover(root);

  function binding(scope, id, node, extra = {}) {
    if (!id) return;
    const found = { name: id, node, scope, file: scope.file, ...extra };
    scope.values.set(id, found); scope.file.bindings.push(found); return found;
  }
  function declarePattern(scope, pattern, node, extra) {
    if (ts.isIdentifier(pattern)) return binding(scope, pattern.text, node, extra);
    if (ts.isObjectBindingPattern(pattern)) for (const element of pattern.elements) if (ts.isIdentifier(element.name)) binding(scope, element.name.text, node, { ...extra, selector: memberName(element.propertyName || element.name) });
  }
  function visit(file, node, scope) {
    scopes.set(node, scope);
    if (ts.isFunctionDeclaration(node) && node.name) binding(scope, node.name.text, node, { init: node });
    if (isFunction(node)) {
      scope = { parent: scope, values: new Map(), file, node };
      scopes.set(node, scope);
      for (const parameter of node.parameters || []) declarePattern(scope, parameter.name, parameter, { parameter: true });
    } else if (ts.isBlock(node)) { scope = { parent: scope, values: new Map(), file, node }; scopes.set(node, scope); }
    if (ts.isImportDeclaration(node) && literal(node.moduleSpecifier)) {
      const source = node.moduleSpecifier.text;
      if (node.importClause?.name) binding(scope, node.importClause.name.text, node, { module: source, imported: 'default' });
      const named = node.importClause?.namedBindings;
      if (named && ts.isNamespaceImport(named)) binding(scope, named.name.text, node, { module: source, imported: '*' });
      if (named && ts.isNamedImports(named)) for (const element of named.elements) binding(scope, element.name.text, node, { module: source, imported: memberName(element.propertyName || element.name) });
    }
    if (ts.isVariableDeclaration(node)) declarePattern(scope, node.name, node, { init: node.initializer });
    if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference) && literal(node.moduleReference.expression)) binding(scope, node.name.text, node, { module: literal(node.moduleReference.expression), imported: '*', typeOnly: !!node.isTypeOnly });
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      const key = dotted(node.left);
      if (key === 'module.exports') file.exports.set('default', node.right);
      else if (key.startsWith('exports.')) file.exports.set(key.substring(8), node.right);
      else if (key.startsWith('module.exports.')) file.exports.set(key.substring(15), node.right);
      else if (ts.isIdentifier(node.left)) file.assignments.push(node.left);
    }
    if (ts.isExportAssignment(node)) file.exports.set('default', node.expression);
    if (node.modifiers?.some(m => m.kind === ts.SyntaxKind.ExportKeyword)) {
      if (node.modifiers.some(m => m.kind === ts.SyntaxKind.DefaultKeyword)) file.exports.set('default', node);
      else if (node.name && ts.isIdentifier(node.name)) file.exports.set(node.name.text, node);
      else if (ts.isVariableStatement(node)) for (const declaration of node.declarationList.declarations) if (ts.isIdentifier(declaration.name)) file.exports.set(declaration.name.text, declaration.initializer);
    }
    if (ts.isCallExpression(node)) file.calls.push(node);
    ts.forEachChild(node, child => visit(file, child, scope));
  }
  for (const file of files.values()) if (!file.bad) { file.scope = { parent: null, values: new Map(), file, node: file.sf }; visit(file, file.sf, file.scope); }
  function lookup(node, id = node?.text) { for (let scope = scopes.get(node); scope; scope = scope.parent) if (scope.values.has(id)) return scope.values.get(id); return null; }
  for (const file of files.values()) for (const assignment of file.assignments) {
    const b = lookup(assignment);
    if (b) b.assigned = true;
    else binding(file.scope, assignment.text, assignment.parent, { init: assignment.parent.right, implicit: true });
  }
  function resolveModule(file, source) {
    if (!source?.startsWith('.')) return null;
    const base = path.resolve(path.dirname(file.absolute), source);
    if (!inside(base)) return null;
    return [base, ...EXTENSIONS.map(ext => base + ext), ...EXTENSIONS.map(ext => path.join(base, 'index' + ext))].map(p => files.get(p)).find(f => f && !f.bad) || null;
  }
  function exported(file, name) {
    if (!file) return null;
    if (file.exports.has(name)) return file.exports.get(name);
    const node = unwrap(file.exports.get('default'));
    if (node && ts.isObjectLiteralExpression(node)) return property(node, name);
    return null;
  }
  function property(node, key, depth = 0) {
    node = unwrap(node);
    if (!node || depth > 16) return null;
    if (ts.isIdentifier(node)) { const b = lookup(node); if (b && !b.assigned) return property(b.init, key, depth + 1); }
    if (ts.isObjectLiteralExpression(node)) for (const item of node.properties) {
      if (memberName(item.name) === key) return ts.isPropertyAssignment(item) ? item.initializer : ts.isShorthandPropertyAssignment(item) ? item.name : isFunction(item) ? item : null;
    }
    return null;
  }
  function wrapperFor(module) { return wrappers.find(w => module.replace(/\.[cm]?[jt]sx?$/, '').split('/').at(-1) === w.module || module === w.module); }
  function importedRole(file, module, imported) {
    if (module === 'express') return imported === 'Router' ? { type: 'routerFactory' } : ['default', '*'].includes(imported) ? { type: 'expressFactory' } : null;
    if (module === 'consign') return { type: 'consignFactory' };
    if (module === 'axios') return REQUEST_METHODS.has(imported) ? { type: 'axios', method: imported } : ['default', '*', 'request'].includes(imported) ? { type: 'axios' } : null;
    const local = resolveModule(file, module);
    const wrapper = local && wrapperFor(module);
    if (wrapper) return { type: 'wrapper', wrapper, local };
    if (local) return imported === '*' ? { type: 'module', local } : role(exported(local, imported));
    return null;
  }
  function role(node) {
    node = unwrap(node);
    if (!node) return null;
    if (roles.has(node)) return roles.get(node);
    if (activeRoles.has(node)) return null;
    activeRoles.add(node);
    let result = null;
    const file = scopes.get(node)?.file;
    if (ts.isIdentifier(node)) {
      const b = lookup(node);
      if (forcedRoles.has(b)) result = forcedRoles.get(b);
      else if (b?.module) result = importedRole(b.file, b.module, b.imported);
      else if (b && !b.assigned) {
        result = role(b.init);
        if (b.selector && result?.type === 'expressFactory') result = b.selector === 'Router' ? { type: 'routerFactory' } : null;
        else if (b.selector && result?.type === 'module') result = role(exported(result.local, b.selector));
        else if (b.selector && result?.type === 'axios') result = REQUEST_METHODS.has(b.selector) ? { ...result, method: b.selector } : b.selector === 'request' ? result : null;
      }
    } else if (isFunction(node)) result = { type: 'function', node, file };
    else if (ts.isPropertyAccessExpression(node)) {
      const base = role(node.expression);
      if (base?.type === 'expressFactory' && node.name.text === 'Router') result = { type: 'routerFactory' };
      else if (base?.type === 'module') result = role(exported(base.local, node.name.text));
      else if (base?.type === 'axios' && REQUEST_METHODS.has(node.name.text)) result = { ...base, method: node.name.text };
    } else if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      if (dotted(node.expression) === 'require' && !lookup(node.expression)) {
        const source = literal(node.arguments?.[0]);
        if (source) {
          if (source === 'express' || source === 'axios' || source === 'consign' || wrapperFor(source)) result = importedRole(file, source, 'default');
          else { const local = resolveModule(file, source); if (local) result = role(exported(local, 'default')) || { type: 'module', local }; }
        }
      } else {
        const callee = role(node.expression);
        if (callee?.type === 'expressFactory' || callee?.type === 'routerFactory') {
          const identity = `${file.rel}:${owner(node)}:${ts.isVariableDeclaration(node.parent) && ts.isIdentifier(node.parent.name) ? node.parent.name.text : 'router'}`;
          result = { type: 'router', id: identity, root: callee.type === 'expressFactory' };
          routers.set(identity, result);
        } else if (ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'create' && role(node.expression.expression)?.type === 'axios') result = { type: 'axios', baseURL: property(node.arguments[0], 'baseURL'), defaultOptions: node.arguments[0] };
      }
    }
    activeRoles.delete(node);
    if (result) roles.set(node, result);
    return result;
  }
  function owner(node) {
    const parts = [];
    for (let current = node.parent; current; current = current.parent) if (isFunction(current)) {
      let label = memberName(current.name);
      if (!label && ts.isVariableDeclaration(current.parent)) label = memberName(current.parent.name);
      if (!label && ts.isBinaryExpression(current.parent)) label = dotted(current.parent.left);
      if (!label && ts.isPropertyAssignment(current.parent)) label = memberName(current.parent.name);
      if (!label && ts.isExportAssignment(current.parent)) label = 'default';
      if (!label && ts.isCallExpression(current.parent)) label = `${dotted(current.parent.expression) || 'callback'}:callback`;
      parts.unshift(label || '<anonymous>');
    }
    return parts.join('/') || '<module>';
  }
  function bindFunction(fn, router) {
    if (!fn || !isFunction(fn) || !fn.parameters.length || !ts.isIdentifier(fn.parameters[0].name)) return;
    const b = scopes.get(fn)?.values.get(fn.parameters[0].name.text);
    if (b) forcedRoles.set(b, router);
  }
  // Establish exported registration functions only from proven loader/app provenance.
  for (const file of files.values()) if (!file.bad) for (const call of file.calls) {
    const e = call.expression;
    if (ts.isPropertyAccessExpression(e) && e.name.text === 'into' && role(call.arguments[0])?.type === 'router') {
      const directories = [];
      let chain = e.expression;
      while (ts.isCallExpression(chain) && ts.isPropertyAccessExpression(chain.expression)) {
        if (['include', 'then'].includes(chain.expression.name.text) && literal(chain.arguments[0])) directories.push(literal(chain.arguments[0]));
        chain = chain.expression.expression;
      }
      if (ts.isCallExpression(chain) && role(chain.expression)?.type === 'consignFactory') {
        const router = role(call.arguments[0]);
        for (const directory of directories) {
          const prefix = slash(directory).replace(/^\.\//, '').replace(/\/$/, '');
          for (const loaded of files.values()) if (!loaded.bad && (loaded.rel === prefix || loaded.rel.startsWith(prefix + '/') || EXTENSIONS.some(ext => loaded.rel === prefix + ext))) {
            bindFunction(unwrap(exported(loaded, 'default')), router);
            if (!chain.arguments.length) consignLoads.push({ file, call, target: loaded });
          }
        }
      }
    }
    const callee = role(e);
    if (callee?.type === 'function' && role(call.arguments[0])?.type === 'router') bindFunction(callee.node, role(call.arguments[0]));
  }
  // Some files were examined before their loader bound a parameter.
  roles.clear();
  const dynamic = () => ({ text: '{param}', dynamic: true, base: 'unknown' });
  function value(node, seen = new Set()) {
    node = unwrap(node);
    if (!node || seen.has(node) || seen.size > 32) return dynamic();
    seen = new Set(seen).add(node);
    const env = dotted(node).match(/^(?:import\.meta\.env|process\.env)\.([A-Za-z0-9_]+)$/);
    if (env) return { text: backendEnv.has(env[1]) ? '{backend}' : '{unknown}', dynamic: true, base: backendEnv.has(env[1]) ? 'backend' : 'unknown' };
    if (ts.isStringLiteralLike(node)) return { text: node.text, dynamic: false, base: null };
    if (ts.isIdentifier(node)) {
      if (/token|password|secret|credential|api.?key/i.test(node.text)) return dynamic();
      const b = lookup(node);
      if (b?.module) { const local = resolveModule(b.file, b.module); return value(exported(local, b.imported), seen); }
      if (b && !b.assigned && b.init) return value(b.selector ? property(b.init, b.selector) : b.init, seen);
      return dynamic();
    }
    if (ts.isTemplateExpression(node)) {
      let result = { text: node.head.text, dynamic: false, base: null };
      for (const span of node.templateSpans) { const item = value(span.expression, seen); result = { text: result.text + item.text + span.literal.text, dynamic: result.dynamic || item.dynamic, base: result.base || item.base }; }
      return result;
    }
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) { const a = value(node.left, seen), b = value(node.right, seen); return { text: a.text + b.text, dynamic: a.dynamic || b.dynamic, base: a.base || b.base }; }
    if (ts.isPropertyAccessExpression(node)) {
      const base = role(node.expression);
      if (base?.type === 'wrapper' && base.wrapper.kind === 'server' && ['SERVER_URL', 'local_url'].includes(node.name.text)) return { text: '{backend}', dynamic: true, base: 'backend' };
      if (base?.type === 'module') return value(exported(base.local, node.name.text), seen);
      const prop = property(node.expression, node.name.text); if (prop) return value(prop, seen);
    }
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'get_url' && role(node.expression.expression)?.type === 'wrapper') return { text: '{backend}', dynamic: true, base: 'backend' };
    if (ts.isConditionalExpression(node)) { const a = value(node.whenTrue, seen), b = value(node.whenFalse, seen); if (a.text === b.text && a.base === b.base) return a; }
    return dynamic();
  }
  function normalize(text) { return text.replace(/:[A-Za-z_$][\w$]*(?:\([^)]*\))?[?+*]?/g, '{param}').replace(/\{[^}]*\}/g, '{param}').replace(/\/$/, '') || '/'; }
  function resolvedPath(node, assumedTarget = 'unknown', baseNode = null, express = false) {
    let parsed = value(node);
    let target = assumedTarget;
    if (baseNode && !/^(?:https?:)?\/\//.test(parsed.text) && !parsed.text.startsWith('{backend}')) {
      const base = value(baseNode); parsed = { text: base.text.replace(/\/$/, '') + '/' + parsed.text.replace(/^\//, ''), dynamic: parsed.dynamic || base.dynamic, base: base.base };
    }
    let text = parsed.text;
    if (text.startsWith('{backend}')) { text = text.substring(9) || '/'; target = 'backend'; }
    else if (/^https?:\/\//i.test(text)) {
      try { const url = new URL(text); target = /\{|%7b/i.test(url.host) ? 'unknown' : backendOrigins.has(url.origin) ? 'backend' : 'external'; text = url.pathname.replace(/%7Bparam%7D/gi, '{param}'); } catch { return { path: null, normalizedPath: null, target: 'unknown', resolution: 'unresolved' }; }
    } else if (text.startsWith('//')) return { path: null, normalizedPath: null, target: 'external', resolution: 'unresolved' };
    if (!text.startsWith('/') || text.startsWith('//')) return { path: null, normalizedPath: null, target, resolution: 'unresolved' };
    // Never return URL credentials, query values, or fragments from target source.
    if (!express) text = text.split(/[?#]/, 1)[0] || '/';
    else {
      // Preserve Express's optional parameter marker, but never URL query values.
      const query = text.indexOf('?');
      if (query >= 0 && (!/:[\w$]+$/.test(text.substring(0, query)) || query + 1 < text.length && text[query + 1] !== '/')) text = text.substring(0, query) || '/';
      text = text.split('#', 1)[0];
    }
    const unsupportedPattern = express && /[?+*()[\]{}]/.test(text);
    return { path: text, normalizedPath: unsupportedPattern ? null : normalize(text), target, resolution: unsupportedPattern ? 'unresolved' : /\{[^}]*\}/.test(text) ? 'template' : 'literal', ...(unsupportedPattern ? { unsupportedPattern: true } : {}) };
  }
  function method(node, fallback = null) { const result = node ? value(node) : null; return result && !result.dynamic && REQUEST_METHODS.has(result.text.toLowerCase()) ? result.text.toUpperCase() : !node && fallback ? fallback : 'UNKNOWN'; }
  function optionMethod(node, fallback = 'GET', seen = new Set()) {
    node = unwrap(node);
    if (!node) return fallback;
    if (seen.has(node) || seen.size > 16) return 'UNKNOWN';
    seen = new Set(seen).add(node);
    if (ts.isIdentifier(node)) { const b = lookup(node); return b && !b.assigned && b.init !== node ? optionMethod(b.init, fallback, seen) : 'UNKNOWN'; }
    if (!ts.isObjectLiteralExpression(node) || node.properties.some(p => ts.isSpreadAssignment(p) || p.name && ts.isComputedPropertyName(p.name))) return 'UNKNOWN';
    return method(property(node, 'method'), fallback);
  }
  function location(file, node) { return { file: file.rel, line: file.sf.getLineAndCharacterOfPosition(node.getStart(file.sf)).line + 1, endLine: file.sf.getLineAndCharacterOfPosition(node.getEnd()).line + 1 }; }
  function routeChain(node) {
    if (!node || !ts.isCallExpression(node) || !ts.isPropertyAccessExpression(node.expression)) return null;
    const e = node.expression;
    if (e.name.text === 'route' && role(e.expression)?.type === 'router') return { router: role(e.expression), route: node.arguments[0] };
    if (HTTP.has(e.name.text)) return routeChain(e.expression);
    return null;
  }
  function handler(node, file, seen = new Set()) {
    node = unwrap(node);
    if (!node || seen.has(node)) return { label: '<unresolved>', fingerprint: hash('unresolved') };
    seen = new Set(seen).add(node);
    if (isFunction(node)) return { label: memberName(node.name) || '<inline>', fingerprint: semanticHash(node), file: scopes.get(node).file.rel, line: scopes.get(node).file.sf.getLineAndCharacterOfPosition(node.getStart()).line + 1 };
    if (ts.isCallExpression(node) && dotted(node.expression) === 'require') { const target = role(node); if (target?.type === 'function') return handler(target.node, file, seen); }
    if (ts.isIdentifier(node)) {
      const b = lookup(node);
      const definition = b?.module ? exported(resolveModule(b.file, b.module), b.imported) : b?.init;
      if (definition && definition !== node) return { ...handler(definition, file, seen), label: node.text };
    }
    if (ts.isPropertyAccessExpression(node)) {
      const ref = role(node.expression);
      let definition = ref?.type === 'module' ? exported(ref.local, node.name.text) : property(node.expression, node.name.text);
      if (!definition && ts.isIdentifier(node.expression)) {
        const b = lookup(node.expression);
        if (b?.module) definition = exported(resolveModule(b.file, b.module), node.name.text);
      }
      if (definition) return { ...handler(definition, file, seen), label: dotted(node) || '<reference>' };
    }
    return { label: dotted(node) || '<expression>', fingerprint: semanticHash(node) };
  }
  const wrapperFiles = new Set();
  for (const file of files.values()) for (const b of file.bindings) {
    const imported = b.module || (b.init && ts.isCallExpression(b.init) && dotted(b.init.expression) === 'require' ? literal(b.init.arguments[0]) : null);
    if (imported) { const local = resolveModule(file, imported); if (local && wrapperFor(imported)) wrapperFiles.add(local.absolute); }
  }
  for (const file of files.values()) if (!file.bad) for (const call of file.calls) {
    const e = call.expression;
    if (kind === 'backend' && ts.isPropertyAccessExpression(e)) {
      const parent = role(e.expression);
      if (e.name.text === 'use' && parent?.type === 'router') {
        const firstIsRouter = role(call.arguments[0])?.type === 'router';
        const prefix = firstIsRouter ? { path: '/', resolution: 'literal' } : resolvedPath(call.arguments[0], 'unknown', null, true);
        if (prefix.unsupportedPattern) diag(file, call, 'UNSUPPORTED_EXPRESS_PATTERN', 'Express path pattern requires runtime-specific matching and is not normalized.');
        for (const arg of call.arguments.slice(firstIsRouter ? 0 : 1)) { const child = role(arg); if (child?.type === 'router') mounts.push({ parent: parent.id, child: child.id, prefix: prefix.path, file, call }); }
      }
      if (HTTP.has(e.name.text)) {
        const chain = routeChain(e.expression);
        const router = parent?.type === 'router' ? parent : chain?.router;
        const routeNode = chain ? chain.route : call.arguments[0];
        const handlers = [...call.arguments].slice(chain ? 0 : 1);
        if (router && handlers.length) {
          const alternatives = routeNode && ts.isArrayLiteralExpression(routeNode) ? [...routeNode.elements] : [routeNode];
          for (const route of alternatives) endpointCandidates.push({ file, call, router, route, method: e.name.text.toUpperCase(), handlers });
        }
      }
    }
    if (wrapperFiles.has(file.absolute)) continue;
    let source = null, methodName = 'UNKNOWN', argument = null, baseURL = null, assumedTarget = 'unknown';
    if (ts.isPropertyAccessExpression(e)) {
      const client = role(e.expression);
      const m = e.name.text;
      if (client?.type === 'wrapper' && client.wrapper.kind === 'server') {
        if (/^(get|post|put|patch|delete|head|options)_request$/.test(m)) { source = 'wrapper'; methodName = m.split('_')[0].toUpperCase(); argument = call.arguments[0]; }
        else if (REQUEST_METHODS.has(m)) { source = 'wrapper'; methodName = m.toUpperCase(); argument = m === 'get' && property(call.arguments[0], 'path') ? property(call.arguments[0], 'path') : call.arguments[0]; }
        assumedTarget = 'backend';
      } else if (client?.type === 'axios' && REQUEST_METHODS.has(m)) { source = 'axios'; methodName = m.toUpperCase(); argument = call.arguments[0]; baseURL = client.baseURL; }
      else if (client?.type === 'axios' && m === 'request') { source = 'axios'; methodName = optionMethod(call.arguments[0], optionMethod(client.defaultOptions)); argument = property(call.arguments[0], 'url'); baseURL = property(call.arguments[0], 'baseURL') || client.baseURL; }
    } else if (ts.isIdentifier(e)) {
      const client = role(e);
      if (client?.type === 'wrapper' && client.wrapper.kind === 'method-first') {
        source = 'wrapper'; methodName = method(call.arguments[0]); argument = call.arguments[1];
        if (client.wrapper.methodPolicy === 'get-or-post' && methodName !== 'UNKNOWN' && methodName !== 'GET') methodName = 'POST';
        if (client.wrapper.relativeTarget === 'backend' && value(argument).text.startsWith('/')) assumedTarget = 'backend';
      }
      else if (client?.type === 'axios') {
        source = 'axios';
        if (client.method) { methodName = client.method.toUpperCase(); argument = call.arguments[0]; }
        else if (ts.isObjectLiteralExpression(unwrap(call.arguments[0]) || file.sf) || property(call.arguments[0], 'url')) { argument = property(call.arguments[0], 'url'); methodName = optionMethod(call.arguments[0], optionMethod(client.defaultOptions)); baseURL = property(call.arguments[0], 'baseURL') || client.baseURL; }
        else { argument = call.arguments[0]; methodName = optionMethod(call.arguments[1], optionMethod(client.defaultOptions)); baseURL = client.baseURL; }
      } else if (e.text === 'fetch' && !lookup(e)) { source = 'fetch'; argument = call.arguments[0]; methodName = optionMethod(call.arguments[1]); }
    }
    if (source) {
      const route = resolvedPath(argument, assumedTarget, baseURL);
      if (route.resolution === 'unresolved') diag(file, call, 'UNRESOLVED_CALL_PATH', 'The request path could not be resolved statically.');
      if (methodName === 'UNKNOWN') diag(file, call, 'UNRESOLVED_HTTP_METHOD', 'The request HTTP method could not be resolved statically.');
      calls.push({ ...location(file, call), method: methodName, ...route, caller: owner(call), source, bodyFingerprint: semanticHash(call) });
    }
  }
  function prefixes(routerId, seen = new Set()) {
    if (seen.has(routerId)) return [null];
    const router = routers.get(routerId);
    if (router?.root) return [''];
    const edges = mounts.filter(edge => edge.child === routerId);
    if (!edges.length) return [null];
    const result = [];
    for (const edge of edges) for (const prefix of prefixes(edge.parent, new Set(seen).add(routerId))) result.push(prefix === null || edge.prefix === null ? null : prefix.replace(/\/$/, '') + (edge.prefix === '/' ? '' : edge.prefix));
    return [...new Set(result)];
  }
  const endpoints = [];
  for (const candidate of endpointCandidates) {
    const { file, call, router, handlers } = candidate;
    const own = resolvedPath(candidate.route, 'unknown', null, true);
    const resolvedHandlers = handlers.map(item => handler(item, file));
    const last = resolvedHandlers.at(-1);
    for (const prefix of prefixes(router.id)) {
      const complete = prefix !== null && own.path !== null ? (prefix.replace(/\/$/, '') + own.path) : null;
      if (own.path === null) diag(file, call, 'UNRESOLVED_ENDPOINT_PATH', 'The Express registration path could not be resolved statically.');
      if (own.unsupportedPattern || complete && /[?+*()[\]{}]/.test(complete)) diag(file, call, 'UNSUPPORTED_EXPRESS_PATTERN', 'Express path pattern requires runtime-specific matching and is not normalized.');
      if (prefix === null) diag(file, call, 'UNRESOLVED_ROUTER_MOUNT', 'No statically proven application mount was found for this router.');
      const endpoint = { ...location(file, call), method: candidate.method, path: complete, normalizedPath: complete === null || /[?+*()[\]{}]/.test(complete) ? null : normalize(complete), handler: last.label, ...(last.file ? { handlerFile: last.file, handlerLine: last.line } : {}), caller: owner(call), bodyFingerprint: hash(resolvedHandlers.map(item => item.fingerprint).join(':')) };
      endpoints.push(endpoint); endpointHandlerNodes.set(endpoint, handlers);
    }
  }
  function finish(records, category) {
    const occurrences = new Map();
    for (const record of records) {
      const identity = JSON.stringify([name, kind, category, record.file, record.caller, record.method, record.normalizedPath ?? record.path]);
      const ordinal = (occurrences.get(identity) || 0) + 1; occurrences.set(identity, ordinal);
      record.id = `${category}-${hash(`${identity}:${ordinal}`).slice(0, 24)}`;
      record.contractFingerprint = hash(JSON.stringify([record.method, record.normalizedPath ?? record.path, record.target || 'endpoint']));
      record.fingerprint = hash(`${record.contractFingerprint}:${record.bodyFingerprint}`);
    }
    return records.sort((a, b) => a.file.localeCompare(b.file, 'en') || a.line - b.line || a.id.localeCompare(b.id, 'en'));
  }
  finish(endpoints, 'endpoint'); finish(calls, 'call');
  const architecture = buildArchitecture({ ts, files, name, kind, lookup, resolveModule, unwrap, isFunction, literal, semanticHash, hash, location, endpoints, endpointHandlerNodes, consignLoads });
  const deduped = [...new Map(diagnostics.map(d => [JSON.stringify(d), d])).values()].sort((a, b) => a.file.localeCompare(b.file, 'en') || a.line - b.line || a.code.localeCompare(b.code, 'en'));
  return {
    schemaVersion: 1, component: { name, kind }, filesAnalyzed: [...files.values()].filter(file => !file.bad).length,
    endpoints, calls, diagnostics: deduped, architecture,
    stats: { parser: 'typescript', parserVersion: ts.version, sourceFiles: files.size, parseErrorFiles: [...files.values()].filter(f => f.bad).length, endpointRegistrations: endpoints.length, uniqueMethodPaths: new Set(endpoints.filter(e => e.path !== null).map(e => `${e.method} ${e.path}`)).size, callSites: calls.length, unresolvedCallPaths: calls.filter(c => c.path === null).length, wrapperFilesExcluded: wrapperFiles.size, elapsedMs: Math.round((performance.now() - started) * 100) / 100, peakRssKiB: process.resourceUsage().maxRSS },
  };
}

if (require.main === module) {
  try {
    const args = process.argv.slice(2), options = {};
    for (let i = 0; i < args.length; i += 2) { if (!['--root', '--kind', '--name', '--config'].includes(args[i]) || !args[i + 1] || options[args[i].substring(2)] !== undefined) throw new Error('Usage: analyzer.cjs --root ABSOLUTE --kind backend|frontend --name NAME [--config JSON_FILE]'); options[args[i].substring(2)] = args[i + 1]; }
    if (options.config) { if (fs.statSync(options.config).size > 64 * 1024) throw new Error('Adapter configuration exceeds 64 KiB.'); options.config = JSON.parse(fs.readFileSync(options.config, 'utf8')); }
    process.stdout.write(JSON.stringify(analyze(options)) + '\n');
  } catch (error) {
    // Error messages never include target source or literal configuration values.
    process.stderr.write(`JavaScript analysis failed: ${error instanceof Error && /^(Provide|The analysis|Source file|Invalid wrapper|Usage:|Adapter configuration)/.test(error.message) ? error.message : 'invalid input, filesystem access, or parser failure.'}\n`);
    process.exitCode = 1;
  }
}
module.exports = { analyze };
