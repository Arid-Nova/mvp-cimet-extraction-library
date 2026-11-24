'use strict';
const { hash, owner, location } = require('../common/ast.cjs');
/** Compose imported-router mounts after all registrations are known; cycles remain unresolved. */
function buildEndpoints({
  endpointCandidates,
  mounts,
  routers,
  handler,
  resolvedPath,
  normalize,
  endpointHandlerNodes,
  resource,
  diag,
}) {
  function prefixes(routerId, seen = new Set()) {
    resource.step();
    if (seen.has(routerId)) return [null];

    const router = routers.get(routerId);
    if (router?.root) return [''];

    const edges = mounts.filter((edge) => edge.child === routerId);
    if (!edges.length) return [null];

    const result = [];
    for (const edge of edges)
      for (const prefix of prefixes(edge.parent, new Set(seen).add(routerId))) {
        resource.record();
        result.push(
          prefix === null || edge.prefix === null
            ? null
            : prefix.replace(/\/$/, '') + (edge.prefix === '/' ? '' : edge.prefix),
        );
      }

    return [...new Set(result)];
  }

  const endpoints = [];

  for (const candidate of endpointCandidates) {
    const { file, call, router, handlers } = candidate;
    const own = resolvedPath(candidate.route, 'unknown', null, true);
    const resolvedHandlers = handlers.map((item) => handler(item, file));
    const last = resolvedHandlers.at(-1);

    for (const prefix of prefixes(router.id)) {
      const complete =
        prefix !== null && own.path !== null ? prefix.replace(/\/$/, '') + own.path : null;

      if (own.path === null)
        diag(
          file,
          call,
          'UNRESOLVED_ENDPOINT_PATH',
          'The Express registration path could not be resolved statically.',
        );

      if (own.unsupportedPattern || (complete && /[?+*()[\]{}]/.test(complete)))
        diag(
          file,
          call,
          'UNSUPPORTED_EXPRESS_PATTERN',
          'Express path pattern requires runtime-specific matching and is not normalized.',
        );

      if (prefix === null)
        diag(
          file,
          call,
          'UNRESOLVED_ROUTER_MOUNT',
          'No statically proven application mount was found for this router.',
        );

      const endpoint = {
        ...location(file, call),
        method: candidate.method,
        path: complete,
        normalizedPath:
          complete === null || /[?+*()[\]{}]/.test(complete) ? null : normalize(complete),
        handler: last.label,
        ...(last.file ? { handlerFile: last.file, handlerLine: last.line } : {}),
        caller: owner(call),
        bodyFingerprint: hash(resolvedHandlers.map((item) => item.fingerprint).join(':')),
      };

      resource.record();
      endpoints.push(endpoint);
      endpointHandlerNodes.set(endpoint, handlers);
    }
  }

  return endpoints;
}

module.exports = { buildEndpoints };
