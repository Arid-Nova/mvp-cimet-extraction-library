'use strict';
// Shared, side-effect-free syntax helpers. These never resolve or execute target code.
const path = require('node:path');
const crypto = require('node:crypto');
const ts = require('typescript');
const HTTP = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'all']);
const REQUEST_METHODS = new Set([...HTTP].filter((method) => method !== 'all'));
const EXTENSIONS = ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs', '.mts', '.cts'];
const hash = (value) => crypto.createHash('sha256').update(value).digest('hex');
const slash = (value) => value.split(path.sep).join('/');
const literal = (node) => (node && ts.isStringLiteralLike(node) ? node.text : null);
const isFunction = (node) => ts.isFunctionLike(node) && !!node.body;
const memberName = (node) =>
  node && (ts.isIdentifier(node) || ts.isStringLiteralLike(node)) ? node.text : null;
function dotted(node) {
  if (!node) return '';
  if (ts.isIdentifier(node)) return node.text;
  if (node.kind === ts.SyntaxKind.ThisKeyword) return 'this';
  if (ts.isMetaProperty(node)) return 'import.meta';
  if (ts.isPropertyAccessExpression(node)) return `${dotted(node.expression)}.${node.name.text}`;
  return '';
}
function unwrap(node) {
  while (
    node &&
    (ts.isParenthesizedExpression(node) ||
      ts.isAsExpression(node) ||
      ts.isNonNullExpression(node) ||
      ts.isTypeAssertionExpression(node) ||
      ts.isSatisfiesExpression(node))
  )
    node = node.expression;
  return node;
}
function semanticHash(node, resource = null) {
  if (!node) return hash('unresolved');
  const scanner = ts.createScanner(
    ts.ScriptTarget.Latest,
    true,
    ts.LanguageVariant.JSX,
    node.getText(),
  );
  const output = [];
  for (let token = scanner.scan(); token !== ts.SyntaxKind.EndOfFileToken; token = scanner.scan()) {
    resource?.step();
    output.push(`${token}:${scanner.getTokenText()}`);
  }
  const shape = [];
  function visit(current) {
    resource?.step();
    shape.push(`(${current.kind}`);
    ts.forEachChild(current, visit);
    shape.push(')');
  }
  visit(node);
  return hash(JSON.stringify([output, shape]));
}

function owner(node) {
  const parts = [];
  for (let current = node.parent; current; current = current.parent)
    if (isFunction(current)) {
      let label = memberName(current.name);
      if (!label && ts.isVariableDeclaration(current.parent))
        label = memberName(current.parent.name);
      if (!label && ts.isBinaryExpression(current.parent)) label = dotted(current.parent.left);
      if (!label && ts.isPropertyAssignment(current.parent))
        label = memberName(current.parent.name);
      if (!label && ts.isExportAssignment(current.parent)) label = 'default';
      if (!label && ts.isCallExpression(current.parent))
        label = `${dotted(current.parent.expression) || 'callback'}:callback`;
      parts.unshift(label || '<anonymous>');
    }
  return parts.join('/') || '<module>';
}

function location(file, node) {
  return {
    file: file.rel,
    line: file.sf.getLineAndCharacterOfPosition(node.getStart(file.sf)).line + 1,
    endLine: file.sf.getLineAndCharacterOfPosition(node.getEnd()).line + 1,
  };
}

function createSemanticHasher(resource) {
  const cache = new WeakMap();
  return (node) => {
    if (!node) return hash('unresolved');
    if (!cache.has(node)) cache.set(node, semanticHash(node, resource));
    return cache.get(node);
  };
}
module.exports = {
  createSemanticHasher,
  ts,
  HTTP,
  REQUEST_METHODS,
  EXTENSIONS,
  hash,
  slash,
  literal,
  isFunction,
  memberName,
  dotted,
  unwrap,
  semanticHash,
  owner,
  location,
};
