'use strict';
/** Conservative local value/export resolution: ambiguous, mutable and type-only targets stay unknown. */
function createValueResolver({
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
}) {
  const identifierName = (node) => (node && ts.isIdentifier(node) ? node.text : null);
  function typeOnlyBinding(binding) {
    if (binding.typeOnly) return true;
    if (!binding.module || !ts.isImportDeclaration(binding.node)) return false;
    if (binding.node.importClause?.isTypeOnly) return true;
    const named = binding.node.importClause?.namedBindings;
    return (
      named &&
      ts.isNamedImports(named) &&
      named.elements.some((element) => element.name.text === binding.name && element.isTypeOnly)
    );
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
    return imported === '*'
      ? { type: 'module', file: target }
      : exportedValue(target, imported, seen);
  }
  function exportedValue(file, exportedName, seen = new Set()) {
    if (!file || seen.size > 64) return null;
    const key = `${file.rel}:${exportedName}`;
    if (seen.has(key)) return null;
    seen = new Set(seen).add(key);
    if (localExports.get(file).has(exportedName))
      return resolveValue(localExports.get(file).get(exportedName), seen);
    const defaultNode = localExports.get(file).get('default');
    if (exportedName !== 'default' && defaultNode) {
      const result = memberValue(resolveValue(defaultNode, seen), exportedName, seen);
      if (result) return result;
    }
    const matches = [],
      stars = [];
    for (const declaration of exportDeclarations.get(file) || []) {
      if (declaration.isTypeOnly) continue;
      const source = literal(declaration.moduleSpecifier),
        clause = declaration.exportClause;
      if (clause && ts.isNamedExports(clause)) {
        for (const element of clause.elements)
          if (!element.isTypeOnly && element.name.text === exportedName) {
            const localName = (element.propertyName || element.name).text;
            const result = source
              ? importValue(file, source, localName, seen)
              : resolveBinding(lookup(element, localName), seen);
            if (result) matches.push(result);
          }
      } else if (
        clause &&
        ts.isNamespaceExport(clause) &&
        clause.name.text === exportedName &&
        source
      ) {
        const result = importValue(file, source, '*', seen);
        if (result) matches.push(result);
      } else if (!clause && source && exportedName !== 'default') {
        stars.push(source);
      }
    }
    if (!matches.length)
      for (const source of stars) {
        const result = importValue(file, source, exportedName, seen);
        if (result) matches.push(result);
      }
    // Conflicting star exports are not assigned an arbitrary target.
    if (
      !matches.length ||
      matches.some(
        (value) =>
          value.type !== matches[0].type ||
          value.node !== matches[0].node ||
          value.file !== matches[0].file,
      )
    )
      return null;
    return matches[0];
  }
  function resolveBinding(binding, seen) {
    if (!binding || binding.assigned || mutations.has(binding) || typeOnlyBinding(binding))
      return null;
    if (binding.module && ts.isImportEqualsDeclaration(binding.node))
      return (
        importValue(binding.file, binding.module, 'default', seen) ||
        importValue(binding.file, binding.module, '*', seen)
      );
    if (binding.module) return importValue(binding.file, binding.module, binding.imported, seen);
    const value = resolveValue(binding.init, seen);
    return binding.selector ? memberValue(value, binding.selector, seen) : value;
  }
  function memberValue(base, member, seen) {
    if (!base || !member) return null;
    if (base.type === 'module') return exportedValue(base.file, member, seen);
    if (base.type === 'object') {
      // A spread/computed property could override a named function property.
      if (
        base.node.properties.some(
          (p) => ts.isSpreadAssignment(p) || (p.name && ts.isComputedPropertyName(p.name)),
        )
      )
        return null;
      const found = base.node.properties.filter(
        (p) => (identifierName(p.name) || literal(p.name)) === member,
      );
      if (found.length !== 1) return null;
      const item = found[0];
      return ts.isPropertyAssignment(item)
        ? resolveValue(item.initializer, seen)
        : ts.isShorthandPropertyAssignment(item)
          ? resolveValue(item.name, seen)
          : ts.isMethodDeclaration(item)
            ? { type: 'function', node: item }
            : null;
    }
    return null;
  }
  function resolveValue(node, seen = new Set()) {
    resource.step();
    node = unwrap(node);
    if (!node || seen.has(node) || seen.size > 64) return null;
    seen = new Set(seen).add(node);
    if (
      isFunction(node) &&
      !ts.isGetAccessorDeclaration(node) &&
      !ts.isSetAccessorDeclaration(node)
    )
      return { type: 'function', node };
    if (ts.isIdentifier(node)) return resolveBinding(lookup(node), seen);
    if (ts.isObjectLiteralExpression(node)) return { type: 'object', node };
    if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node))
      return memberValue(
        resolveValue(node.expression, seen),
        ts.isPropertyAccessExpression(node) ? node.name.text : literal(node.argumentExpression),
        seen,
      );
    if (ts.isCallExpression(node) && requireIdentifier(node.expression)) {
      const local = resolveModule(nodeFiles.get(node), literal(node.arguments[0]));
      if (local) return exportedValue(local, 'default', seen) || { type: 'module', file: local };
    }
    return null;
  }

  return { requireIdentifier, resolveValue };
}
module.exports = { createValueResolver };
