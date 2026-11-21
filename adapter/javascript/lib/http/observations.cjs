'use strict';
const {
  ts,
  HTTP,
  REQUEST_METHODS,
  literal,
  dotted,
  unwrap,
  owner,
  location,
} = require('../common/ast.cjs');
/** Observe route registrations and supported outbound HTTP clients; lookalikes never imply provenance. */
function collectHttpObservations({
  files,
  kind,
  lookup,
  property,
  resolveModule,
  wrapperFor,
  role,
  value,
  resolvedPath,
  method,
  optionMethod,
  routeChain,
  resource,
  diag,
  semanticHash,
}) {
  const endpointCandidates = [], calls = [], mounts = [];
  const wrapperFiles = new Set();

  for (const file of files.values())
    for (const b of file.bindings) {
      const imported =
        b.module ||
        (b.init && ts.isCallExpression(b.init) && dotted(b.init.expression) === 'require'
          ? literal(b.init.arguments[0])
          : null);
      if (imported) {
        const local = resolveModule(file, imported);
        if (local && wrapperFor(imported)) wrapperFiles.add(local.absolute);
      }
    }

  for (const file of files.values())
    if (!file.bad)
      for (const call of file.calls) {
        resource.step();
        const e = call.expression;
        if (kind === 'backend' && ts.isPropertyAccessExpression(e)) {
          const parent = role(e.expression);
          if (e.name.text === 'use' && parent?.type === 'router') {
            const firstIsRouter = role(call.arguments[0])?.type === 'router';
            const prefix = firstIsRouter
              ? { path: '/', resolution: 'literal' }
              : resolvedPath(call.arguments[0], 'unknown', null, true);
            
              if (prefix.unsupportedPattern)
              diag(
                file,
                call,
                'UNSUPPORTED_EXPRESS_PATTERN',
                'Express path pattern requires runtime-specific matching and is not normalized.',
              );

            for (const arg of call.arguments.slice(firstIsRouter ? 0 : 1)) {
              const child = role(arg);
              if (child?.type === 'router')
                mounts.push({
                  parent: parent.id,
                  child: child.id,
                  prefix: prefix.path,
                  file,
                  call,
                });
            }
          }

          if (HTTP.has(e.name.text)) {
            const chain = routeChain(e.expression);
            const router = parent?.type === 'router' ? parent : chain?.router;
            const routeNode = chain ? chain.route : call.arguments[0];
            const handlers = [...call.arguments].slice(chain ? 0 : 1);
            if (router && handlers.length) {
              const alternatives =
                routeNode && ts.isArrayLiteralExpression(routeNode)
                  ? [...routeNode.elements]
                  : [routeNode];
              for (const route of alternatives) {
                resource.record();
                endpointCandidates.push({
                  file,
                  call,
                  router,
                  route,
                  method: e.name.text.toUpperCase(),
                  handlers,
                });
              }
            }
          }
        }

        if (wrapperFiles.has(file.absolute)) continue;

        let source = null,
          methodName = 'UNKNOWN',
          argument = null,
          baseURL = null,
          assumedTarget = 'unknown';

        if (ts.isPropertyAccessExpression(e)) {
          const client = role(e.expression);
          const m = e.name.text;
          if (client?.type === 'wrapper' && client.wrapper.kind === 'server') {
            if (/^(get|post|put|patch|delete|head|options)_request$/.test(m)) {
              source = 'wrapper';
              methodName = m.split('_')[0].toUpperCase();
              argument = call.arguments[0];
            } else if (REQUEST_METHODS.has(m)) {
              source = 'wrapper';
              methodName = m.toUpperCase();
              argument =
                m === 'get' && property(call.arguments[0], 'path')
                  ? property(call.arguments[0], 'path')
                  : call.arguments[0];
            }
            assumedTarget = 'backend';
          } else if (client?.type === 'axios' && REQUEST_METHODS.has(m)) {
            source = 'axios';
            methodName = m.toUpperCase();
            argument = call.arguments[0];
            baseURL = client.baseURL;
          } else if (client?.type === 'axios' && m === 'request') {
            source = 'axios';
            methodName = optionMethod(call.arguments[0], optionMethod(client.defaultOptions));
            argument = property(call.arguments[0], 'url');
            baseURL = property(call.arguments[0], 'baseURL') || client.baseURL;
          }
        } else if (ts.isIdentifier(e)) {
          const client = role(e);
          if (client?.type === 'wrapper' && client.wrapper.kind === 'method-first') {
            source = 'wrapper';
            methodName = method(call.arguments[0]);
            argument = call.arguments[1];
            if (
              client.wrapper.methodPolicy === 'get-or-post' &&
              methodName !== 'UNKNOWN' &&
              methodName !== 'GET'
            )
              methodName = 'POST';
            if (client.wrapper.relativeTarget === 'backend' && value(argument).text.startsWith('/'))
              assumedTarget = 'backend';
          } else if (client?.type === 'axios') {
            source = 'axios';
            if (client.method) {
              methodName = client.method.toUpperCase();
              argument = call.arguments[0];
            } else if (
              ts.isObjectLiteralExpression(unwrap(call.arguments[0]) || file.sf) ||
              property(call.arguments[0], 'url')
            ) {
              argument = property(call.arguments[0], 'url');
              methodName = optionMethod(call.arguments[0], optionMethod(client.defaultOptions));
              baseURL = property(call.arguments[0], 'baseURL') || client.baseURL;
            } else {
              argument = call.arguments[0];
              methodName = optionMethod(call.arguments[1], optionMethod(client.defaultOptions));
              baseURL = client.baseURL;
            }
          } else if (e.text === 'fetch' && !lookup(e)) {
            source = 'fetch';
            argument = call.arguments[0];
            methodName = optionMethod(call.arguments[1]);
          }
        }

        if (source) {
          const route = resolvedPath(argument, assumedTarget, baseURL);
          if (route.resolution === 'unresolved')
            diag(
              file,
              call,
              'UNRESOLVED_CALL_PATH',
              'The request path could not be resolved statically.',
            );
          if (methodName === 'UNKNOWN')
            diag(
              file,
              call,
              'UNRESOLVED_HTTP_METHOD',
              'The request HTTP method could not be resolved statically.',
            );
          
          resource.record();
          calls.push({
            ...location(file, call),
            method: methodName,
            ...route,
            caller: owner(call),
            source,
            bodyFingerprint: semanticHash(call),
          });
        }
      }

  return { wrapperFiles, endpointCandidates, calls, mounts };
}

module.exports = { collectHttpObservations };
