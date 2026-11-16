'use strict';
/** Build ordered module/local-call/handler edges only after both inventories and resolution exist. */
function buildGraphs({
  ts,
  validFiles,
  literal,
  location,
  lookup,
  resolveModule,
  isFunction,
  id,
  dependencies,
  functions,
  calls,
  endpointHandlers,
  functionRecords,
  requireIdentifier,
  resolveValue,
  consignLoads,
  endpoints,
  endpointHandlerNodes,
  resource,
}) {
  function addDependency(file, node, source, edgeKind, typeOnly = false, provenTarget = null) {
    const target =
      provenTarget || (typeof source === 'string' ? resolveModule(file, source) : null);
    const resolution = target
      ? 'resolved'
      : typeof source === 'string' &&
          !source.startsWith('.') &&
          !source.startsWith('/') &&
          !/^[A-Za-z]:[\\/]/.test(source)
        ? 'external'
        : 'unresolved';
    // Specifiers are deliberately omitted: URLs and package strings can contain secrets.
    resource.record();
    dependencies.push({
      id: id('dependency', [file.rel, target?.rel || null, edgeKind, typeOnly]),
      source: file.rel,
      target: target?.rel || null,
      line: location(file, node).line,
      kind: edgeKind,
      resolution,
      typeOnly,
    });
  }
  function dependencyTypes(clause, declarationTypeOnly) {
    if (declarationTypeOnly) return [true];
    const named = clause?.namedBindings || clause;
    if (named && (ts.isNamedImports(named) || ts.isNamedExports(named)) && named.elements.length) {
      const types = new Set(named.elements.map((element) => !!element.isTypeOnly));
      if (clause?.name) types.add(false);
      return [...types].sort();
    }
    return [false];
  }
  let candidateCallSites = 0,
    unresolvedCallSites = 0,
    resolvedTopLevelCallsOmitted = 0;
  for (const file of validFiles) {
    function visit(node) {
      resource.step();
      if (ts.isImportDeclaration(node))
        for (const typeOnly of dependencyTypes(node.importClause, node.importClause?.isTypeOnly))
          addDependency(file, node, literal(node.moduleSpecifier), 'import', typeOnly);
      if (ts.isExportDeclaration(node) && node.moduleSpecifier)
        for (const typeOnly of dependencyTypes(node.exportClause, node.isTypeOnly))
          addDependency(file, node, literal(node.moduleSpecifier), 'reexport', typeOnly);
      if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference))
        addDependency(
          file,
          node,
          literal(node.moduleReference.expression),
          'import-equals',
          !!node.isTypeOnly,
        );
      if (ts.isImportTypeNode(node))
        addDependency(
          file,
          node,
          ts.isLiteralTypeNode(node.argument) ? literal(node.argument.literal) : null,
          'import-type',
          true,
        );
      if (ts.isCallExpression(node)) {
        if (node.expression.kind === ts.SyntaxKind.ImportKeyword)
          addDependency(file, node, literal(node.arguments[0]), 'dynamic-import');
        else if (requireIdentifier(node.expression))
          addDependency(file, node, literal(node.arguments[0]), 'require');
        candidateCallSites++;
        const target = resolveValue(node.expression);
        const targetRecord = target?.type === 'function' && functionRecords.get(target.node);
        let parent = node.parent;
        while (parent && !isFunction(parent)) parent = parent.parent;
        const callerRecord = parent && functionRecords.get(parent);
        if (!targetRecord) {
          unresolvedCallSites++;
          if (callerRecord) callerRecord.unresolvedCalls++;
        } else if (!callerRecord) resolvedTopLevelCallsOmitted++;
        else {
          resource.record();
          calls.push({
            id: id('local-call', [file.rel, callerRecord.id, targetRecord.id]),
            file: file.rel,
            line: location(file, node).line,
            callerId: callerRecord.id,
            targetId: targetRecord.id,
          });
        }
      }
      ts.forEachChild(node, visit);
    }
    visit(file.sf);
  }
  for (const load of consignLoads)
    if (!load.target.bad) addDependency(load.file, load.call, null, 'consign', false, load.target);
  for (const endpoint of endpoints)
    for (const node of endpointHandlerNodes.get(endpoint) || []) {
      const value = resolveValue(node),
        record = value?.type === 'function' && functionRecords.get(value.node);
      if (record) {
        resource.record();
        endpointHandlers.push({ endpointId: endpoint.id, functionId: record.id });
      }
    }
  const uniqueHandlers = [
    ...new Map(
      endpointHandlers.map((edge) => [`${edge.endpointId}:${edge.functionId}`, edge]),
    ).values(),
  ];
  const order = (a, b) =>
    (a.file || a.source || '').localeCompare(b.file || b.source || '', 'en') ||
    (a.line || 0) - (b.line || 0) ||
    a.id.localeCompare(b.id, 'en');
  dependencies.sort(order);
  functions.sort(order);
  calls.sort(order);
  uniqueHandlers.sort(
    (a, b) =>
      a.endpointId.localeCompare(b.endpointId, 'en') ||
      a.functionId.localeCompare(b.functionId, 'en'),
  );

  return { uniqueHandlers, candidateCallSites, unresolvedCallSites, resolvedTopLevelCallsOmitted };
}
module.exports = { buildGraphs };
