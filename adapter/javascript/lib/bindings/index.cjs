'use strict';
const path = require('node:path');
const {
  ts,
  EXTENSIONS,
  literal,
  isFunction,
  memberName,
  dotted,
  unwrap,
} = require('../common/ast.cjs');
/** Build lexical scopes and exports once. Relative-module resolution is confined to admitted files. */
function buildBindings({ root, files, resource }) {
  const scopes = new WeakMap();
  const inside = (absolute) => {
    const relative = path.relative(root, absolute);
    return relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
  };

  function binding(scope, id, node, extra = {}) {
    if (!id) return;
    const found = { name: id, node, scope, file: scope.file, ...extra };
    scope.values.set(id, found);
    scope.file.bindings.push(found);
    return found;
  }

  function declarePattern(scope, pattern, node, extra) {
    if (ts.isIdentifier(pattern)) return binding(scope, pattern.text, node, extra);
    if (ts.isObjectBindingPattern(pattern))
      for (const element of pattern.elements)
        if (ts.isIdentifier(element.name))
          binding(scope, element.name.text, node, {
            ...extra,
            selector: memberName(element.propertyName || element.name),
          });
  }

  function visit(file, node, scope) {
    resource.step();
    scopes.set(node, scope);
    if (ts.isFunctionDeclaration(node) && node.name)
      binding(scope, node.name.text, node, { init: node });

    if (isFunction(node)) {
      scope = { parent: scope, values: new Map(), file, node };
      scopes.set(node, scope);
      for (const parameter of node.parameters || [])
        declarePattern(scope, parameter.name, parameter, { parameter: true });
    } else if (ts.isBlock(node)) {
      scope = { parent: scope, values: new Map(), file, node };
      scopes.set(node, scope);
    }

    if (ts.isImportDeclaration(node) && literal(node.moduleSpecifier)) {
      const source = node.moduleSpecifier.text;
      if (node.importClause?.name)
        binding(scope, node.importClause.name.text, node, { module: source, imported: 'default' });
      const named = node.importClause?.namedBindings;
      if (named && ts.isNamespaceImport(named))
        binding(scope, named.name.text, node, { module: source, imported: '*' });
      if (named && ts.isNamedImports(named))
        for (const element of named.elements)
          binding(scope, element.name.text, node, {
            module: source,
            imported: memberName(element.propertyName || element.name),
          });
    }

    if (ts.isVariableDeclaration(node))
      declarePattern(scope, node.name, node, { init: node.initializer });
    if (
      ts.isImportEqualsDeclaration(node) &&
      ts.isExternalModuleReference(node.moduleReference) &&
      literal(node.moduleReference.expression)
    )
      binding(scope, node.name.text, node, {
        module: literal(node.moduleReference.expression),
        imported: '*',
        typeOnly: !!node.isTypeOnly,
      });

    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      const key = dotted(node.left);
      if (key === 'module.exports') file.exports.set('default', node.right);
      else if (key.startsWith('exports.')) file.exports.set(key.substring(8), node.right);
      else if (key.startsWith('module.exports.')) file.exports.set(key.substring(15), node.right);
      else if (ts.isIdentifier(node.left)) file.assignments.push(node.left);
    }

    if (ts.isExportAssignment(node)) file.exports.set('default', node.expression);

    if (node.modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword)) {
      if (node.modifiers.some((m) => m.kind === ts.SyntaxKind.DefaultKeyword))
        file.exports.set('default', node);
      else if (node.name && ts.isIdentifier(node.name)) file.exports.set(node.name.text, node);
      else if (ts.isVariableStatement(node))
        for (const declaration of node.declarationList.declarations)
          if (ts.isIdentifier(declaration.name))
            file.exports.set(declaration.name.text, declaration.initializer);
    }

    if (ts.isCallExpression(node)) file.calls.push(node);
    ts.forEachChild(node, (child) => visit(file, child, scope));
  }

  for (const file of files.values())
    if (!file.bad) {
      file.scope = { parent: null, values: new Map(), file, node: file.sf };
      visit(file, file.sf, file.scope);
    }

  function lookup(node, id = node?.text) {
    for (let scope = scopes.get(node); scope; scope = scope.parent) {
      resource.step();
      if (scope.values.has(id)) return scope.values.get(id);
    }

    return null;
  }

  for (const file of files.values())
    for (const assignment of file.assignments) {
      const b = lookup(assignment);
      if (b) b.assigned = true;
      else
        binding(file.scope, assignment.text, assignment.parent, {
          init: assignment.parent.right,
          implicit: true,
        });
    }

  function resolveModule(file, source) {
    resource.step();
    if (!source?.startsWith('.')) return null;
    const base = path.resolve(path.dirname(file.absolute), source);
    if (!inside(base)) return null;

    return (
      [
        base,
        ...EXTENSIONS.map((ext) => base + ext),
        ...EXTENSIONS.map((ext) => path.join(base, 'index' + ext)),
      ]
        .map((p) => files.get(p))
        .find((f) => f && !f.bad) || null
    );
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
    if (ts.isIdentifier(node)) {
      const b = lookup(node);
      if (b && !b.assigned) return property(b.init, key, depth + 1);
    }

    if (ts.isObjectLiteralExpression(node))
      for (const item of node.properties) {
        if (memberName(item.name) === key)
          return ts.isPropertyAssignment(item)
            ? item.initializer
            : ts.isShorthandPropertyAssignment(item)
              ? item.name
              : isFunction(item)
                ? item
                : null;
      }

    return null;
  }

  return { scopes, lookup, resolveModule, exported, property };
}

module.exports = { buildBindings };
