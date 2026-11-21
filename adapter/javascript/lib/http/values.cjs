'use strict';
const {
  ts,
  HTTP,
  REQUEST_METHODS,
  hash,
  isFunction,
  memberName,
  dotted,
  unwrap,
} = require('../common/ast.cjs');
/** Bounded symbolic values/URLs and handler references. Secrets and unresolved targets stay redacted. */
function createHttpValues({
  scopes,
  lookup,
  resolveModule,
  exported,
  property,
  role,
  backendEnv,
  backendOrigins,
  resource,
  semanticHash,
}) {
  const dynamic = () => ({ text: '{param}', dynamic: true, base: 'unknown' });
  function value(node, seen = new Set()) {
    resource.step();
    node = unwrap(node);
    if (!node || seen.has(node) || seen.size > 32) return dynamic();
    seen = new Set(seen).add(node);
    const env = dotted(node).match(/^(?:import\.meta\.env|process\.env)\.([A-Za-z0-9_]+)$/);
    
    if (env)
      return {
        text: backendEnv.has(env[1]) ? '{backend}' : '{unknown}',
        dynamic: true,
        base: backendEnv.has(env[1]) ? 'backend' : 'unknown',
      };

    if (ts.isStringLiteralLike(node)) return { text: node.text, dynamic: false, base: null };

    if (ts.isIdentifier(node)) {
      if (/token|password|secret|credential|api.?key/i.test(node.text)) return dynamic();
      const b = lookup(node);
      if (b?.module) {
        const local = resolveModule(b.file, b.module);
        return value(exported(local, b.imported), seen);
      }
      if (b && !b.assigned && b.init)
        return value(b.selector ? property(b.init, b.selector) : b.init, seen);
      return dynamic();
    }

    if (ts.isTemplateExpression(node)) {
      let result = { text: node.head.text, dynamic: false, base: null };
      for (const span of node.templateSpans) {
        const item = value(span.expression, seen);
        result = {
          text: result.text + item.text + span.literal.text,
          dynamic: result.dynamic || item.dynamic,
          base: result.base || item.base,
        };
      }
      return result;
    }

    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const a = value(node.left, seen),
        b = value(node.right, seen);
      return { text: a.text + b.text, dynamic: a.dynamic || b.dynamic, base: a.base || b.base };
    }

    if (ts.isPropertyAccessExpression(node)) {
      const base = role(node.expression);
      if (
        base?.type === 'wrapper' &&
        base.wrapper.kind === 'server' &&
        ['SERVER_URL', 'local_url'].includes(node.name.text)
      )
        return { text: '{backend}', dynamic: true, base: 'backend' };
      if (base?.type === 'module') return value(exported(base.local, node.name.text), seen);
      const prop = property(node.expression, node.name.text);
      if (prop) return value(prop, seen);
    }

    if (
      ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(node.expression) &&
      node.expression.name.text === 'get_url' &&
      role(node.expression.expression)?.type === 'wrapper'
    )
      return { text: '{backend}', dynamic: true, base: 'backend' };
    
      if (ts.isConditionalExpression(node)) {
      const a = value(node.whenTrue, seen),
        b = value(node.whenFalse, seen);
      if (a.text === b.text && a.base === b.base) return a;
    }

    return dynamic();
  }

  function normalize(text) {
    return (
      text
        .replace(/:[A-Za-z_$][\w$]*(?:\([^)]*\))?[?+*]?/g, '{param}')
        .replace(/\{[^}]*\}/g, '{param}')
        .replace(/\/$/, '') || '/'
    );
  }

  function resolvedPath(node, assumedTarget = 'unknown', baseNode = null, express = false) {
    let parsed = value(node);
    let target = assumedTarget;
    
    if (
      baseNode &&
      !/^(?:https?:)?\/\//.test(parsed.text) &&
      !parsed.text.startsWith('{backend}')
    ) {
      const base = value(baseNode);
      parsed = {
        text: base.text.replace(/\/$/, '') + '/' + parsed.text.replace(/^\//, ''),
        dynamic: parsed.dynamic || base.dynamic,
        base: base.base,
      };
    }

    let text = parsed.text;

    if (text.startsWith('{backend}')) {
      text = text.substring(9) || '/';
      target = 'backend';
    } else if (/^https?:\/\//i.test(text)) {
      try {
        const url = new URL(text);
        target = /\{|%7b/i.test(url.host)
          ? 'unknown'
          : backendOrigins.has(url.origin)
            ? 'backend'
            : 'external';
        text = url.pathname.replace(/%7Bparam%7D/gi, '{param}');
      } catch {
        return { path: null, normalizedPath: null, target: 'unknown', resolution: 'unresolved' };
      }
    } else if (text.startsWith('//'))
      return { path: null, normalizedPath: null, target: 'external', resolution: 'unresolved' };
    
    if (!text.startsWith('/') || text.startsWith('//'))
      return { path: null, normalizedPath: null, target, resolution: 'unresolved' };

    // Never return URL credentials, query values, or fragments from target source.
    if (!express) text = text.split(/[?#]/, 1)[0] || '/';
    else {
      // Preserve Express's optional parameter marker, but never URL query values.
      const query = text.indexOf('?');
      if (
        query >= 0 &&
        (!/:[\w$]+$/.test(text.substring(0, query)) ||
          (query + 1 < text.length && text[query + 1] !== '/'))
      )
        text = text.substring(0, query) || '/';
      text = text.split('#', 1)[0];
    }

    const unsupportedPattern = express && /[?+*()[\]{}]/.test(text);
    
    return {
      path: text,
      normalizedPath: unsupportedPattern ? null : normalize(text),
      target,
      resolution: unsupportedPattern
        ? 'unresolved'
        : /\{[^}]*\}/.test(text)
          ? 'template'
          : 'literal',
      ...(unsupportedPattern ? { unsupportedPattern: true } : {}),
    };
  }

  function method(node, fallback = null) {
    const result = node ? value(node) : null;
    return result && !result.dynamic && REQUEST_METHODS.has(result.text.toLowerCase())
      ? result.text.toUpperCase()
      : !node && fallback
        ? fallback
        : 'UNKNOWN';
  }

  function optionMethod(node, fallback = 'GET', seen = new Set()) {
    node = unwrap(node);
    if (!node) return fallback;
    if (seen.has(node) || seen.size > 16) return 'UNKNOWN';
    seen = new Set(seen).add(node);
    
    if (ts.isIdentifier(node)) {
      const b = lookup(node);
      return b && !b.assigned && b.init !== node ? optionMethod(b.init, fallback, seen) : 'UNKNOWN';
    }

    if (
      !ts.isObjectLiteralExpression(node) ||
      node.properties.some(
        (p) => ts.isSpreadAssignment(p) || (p.name && ts.isComputedPropertyName(p.name)),
      )
    )
      return 'UNKNOWN';
   
    return method(property(node, 'method'), fallback);
  }

  function routeChain(node) {
    if (!node || !ts.isCallExpression(node) || !ts.isPropertyAccessExpression(node.expression))
      return null;
    const e = node.expression;
    if (e.name.text === 'route' && role(e.expression)?.type === 'router')
      return { router: role(e.expression), route: node.arguments[0] };
    if (HTTP.has(e.name.text)) return routeChain(e.expression);
    return null;
  }

  function handler(node, file, seen = new Set()) {
    resource.step();
    node = unwrap(node);
    if (!node || seen.has(node)) return { label: '<unresolved>', fingerprint: hash('unresolved') };
    seen = new Set(seen).add(node);
    
    if (isFunction(node))
      return {
        label: memberName(node.name) || '<inline>',
        fingerprint: semanticHash(node),
        file: scopes.get(node).file.rel,
        line: scopes.get(node).file.sf.getLineAndCharacterOfPosition(node.getStart()).line + 1,
      };
    
    if (ts.isCallExpression(node) && dotted(node.expression) === 'require') {
      const target = role(node);
      if (target?.type === 'function') return handler(target.node, file, seen);
    }

    if (ts.isIdentifier(node)) {
      const b = lookup(node);
      const definition = b?.module
        ? exported(resolveModule(b.file, b.module), b.imported)
        : b?.init;
      if (definition && definition !== node)
        return { ...handler(definition, file, seen), label: node.text };
    }

    if (ts.isPropertyAccessExpression(node)) {
      const ref = role(node.expression);
      let definition =
        ref?.type === 'module'
          ? exported(ref.local, node.name.text)
          : property(node.expression, node.name.text);
      
      if (!definition && ts.isIdentifier(node.expression)) {
        const b = lookup(node.expression);
        if (b?.module) definition = exported(resolveModule(b.file, b.module), node.name.text);
      }

      if (definition)
        return { ...handler(definition, file, seen), label: dotted(node) || '<reference>' };
    }

    return { label: dotted(node) || '<expression>', fingerprint: semanticHash(node) };
  }

  return { value, normalize, resolvedPath, method, optionMethod, routeChain, handler };
}

module.exports = { createHttpValues };
