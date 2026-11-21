'use strict';

const fs = require('node:fs');
const path = require('node:path');
const ast = require('./common/ast.cjs');
const { createResourceBudget } = require('./source/resources.cjs');
const { discoverSourceFiles } = require('./source/discovery.cjs');
const { buildBindings } = require('./bindings/index.cjs');
const { createProvenance } = require('./provenance/index.cjs');
const { createHttpValues } = require('./http/values.cjs');
const { collectHttpObservations } = require('./http/observations.cjs');
const { buildEndpoints } = require('./http/endpoints.cjs');
const { finalizeRecords, sourceCoverage, orderedDiagnostics } = require('./output.cjs');
const { buildArchitecture } = require('../architecture.cjs');

/**
 * Repository-scoped pass orchestration. Discovery -> lexical bindings -> proven
 * framework/loader roles -> HTTP observations -> mounted endpoints -> architecture.
 * Cross-file maps live only in this invocation. Nothing loads analyzed modules.
 */
function analyze({ root, kind, name, config = {} }) {
  const started = performance.now();
  if (
    !path.isAbsolute(root) ||
    !['backend', 'frontend'].includes(kind) ||
    typeof name !== 'string' ||
    !name.trim()
  ) {
    throw new Error('Provide absolute root, backend|frontend kind, and a component name.');
  }

  root = fs.realpathSync(root);
  if (!fs.statSync(root).isDirectory()) throw new Error('The analysis root must be a directory.');
  
  const resource = createResourceBudget(config);
  const diagnostics = [];
  const files = discoverSourceFiles({ root, config, diagnostics, resource });
  const wrappers = config.wrappers || [
    { module: 'server_requests', kind: 'server' },
    {
      module: 'dataUtility',
      kind: 'method-first',
      relativeTarget: 'backend',
      methodPolicy: 'get-or-post',
    },
    { module: 'dataUtilityImage', kind: 'method-first' },
  ];

  if (
    !Array.isArray(wrappers) ||
    wrappers.some(
      (wrapper) =>
        typeof wrapper.module !== 'string' || !['server', 'method-first'].includes(wrapper.kind),
    )
  )
    throw new Error('Invalid wrapper configuration.');
  
  const semanticHash = ast.createSemanticHasher(resource);
  const bindings = buildBindings({ root, files, resource });
  const provenance = createProvenance({ files, ...bindings, wrappers, resource });
  const values = createHttpValues({
    ...bindings,
    ...provenance,
    resource,
    semanticHash,
    backendEnv: new Set(config.backendEnv || ['VITE_NUTROSAL_BE', 'VITE_LOCAL_URL']),
    backendOrigins: new Set((config.backendOrigins || []).map((value) => new URL(value).origin)),
  });

  const diag = (file, node, code, message) =>
    diagnostics.push({
      file: file?.rel || '',
      line: node ? file.sf.getLineAndCharacterOfPosition(node.getStart(file.sf)).line + 1 : 1,
      code,
      message,
    });

  const observations = collectHttpObservations({
    files,
    kind,
    ...bindings,
    ...provenance,
    ...values,
    resource,
    diag,
    semanticHash,
  });

  const endpointHandlerNodes = new WeakMap();
  const endpoints = buildEndpoints({
    ...observations,
    ...provenance,
    ...values,
    endpointHandlerNodes,
    resource,
    diag,
  });

  finalizeRecords(endpoints, 'endpoint', name, kind);
  finalizeRecords(observations.calls, 'call', name, kind);
  const coverage = sourceCoverage(diagnostics);
  const architecture = buildArchitecture({
    ...ast,
    files,
    name,
    kind,
    ...bindings,
    endpoints,
    endpointHandlerNodes,
    consignLoads: provenance.consignLoads,
    ...coverage,
    resource,
    semanticHash,
  });
  
  const validFiles = [...files.values()].filter((file) => !file.bad);
  return {
    schemaVersion: 1,
    component: { name, kind },
    filesAnalyzed: validFiles.length,
    endpoints,
    calls: observations.calls,
    diagnostics: orderedDiagnostics(diagnostics),
    architecture,
    stats: {
      parser: 'typescript',
      parserVersion: ast.ts.version,
      sourceFiles: files.size,
      parseErrorFiles: files.size - validFiles.length,
      endpointRegistrations: endpoints.length,
      uniqueMethodPaths: new Set(
        endpoints
          .filter((endpoint) => endpoint.path !== null)
          .map((endpoint) => `${endpoint.method} ${endpoint.path}`),
      ).size,
      callSites: observations.calls.length,
      unresolvedCallPaths: observations.calls.filter((call) => call.path === null).length,
      wrapperFilesExcluded: observations.wrapperFiles.size,
      elapsedMs: Math.round((performance.now() - started) * 100) / 100,
      peakRssKiB: process.resourceUsage().maxRSS,
      resourceUsage: { ...resource.usage },
      resourceLimits: { ...resource.limits },
    },
  };
}

module.exports = { analyze };
