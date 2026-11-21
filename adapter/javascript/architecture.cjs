'use strict';
const { buildContracts } = require('./contracts.cjs');
const { buildSemanticEvidence } = require('./semantics.cjs');
const { buildInventory } = require('./lib/architecture/inventory.cjs');
const { createValueResolver } = require('./lib/architecture/resolution.cjs');
const { buildGraphs } = require('./lib/architecture/graphs.cjs');

// Uses only ASTs already read by analyzer.cjs. Never loads a target module.
function buildArchitecture({
  ts,
  files,
  name,
  kind,
  lookup,
  resolveModule,
  unwrap,
  isFunction,
  literal,
  semanticHash,
  hash,
  location,
  endpoints,
  endpointHandlerNodes,
  consignLoads,
  sourceInspectionComplete = true,
  sourceExclusionCounts = {},
  resource = { step() {}, record() {} },
}) {
  const validFiles = [...files.values()].filter((file) => !file.bad);
  resource.record(validFiles.length);
  const modules = validFiles.map((file) => ({ id: file.rel, file: file.rel, line: 1 }));
  const dependencies = [],
    functions = [],
    calls = [],
    endpointHandlers = [];
  const occurrence = new Map();
  const id = (category, parts) => {
    const key = JSON.stringify([name, kind, category, ...parts]);
    const ordinal = (occurrence.get(key) || 0) + 1;
    occurrence.set(key, ordinal);
    return `${category}-${hash(`${key}:${ordinal}`).slice(0, 24)}`;
  };

  const { functionRecords, nodeFiles, mutations, exportDeclarations, localExports } =
    buildInventory({
      ts,
      validFiles,
      isFunction,
      literal,
      lookup,
      semanticHash,
      location,
      id,
      functions,
      resource,
    });
    
  const { requireIdentifier, resolveValue } = createValueResolver({
    ts,
    unwrap,
    isFunction,
    literal,
    lookup,
    resolveModule,
    nodeFiles,
    mutations,
    localExports,
    exportDeclarations,
    resource,
  });

  const { uniqueHandlers, candidateCallSites, unresolvedCallSites, resolvedTopLevelCallsOmitted } =
    buildGraphs({
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
    });

  const contracts = buildContracts({
    ts,
    validFiles,
    lookup,
    unwrap,
    isFunction,
    literal,
    location,
    endpoints,
    endpointHandlerNodes,
    resolveValue,
    functionRecords,
    nodeFiles,
    mutations,
    resource,
  });

  const semanticEvidence = buildSemanticEvidence({
    ts,
    validFiles,
    name,
    kind,
    lookup,
    resolveModule,
    unwrap,
    isFunction,
    literal,
    location,
    endpoints,
    endpointHandlers: uniqueHandlers,
    resolveValue,
    functionRecords,
    nodeFiles,
    mutations,
    localExports,
    exportDeclarations,
    requireIdentifier,
    hash,
    sourceInspectionComplete,
    sourceExclusionCounts,
    resource,
  });

  return {
    schemaVersion: 1,
    modules,
    dependencies,
    functions,
    calls,
    endpointHandlers: uniqueHandlers,
    contracts,
    semanticEvidence,
    summary: {
      modules: modules.length,
      dependencies: dependencies.length,
      resolvedRuntimeDependencies: dependencies.filter(
        (d) => d.resolution === 'resolved' && !d.typeOnly,
      ).length,
      typeOnlyDependencies: dependencies.filter((d) => d.typeOnly).length,
      externalDependencies: dependencies.filter((d) => d.resolution === 'external').length,
      unresolvedDependencies: dependencies.filter((d) => d.resolution === 'unresolved').length,
      functions: functions.length,
      candidateCallSites,
      resolvedLocalCalls: calls.length,
      unresolvedCallSites,
      resolvedTopLevelCallsOmitted,
      endpointHandlerBindings: uniqueHandlers.length,
      endpointsWithResolvedHandlers: new Set(uniqueHandlers.map((edge) => edge.endpointId)).size,
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
