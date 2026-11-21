'use strict';
const {
  ts,
  REQUEST_METHODS,
  EXTENSIONS,
  literal,
  isFunction,
  dotted,
  unwrap,
  owner,
  slash,
} = require('../common/ast.cjs');
/** Prove Express/client roles from bindings, then propagate only recognized loader/app parameters. */
function createProvenance({
  files,
  scopes,
  lookup,
  resolveModule,
  exported,
  property,
  wrappers,
  resource,
}) {
  const forcedRoles = new Map(),
    roles = new Map(),
    activeRoles = new Set(),
    routers = new Map(),
    consignLoads = [];

  function wrapperFor(module) {
    return wrappers.find(
      (w) =>
        module
          .replace(/\.[cm]?[jt]sx?$/, '')
          .split('/')
          .at(-1) === w.module || module === w.module,
    );
  }

  function importedRole(file, module, imported) {
    if (module === 'express')
      return imported === 'Router'
        ? { type: 'routerFactory' }
        : ['default', '*'].includes(imported)
          ? { type: 'expressFactory' }
          : null;
    
    if (module === 'consign') return { type: 'consignFactory' };

    if (module === 'axios')
      return REQUEST_METHODS.has(imported)
        ? { type: 'axios', method: imported }
        : ['default', '*', 'request'].includes(imported)
          ? { type: 'axios' }
          : null;

    const local = resolveModule(file, module);
    const wrapper = local && wrapperFor(module);

    if (wrapper) return { type: 'wrapper', wrapper, local };
    if (local)
      return imported === '*' ? { type: 'module', local } : role(exported(local, imported));
    
    return null;
  }

  function role(node) {
    resource.step();
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
        if (b.selector && result?.type === 'expressFactory')
          result = b.selector === 'Router' ? { type: 'routerFactory' } : null;
        else if (b.selector && result?.type === 'module')
          result = role(exported(result.local, b.selector));
        else if (b.selector && result?.type === 'axios')
          result = REQUEST_METHODS.has(b.selector)
            ? { ...result, method: b.selector }
            : b.selector === 'request'
              ? result
              : null;
      }
    } else if (isFunction(node)) result = { type: 'function', node, file };
    else if (ts.isPropertyAccessExpression(node)) {
      const base = role(node.expression);
      if (base?.type === 'expressFactory' && node.name.text === 'Router')
        result = { type: 'routerFactory' };
      else if (base?.type === 'module') result = role(exported(base.local, node.name.text));
      else if (base?.type === 'axios' && REQUEST_METHODS.has(node.name.text))
        result = { ...base, method: node.name.text };
    } else if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      if (dotted(node.expression) === 'require' && !lookup(node.expression)) {
        const source = literal(node.arguments?.[0]);
        if (source) {
          if (
            source === 'express' ||
            source === 'axios' ||
            source === 'consign' ||
            wrapperFor(source)
          )
            result = importedRole(file, source, 'default');
          else {
            const local = resolveModule(file, source);
            if (local) result = role(exported(local, 'default')) || { type: 'module', local };
          }
        }
      } else {
        const callee = role(node.expression);
        if (callee?.type === 'expressFactory' || callee?.type === 'routerFactory') {
          const identity = `${file.rel}:${owner(node)}:${ts.isVariableDeclaration(node.parent) && ts.isIdentifier(node.parent.name) ? node.parent.name.text : 'router'}`;
          result = { type: 'router', id: identity, root: callee.type === 'expressFactory' };
          routers.set(identity, result);
        } else if (
          ts.isPropertyAccessExpression(node.expression) &&
          node.expression.name.text === 'create' &&
          role(node.expression.expression)?.type === 'axios'
        )
          result = {
            type: 'axios',
            baseURL: property(node.arguments[0], 'baseURL'),
            defaultOptions: node.arguments[0],
          };
      }
    }

    activeRoles.delete(node);
    if (result) roles.set(node, result);

    return result;
  }

  function bindFunction(fn, router) {
    if (!fn || !isFunction(fn) || !fn.parameters.length || !ts.isIdentifier(fn.parameters[0].name))
      return;
    const b = scopes.get(fn)?.values.get(fn.parameters[0].name.text);
    if (b) forcedRoles.set(b, router);
  }

  // Establish exported registration functions only from proven loader/app provenance.
  for (const file of files.values())
    if (!file.bad)
      for (const call of file.calls) {
        const e = call.expression;
        if (
          ts.isPropertyAccessExpression(e) &&
          e.name.text === 'into' &&
          role(call.arguments[0])?.type === 'router'
        ) {
          const directories = [];
          let chain = e.expression;
          while (ts.isCallExpression(chain) && ts.isPropertyAccessExpression(chain.expression)) {
            if (
              ['include', 'then'].includes(chain.expression.name.text) &&
              literal(chain.arguments[0])
            )
              directories.push(literal(chain.arguments[0]));
            chain = chain.expression.expression;
          }
          if (ts.isCallExpression(chain) && role(chain.expression)?.type === 'consignFactory') {
            const router = role(call.arguments[0]);
            for (const directory of directories) {
              const prefix = slash(directory).replace(/^\.\//, '').replace(/\/$/, '');
              for (const loaded of files.values()) {
                resource.step();
                if (
                  !loaded.bad &&
                  (loaded.rel === prefix ||
                    loaded.rel.startsWith(prefix + '/') ||
                    EXTENSIONS.some((ext) => loaded.rel === prefix + ext))
                ) {
                  bindFunction(unwrap(exported(loaded, 'default')), router);
                  if (!chain.arguments.length) {
                    resource.record();
                    consignLoads.push({ file, call, target: loaded });
                  }
                }
              }
            }
          }
        }

        const callee = role(e);
        if (callee?.type === 'function' && role(call.arguments[0])?.type === 'router')
          bindFunction(callee.node, role(call.arguments[0]));
      }

  // Some files were examined before their loader bound a parameter.
  roles.clear();

  return { role, wrapperFor, routers, consignLoads };
}

module.exports = { createProvenance };
