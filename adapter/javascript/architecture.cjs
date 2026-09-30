'use strict';

// Uses only ASTs already read by analyzer.cjs. Never loads a target module.
function buildArchitecture({ ts, files, name, kind, lookup, resolveModule, unwrap, isFunction, literal, semanticHash, hash, location, endpoints, endpointHandlerNodes, consignLoads }) {
  const validFiles = [...files.values()].filter(file => !file.bad);
  const modules = validFiles.map(file => ({ id: file.rel, file: file.rel, line: 1 }));
  const dependencies = [], functions = [], calls = [], endpointHandlers = [];
  const functionRecords = new Map(), nodeFiles = new WeakMap(), occurrence = new Map();
  const mutations = new Set(), exportDeclarations = new Map(), localExports = new Map();
  const id = (category, parts) => {
    const key = JSON.stringify([name, kind, category, ...parts]);
    const ordinal = (occurrence.get(key) || 0) + 1; occurrence.set(key, ordinal);
    return `${category}-${hash(`${key}:${ordinal}`).slice(0, 24)}`;
  };
  const identifierName = node => node && ts.isIdentifier(node) ? node.text : null;
  function safeDotted(node) {
    if (!node) return '';
    if (ts.isIdentifier(node)) return node.text;
    if (ts.isPropertyAccessExpression(node)) { const base = safeDotted(node.expression); return base ? `${base}.${node.name.text}` : ''; }
    return '';
  }
  function functionLabel(node) {
    if (identifierName(node.name)) return identifierName(node.name);
    const parent = node.parent;
    if (ts.isVariableDeclaration(parent)) return identifierName(parent.name) || '<binding>';
    if (ts.isPropertyAssignment(parent)) return identifierName(parent.name) || '<property>';
    if (ts.isBinaryExpression(parent)) return safeDotted(parent.left) || '<assignment>';
    if (ts.isExportAssignment(parent)) return '<default>';
    if (ts.isCallExpression(parent) || ts.isNewExpression(parent)) return `${safeDotted(parent.expression) || '<call>'}:callback:${[...(parent.arguments || [])].indexOf(node)}`;
    return '<anonymous>';
  }
  function scan(file, node, parentFunction = null) {
    nodeFiles.set(node, file);
    if (isFunction(node)) {
      const label = functionLabel(node);
      const record = { id: id('function', [file.rel, parentFunction?.id || '<module>', label]), ...location(file, node), name: label, bodyFingerprint: semanticHash(node) };
      functions.push(record); functionRecords.set(node, record); parentFunction = record;
    }
    if (ts.isExportDeclaration(node)) exportDeclarations.get(file).push(node);
    function addExport(exportName, value) {
      const exports = localExports.get(file);
      exports.set(exportName, exports.has(exportName) ? null : value);
    }
    if (ts.isExportAssignment(node)) addExport('default', node.expression);
    if (node.modifiers?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword)) {
      if (node.modifiers.some(modifier => modifier.kind === ts.SyntaxKind.DefaultKeyword)) addExport('default', node);
      else if (ts.isVariableStatement(node)) {
        for (const declaration of node.declarationList.declarations) if (ts.isIdentifier(declaration.name)) addExport(declaration.name.text, declaration.initializer);
      }
      else if (identifierName(node.name) && (!ts.isFunctionDeclaration(node) || node.body)) addExport(node.name.text, node);
    }
    if (!parentFunction && ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      const key = safeDotted(node.left);
      let base = node.left;
      while (ts.isPropertyAccessExpression(base)) base = base.expression;
      if (!lookup(base)) {
        if (key === 'module.exports') addExport('default', node.right);
        else if (key.startsWith('module.exports.')) addExport(key.substring(15), node.right);
        else if (key.startsWith('exports.')) addExport(key.substring(8), node.right);
      }
    }
    if (ts.isBinaryExpression(node) && node.operatorToken.kind >= ts.SyntaxKind.FirstAssignment && node.operatorToken.kind <= ts.SyntaxKind.LastAssignment || (ts.isPrefixUnaryExpression(node) || ts.isPostfixUnaryExpression(node)) && [ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator)) {
      let left = ts.isBinaryExpression(node) ? node.left : node.operand;
      while (ts.isPropertyAccessExpression(left) || ts.isElementAccessExpression(left)) left = left.expression;
      if (ts.isIdentifier(left)) { const binding = lookup(left); if (binding) mutations.add(binding); }
    }
    ts.forEachChild(node, child => scan(file, child, parentFunction));
  }
  for (const file of validFiles) { exportDeclarations.set(file, []); localExports.set(file, new Map()); scan(file, file.sf); }

  function typeOnlyBinding(binding) {
    if (binding.typeOnly) return true;
    if (!binding.module || !ts.isImportDeclaration(binding.node)) return false;
    if (binding.node.importClause?.isTypeOnly) return true;
    const named = binding.node.importClause?.namedBindings;
    return named && ts.isNamedImports(named) && named.elements.some(element => element.name.text === binding.name && element.isTypeOnly);
  }
  function requireIdentifier(node, seen = new Set()) {
    node = unwrap(node);
    if (!node || !ts.isIdentifier(node) || seen.has(node) || seen.size > 64) return false;
    const binding = lookup(node);
    if (!binding) return node.text === 'require';
    if (binding.assigned || mutations.has(binding) || binding.module || !binding.init) return false;
    return requireIdentifier(binding.init, new Set(seen).add(node));
  }
  function importValue(file, source, imported, seen) {
    const target = resolveModule(file, source);
    if (!target) return null;
    return imported === '*' ? { type: 'module', file: target } : exportedValue(target, imported, seen);
  }
  function exportedValue(file, exportedName, seen = new Set()) {
    if (!file || seen.size > 64) return null;
    const key = `${file.rel}:${exportedName}`;
    if (seen.has(key)) return null;
    seen = new Set(seen).add(key);
    if (localExports.get(file).has(exportedName)) return resolveValue(localExports.get(file).get(exportedName), seen);
    const defaultNode = localExports.get(file).get('default');
    if (exportedName !== 'default' && defaultNode) {
      const result = memberValue(resolveValue(defaultNode, seen), exportedName, seen);
      if (result) return result;
    }
    const matches = [], stars = [];
    for (const declaration of exportDeclarations.get(file) || []) {
      if (declaration.isTypeOnly) continue;
      const source = literal(declaration.moduleSpecifier), clause = declaration.exportClause;
      if (clause && ts.isNamedExports(clause)) {
        for (const element of clause.elements) if (!element.isTypeOnly && element.name.text === exportedName) {
          const localName = (element.propertyName || element.name).text;
          const result = source ? importValue(file, source, localName, seen) : resolveBinding(lookup(element, localName), seen);
          if (result) matches.push(result);
        }
      } else if (clause && ts.isNamespaceExport(clause) && clause.name.text === exportedName && source) {
        const result = importValue(file, source, '*', seen); if (result) matches.push(result);
      } else if (!clause && source && exportedName !== 'default') {
        stars.push(source);
      }
    }
    if (!matches.length) for (const source of stars) { const result = importValue(file, source, exportedName, seen); if (result) matches.push(result); }
    // Conflicting star exports are not assigned an arbitrary target.
    if (!matches.length || matches.some(value => value.type !== matches[0].type || value.node !== matches[0].node || value.file !== matches[0].file)) return null;
    return matches[0];
  }
  function resolveBinding(binding, seen) {
    if (!binding || binding.assigned || mutations.has(binding) || typeOnlyBinding(binding)) return null;
    if (binding.module && ts.isImportEqualsDeclaration(binding.node)) return importValue(binding.file, binding.module, 'default', seen) || importValue(binding.file, binding.module, '*', seen);
    if (binding.module) return importValue(binding.file, binding.module, binding.imported, seen);
    const value = resolveValue(binding.init, seen);
    return binding.selector ? memberValue(value, binding.selector, seen) : value;
  }
  function memberValue(base, member, seen) {
    if (!base || !member) return null;
    if (base.type === 'module') return exportedValue(base.file, member, seen);
    if (base.type === 'object') {
      // A spread/computed property could override a named function property.
      if (base.node.properties.some(p => ts.isSpreadAssignment(p) || p.name && ts.isComputedPropertyName(p.name))) return null;
      const found = base.node.properties.filter(p => (identifierName(p.name) || literal(p.name)) === member);
      if (found.length !== 1) return null;
      const item = found[0];
      return ts.isPropertyAssignment(item) ? resolveValue(item.initializer, seen) : ts.isShorthandPropertyAssignment(item) ? resolveValue(item.name, seen) : ts.isMethodDeclaration(item) ? { type: 'function', node: item } : null;
    }
    return null;
  }
  function resolveValue(node, seen = new Set()) {
    node = unwrap(node);
    if (!node || seen.has(node) || seen.size > 64) return null;
    seen = new Set(seen).add(node);
    if (isFunction(node) && !ts.isGetAccessorDeclaration(node) && !ts.isSetAccessorDeclaration(node)) return { type: 'function', node };
    if (ts.isIdentifier(node)) return resolveBinding(lookup(node), seen);
    if (ts.isObjectLiteralExpression(node)) return { type: 'object', node };
    if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) return memberValue(resolveValue(node.expression, seen), ts.isPropertyAccessExpression(node) ? node.name.text : literal(node.argumentExpression), seen);
    if (ts.isCallExpression(node) && requireIdentifier(node.expression)) {
      const local = resolveModule(nodeFiles.get(node), literal(node.arguments[0]));
      if (local) return exportedValue(local, 'default', seen) || { type: 'module', file: local };
    }
    return null;
  }

  function addDependency(file, node, source, edgeKind, typeOnly = false, provenTarget = null) {
    const target = provenTarget || (typeof source === 'string' ? resolveModule(file, source) : null);
    const resolution = target ? 'resolved' : typeof source === 'string' && !source.startsWith('.') && !source.startsWith('/') && !/^[A-Za-z]:[\\/]/.test(source) ? 'external' : 'unresolved';
    // Specifiers are deliberately omitted: URLs and package strings can contain secrets.
    dependencies.push({ id: id('dependency', [file.rel, target?.rel || null, edgeKind, typeOnly]), source: file.rel, target: target?.rel || null, line: location(file, node).line, kind: edgeKind, resolution, typeOnly });
  }
  function dependencyTypes(clause, declarationTypeOnly) {
    if (declarationTypeOnly) return [true];
    const named = clause?.namedBindings || clause;
    if (named && (ts.isNamedImports(named) || ts.isNamedExports(named)) && named.elements.length) {
      const types = new Set(named.elements.map(element => !!element.isTypeOnly));
      if (clause?.name) types.add(false);
      return [...types].sort();
    }
    return [false];
  }
  let candidateCallSites = 0, unresolvedCallSites = 0, resolvedTopLevelCallsOmitted = 0;
  for (const file of validFiles) {
    function visit(node) {
      if (ts.isImportDeclaration(node)) for (const typeOnly of dependencyTypes(node.importClause, node.importClause?.isTypeOnly)) addDependency(file, node, literal(node.moduleSpecifier), 'import', typeOnly);
      if (ts.isExportDeclaration(node) && node.moduleSpecifier) for (const typeOnly of dependencyTypes(node.exportClause, node.isTypeOnly)) addDependency(file, node, literal(node.moduleSpecifier), 'reexport', typeOnly);
      if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) addDependency(file, node, literal(node.moduleReference.expression), 'import-equals', !!node.isTypeOnly);
      if (ts.isImportTypeNode(node)) addDependency(file, node, ts.isLiteralTypeNode(node.argument) ? literal(node.argument.literal) : null, 'import-type', true);
      if (ts.isCallExpression(node)) {
        if (node.expression.kind === ts.SyntaxKind.ImportKeyword) addDependency(file, node, literal(node.arguments[0]), 'dynamic-import');
        else if (requireIdentifier(node.expression)) addDependency(file, node, literal(node.arguments[0]), 'require');
        candidateCallSites++;
        const target = resolveValue(node.expression);
        const targetRecord = target?.type === 'function' && functionRecords.get(target.node);
        let parent = node.parent;
        while (parent && !isFunction(parent)) parent = parent.parent;
        const callerRecord = parent && functionRecords.get(parent);
        if (!targetRecord) unresolvedCallSites++;
        else if (!callerRecord) resolvedTopLevelCallsOmitted++;
        else calls.push({ id: id('local-call', [file.rel, callerRecord.id, targetRecord.id]), file: file.rel, line: location(file, node).line, callerId: callerRecord.id, targetId: targetRecord.id });
      }
      ts.forEachChild(node, visit);
    }
    visit(file.sf);
  }
  for (const load of consignLoads) if (!load.target.bad) addDependency(load.file, load.call, null, 'consign', false, load.target);
  for (const endpoint of endpoints) for (const node of endpointHandlerNodes.get(endpoint) || []) {
    const value = resolveValue(node), record = value?.type === 'function' && functionRecords.get(value.node);
    if (record) endpointHandlers.push({ endpointId: endpoint.id, functionId: record.id });
  }
  const uniqueHandlers = [...new Map(endpointHandlers.map(edge => [`${edge.endpointId}:${edge.functionId}`, edge])).values()];
  const order = (a, b) => (a.file || a.source || '').localeCompare(b.file || b.source || '', 'en') || (a.line || 0) - (b.line || 0) || a.id.localeCompare(b.id, 'en');
  dependencies.sort(order); functions.sort(order); calls.sort(order);
  uniqueHandlers.sort((a, b) => a.endpointId.localeCompare(b.endpointId, 'en') || a.functionId.localeCompare(b.functionId, 'en'));
  return {
    schemaVersion: 1, modules, dependencies, functions, calls, endpointHandlers: uniqueHandlers,
    summary: {
      modules: modules.length, dependencies: dependencies.length,
      resolvedRuntimeDependencies: dependencies.filter(d => d.resolution === 'resolved' && !d.typeOnly).length,
      typeOnlyDependencies: dependencies.filter(d => d.typeOnly).length,
      externalDependencies: dependencies.filter(d => d.resolution === 'external').length,
      unresolvedDependencies: dependencies.filter(d => d.resolution === 'unresolved').length,
      functions: functions.length, candidateCallSites, resolvedLocalCalls: calls.length, unresolvedCallSites,
      resolvedTopLevelCallsOmitted, endpointHandlerBindings: uniqueHandlers.length,
      endpointsWithResolvedHandlers: new Set(uniqueHandlers.map(edge => edge.endpointId)).size,
    },
    limitations: [
      'Source module and local function graphs describe static references, not deployment boundaries or runtime reachability.',
      'External dependencies are not inspected; nonliteral imports, excluded files, package aliases, and unresolved targets are not invented. Relative asset, stylesheet, and JSON imports can be unresolved because they are outside the source corpus.',
      'Type-only edges must be excluded from runtime dependency metrics. Dynamic imports and Consign loads remain conditional source evidence.',
      'Function calls are emitted only for proven local targets and enclosing functions. Top-level calls are counted separately; unresolved counts include library, dynamic, and builtin calls.',
      'No argument-sensitive analysis, callback invocation inference, returned-function inference, class instance dispatch, this binding, or complete mutation analysis is performed.',
      'Endpoint bindings cover statically resolved supplied handlers and middleware, without inferring their invocation order or transitive runtime behavior.',
      'Global app.use middleware, middleware inherited through mounted routers, and dynamically assembled middleware are not propagated to endpoint bindings; binding coverage is not complete middleware coverage.',
      'Function IDs exclude lines and bodies; repeated anonymous contexts use occurrence order, so inserting earlier identical callbacks can change those IDs.',
    ],
  };
}

module.exports = { buildArchitecture };
