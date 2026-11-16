'use strict';
/** Inventory function identities, exports and mutations before resolving graph edges. */
function buildInventory({
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
}) {
  const functionRecords = new Map(),
    nodeFiles = new WeakMap(),
    mutations = new Set(),
    exportDeclarations = new Map(),
    localExports = new Map();
  const identifierName = (node) => (node && ts.isIdentifier(node) ? node.text : null);
  function safeDotted(node) {
    if (!node) return '';
    if (ts.isIdentifier(node)) return node.text;
    if (ts.isPropertyAccessExpression(node)) {
      const base = safeDotted(node.expression);
      return base ? `${base}.${node.name.text}` : '';
    }
    return '';
  }
  function functionLabel(node) {
    if (identifierName(node.name)) return identifierName(node.name);
    const parent = node.parent;
    if (ts.isVariableDeclaration(parent)) return identifierName(parent.name) || '<binding>';
    if (ts.isPropertyAssignment(parent)) return identifierName(parent.name) || '<property>';
    if (ts.isBinaryExpression(parent)) return safeDotted(parent.left) || '<assignment>';
    if (ts.isExportAssignment(parent)) return '<default>';
    if (ts.isCallExpression(parent) || ts.isNewExpression(parent))
      return `${safeDotted(parent.expression) || '<call>'}:callback:${[...(parent.arguments || [])].indexOf(node)}`;
    return '<anonymous>';
  }
  function scan(file, node, parentFunction = null) {
    resource.step();
    nodeFiles.set(node, file);
    if (isFunction(node)) {
      const label = functionLabel(node);
      const record = {
        id: id('function', [file.rel, parentFunction?.id || '<module>', label]),
        ...location(file, node),
        name: label,
        bodyFingerprint: semanticHash(node),
        unresolvedCalls: 0,
      };
      resource.record();
      functions.push(record);
      functionRecords.set(node, record);
      parentFunction = record;
    }
    if (ts.isExportDeclaration(node)) exportDeclarations.get(file).push(node);
    function addExport(exportName, value) {
      const exports = localExports.get(file);
      exports.set(exportName, exports.has(exportName) ? null : value);
    }
    if (ts.isExportAssignment(node)) addExport('default', node.expression);
    if (node.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword)) {
      if (node.modifiers.some((modifier) => modifier.kind === ts.SyntaxKind.DefaultKeyword))
        addExport('default', node);
      else if (ts.isVariableStatement(node)) {
        for (const declaration of node.declarationList.declarations)
          if (ts.isIdentifier(declaration.name))
            addExport(declaration.name.text, declaration.initializer);
      } else if (identifierName(node.name) && (!ts.isFunctionDeclaration(node) || node.body))
        addExport(node.name.text, node);
    }
    if (
      !parentFunction &&
      ts.isBinaryExpression(node) &&
      node.operatorToken.kind === ts.SyntaxKind.EqualsToken
    ) {
      const key = safeDotted(node.left);
      let base = node.left;
      while (ts.isPropertyAccessExpression(base)) base = base.expression;
      if (!lookup(base)) {
        if (key === 'module.exports') addExport('default', node.right);
        else if (key.startsWith('module.exports.')) addExport(key.substring(15), node.right);
        else if (key.startsWith('exports.')) addExport(key.substring(8), node.right);
      }
    }
    if (
      (ts.isBinaryExpression(node) &&
        node.operatorToken.kind >= ts.SyntaxKind.FirstAssignment &&
        node.operatorToken.kind <= ts.SyntaxKind.LastAssignment) ||
      ((ts.isPrefixUnaryExpression(node) || ts.isPostfixUnaryExpression(node)) &&
        [ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator))
    ) {
      let left = ts.isBinaryExpression(node) ? node.left : node.operand;
      while (ts.isPropertyAccessExpression(left) || ts.isElementAccessExpression(left))
        left = left.expression;
      if (ts.isIdentifier(left)) {
        const binding = lookup(left);
        if (binding) mutations.add(binding);
      }
    }
    ts.forEachChild(node, (child) => scan(file, child, parentFunction));
  }
  for (const file of validFiles) {
    exportDeclarations.set(file, []);
    localExports.set(file, new Map());
    scan(file, file.sf);
  }

  return { functionRecords, nodeFiles, mutations, exportDeclarations, localExports };
}
module.exports = { buildInventory };
