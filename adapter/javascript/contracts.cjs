'use strict';

// Consumes existing ASTs only. No target module, schema package or comment text is executed.
function buildContracts({ ts, validFiles, lookup, unwrap, isFunction, literal, location, endpoints, endpointHandlerNodes, resolveValue, functionRecords, nodeFiles, mutations }) {
  const LOCATIONS = new Set(['body', 'query', 'params', 'headers']);
  const MAX_FIELDS = 256, MAX_RESPONSES = 128, MAX_CONTEXTS = 128, MAX_NODES = 40000, MAX_DEPTH = 8, MAX_SHAPE = 2048;
  const MAX_TYPE_NODES = 4000;
  let typeWork = 0, typeLimitHit = false;
  const declaredCache = new WeakMap();
  const mutatedValues = new Set(mutations);
  for (const binding of mutations) {
    let current = binding;
    const seen = new Set();
    while (current?.init && ts.isIdentifier(unwrap(current.init)) && !seen.has(current) && seen.size < 64) {
      seen.add(current); current = lookup(unwrap(current.init)); if (current) mutatedValues.add(current);
    }
  }
  const types = new Map(validFiles.map(file => [file, new Map()]));
  const sourceFiles = new Map(validFiles.map(file => [file.sf, file]));
  const fileFor = node => nodeFiles.get(node) || sourceFiles.get(node?.getSourceFile());
  // Without router provenance here, any .use call is conservatively possible
  // global/inherited middleware. This may lower completeness, never fabricate it.
  const potentialInheritedMiddleware = validFiles.some(file => file.calls.some(call => ts.isPropertyAccessExpression(call.expression) && call.expression.name.text === 'use'));
  for (const file of validFiles) for (const statement of file.sf.statements) {
    if (ts.isTypeAliasDeclaration(statement) || ts.isInterfaceDeclaration(statement)) types.get(file).set(statement.name.text, statement);
  }
  const safeName = text => typeof text === 'string' && /^[A-Za-z_$][\w$-]{0,79}$/.test(text) ? text : null;
  const propertyName = node => node && (ts.isIdentifier(node) ? safeName(node.text) : safeName(literal(node)));
  const unknown = () => ({ type: 'unknown', shape: 'unknown', complete: false });
  const primitive = type => ({ type, shape: type, complete: true });
  function declared(node, file, seen = new Set()) {
    if (!node || seen.has(node)) return unknown();
    if (seen.size > MAX_DEPTH || ++typeWork > MAX_TYPE_NODES) { typeLimitHit = true; return unknown(); }
    if (declaredCache.has(node)) return declaredCache.get(node);
    const beforeLimit = typeLimitHit, result = declaredValue(node, file, seen);
    if (!beforeLimit && !typeLimitHit && result.complete) declaredCache.set(node, result);
    return result;
  }
  function declaredValue(node, file, seen) {
    seen = new Set(seen).add(node);
    if (ts.isParenthesizedTypeNode(node) || ts.isJSDocNullableType(node) || ts.isJSDocNonNullableType(node) || ts.isJSDocOptionalType(node)) return declared(node.type, file, seen);
    const keyword = new Map([[ts.SyntaxKind.StringKeyword, 'string'], [ts.SyntaxKind.NumberKeyword, 'number'], [ts.SyntaxKind.BooleanKeyword, 'boolean']]);
    if (keyword.has(node.kind)) return primitive(keyword.get(node.kind));
    if (ts.isLiteralTypeNode(node)) {
      if (node.literal.kind === ts.SyntaxKind.NullKeyword) return primitive('null');
      if (ts.isStringLiteralLike(node.literal)) return primitive('string');
      if (ts.isNumericLiteral(node.literal)) return primitive('number');
      if ([ts.SyntaxKind.TrueKeyword, ts.SyntaxKind.FalseKeyword].includes(node.literal.kind)) return primitive('boolean');
    }
    if (ts.isArrayTypeNode(node) || ts.isTupleTypeNode(node)) {
      const children = ts.isArrayTypeNode(node) ? [node.elementType] : [...node.elements];
      if (children.length > MAX_FIELDS) { typeLimitHit = true; return unknown(); }
      const values = children.map(child => declared(child, file, seen));
      const shape = `array<${[...new Set(values.map(v => v.shape))].sort().join('|')}>`;
      if (shape.length > MAX_SHAPE) { typeLimitHit = true; return unknown(); }
      return { type: 'array', shape, complete: values.every(v => v.complete) };
    }
    if (ts.isTypeReferenceNode(node) && ts.isIdentifier(node.typeName)) {
      if (['Array', 'ReadonlyArray'].includes(node.typeName.text) && node.typeArguments?.length === 1) {
        const item = declared(node.typeArguments[0], file, seen), shape = `array<${item.shape}>`;
        if (shape.length > MAX_SHAPE) { typeLimitHit = true; return unknown(); }
        return { type: 'array', shape, complete: item.complete };
      }
      const definition = types.get(file)?.get(node.typeName.text);
      if (definition && !definition.typeParameters?.length && !definition.heritageClauses?.length) return declared(ts.isTypeAliasDeclaration(definition) ? definition.type : definition, file, seen);
    }
    if (ts.isTypeLiteralNode(node) || ts.isInterfaceDeclaration(node) || ts.isJSDocTypeLiteral(node)) {
      const members = node.members || node.jsDocPropertyTags || [], fields = [];
      let complete = members.length <= MAX_FIELDS, shapeLength = 8;
      if (!complete) typeLimitHit = true;
      for (const member of [...members].slice(0, MAX_FIELDS)) {
        const name = propertyName(member.name);
        if (!name || !(ts.isPropertySignature(member) || ts.isJSDocPropertyTag(member)) || member.questionToken || member.isBracketed) { complete = false; continue; }
        const value = declared(member.type || member.typeExpression?.type, file, seen);
        shapeLength += name.length + value.shape.length + 2;
        if (shapeLength > MAX_SHAPE) { typeLimitHit = true; return unknown(); }
        if (fields.some(field => field.name === name)) complete = false;
        fields.push({ name, value, node: member }); complete &&= value.complete;
      }
      const shape = `object{${fields.map(field => `${field.name}:${field.value.shape}`).sort().join(',')}}`;
      return shape.length > MAX_SHAPE ? unknown() : { type: 'object', shape, complete, fields };
    }
    return unknown();
  }
  function parameterType(parameter) {
    if (parameter?.type) return parameter.type;
    const tag = parameter && ts.getJSDocParameterTags(parameter).find(item => item.typeExpression?.type);
    return tag?.typeExpression?.type;
  }
  function bindPattern(pattern, value, context) {
    if (!pattern) return;
    if (ts.isIdentifier(pattern)) { const binding = lookup(pattern); if (binding) { if (value) context.set(binding, value); else context.delete(binding); } }
    else if (ts.isObjectBindingPattern(pattern)) for (const element of pattern.elements) {
      const name = propertyName(element.propertyName || element.name);
      bindPattern(element.name, !element.dotDotDotToken && name ? descend(value, name) : null, context);
    }
  }
  function childContextFor(fn, context, argumentsRefs = []) {
    const child = new Map(context);
    // Recursive entries must not inherit their previous locals or parameter taints.
    for (const binding of child.keys()) {
      let owner = binding.node;
      while (owner && !isFunction(owner)) owner = owner.parent;
      if (owner === fn) child.delete(binding);
    }
    fn.parameters.forEach((parameter,index) => bindPattern(parameter.name, argumentsRefs[index], child));
    return child;
  }
  function descend(base, name) {
    if (!base || !name) return null;
    if (base.role === 'request') return LOCATIONS.has(name) ? { role: 'input', location: name, path: [] } : null;
    if (base.role === 'input' && base.path.length < MAX_DEPTH) return { ...base, path: [...base.path, name] };
    return null;
  }
  function reference(node, context, seen = new Set()) {
    node = unwrap(node);
    if (!node || seen.has(node) || seen.size > 32) return null;
    seen = new Set(seen).add(node);
    if (ts.isIdentifier(node)) {
      const binding = lookup(node);
      if (!binding || binding.assigned) return null;
      if (context.has(binding)) return context.get(binding);
      const value = reference(binding.init, context, seen);
      return binding.selector ? descend(value, binding.selector) : value;
    }
    if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) return descend(reference(node.expression, context, seen), ts.isPropertyAccessExpression(node) ? safeName(node.name.text) : safeName(literal(node.argumentExpression)));
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
      const base = reference(node.expression.expression, context, seen);
      // These Express response builders return the same response; arbitrary methods do not.
      if (base?.role === 'response' && ['status', 'type', 'header', 'set', 'cookie', 'clearCookie', 'append', 'location', 'vary'].includes(node.expression.name.text)) return base;
    }
    return null;
  }
  function coercion(node) {
    node = unwrap(node);
    if (ts.isPrefixUnaryExpression(node)) return node.operator === ts.SyntaxKind.PlusToken || node.operator === ts.SyntaxKind.MinusToken ? 'number' : node.operator === ts.SyntaxKind.ExclamationToken ? 'boolean' : null;
    if (!ts.isCallExpression(node) || !ts.isIdentifier(node.expression) || lookup(node.expression)) return null;
    return ({ Number: 'number', parseInt: 'number', parseFloat: 'number', String: 'string', Boolean: 'boolean' })[node.expression.text] || null;
  }
  function expressionShape(node, context, seen = new Set()) {
    if (!node || seen.has(node)) return unknown();
    if (seen.size > MAX_DEPTH) { typeLimitHit = true; return unknown(); }
    seen = new Set(seen).add(node); node = unwrap(node);
    if (ts.isStringLiteralLike(node) || ts.isTemplateExpression(node)) return primitive('string');
    if (ts.isNumericLiteral(node)) return primitive('number');
    if ([ts.SyntaxKind.TrueKeyword, ts.SyntaxKind.FalseKeyword].includes(node.kind)) return primitive('boolean');
    if (node.kind === ts.SyntaxKind.NullKeyword) return primitive('null');
    const coerced = coercion(node); if (coerced) return primitive(coerced);
    if (ts.isIdentifier(node)) {
      const binding = lookup(node);
      if (!binding || binding.assigned || mutatedValues.has(binding) || binding.selector) return unknown();
      const type = binding.node?.type || (binding.parameter && parameterType(binding.node));
      if (type) return declared(type, nodeFiles.get(node));
      const initializer = unwrap(binding.init);
      // Aggregate aliases may have escaped into mutating code. The architecture
      // graph is not a heap/escape analysis, so only directly supplied literals
      // or explicit source declarations can prove a complete aggregate shape.
      if (initializer && (ts.isObjectLiteralExpression(initializer) || ts.isArrayLiteralExpression(initializer))) return unknown();
      return expressionShape(binding.init, context, seen);
    }
    if (ts.isObjectLiteralExpression(node)) {
      const fields = []; let complete = node.properties.length <= MAX_FIELDS;
      if (!complete) typeLimitHit = true;
      for (const property of [...node.properties].slice(0, MAX_FIELDS)) {
        const name = propertyName(property.name);
        if (!name || !(ts.isPropertyAssignment(property) || ts.isShorthandPropertyAssignment(property))) { complete = false; continue; }
        const value = expressionShape(ts.isPropertyAssignment(property) ? property.initializer : property.name, context, seen);
        if (fields.some(field => field.name === name)) complete = false;
        fields.push({ name, value }); complete &&= value.complete;
      }
      const shape = `object{${fields.map(field => `${field.name}:${field.value.shape}`).sort().join(',')}}`;
      if (shape.length > MAX_SHAPE) { typeLimitHit = true; return unknown(); }
      return { type: 'object', shape, complete };
    }
    if (ts.isArrayLiteralExpression(node)) {
      if (node.elements.length > MAX_FIELDS) typeLimitHit = true;
      const values = [...node.elements].slice(0, MAX_FIELDS).map(item => ts.isSpreadElement(item) ? unknown() : expressionShape(item, context, seen));
      const shape = `array<${[...new Set(values.map(value => value.shape))].sort().join('|') || 'empty'}>`;
      if (shape.length > MAX_SHAPE) { typeLimitHit = true; return unknown(); }
      return { type: 'array', shape, complete: node.elements.length <= MAX_FIELDS && values.every(value => value.complete) };
    }
    return unknown();
  }
  const operations = [];
  let truncatedOperations = 0;
  for (const endpoint of endpoints) {
    typeWork = 0; typeLimitHit = false;
    const inputs = new Map(), responses = new Map(), responseNodes = new Map(), contextsSeen = new Set(), limitReasons = new Set();
    let nodes = 0, truncated = false, declaredComplete = false, unknownHandler = false, opaqueCalls = 0;
    function addInput(ref, type, evidence, node) {
      if (ref?.role !== 'input' || !ref.path.length) return;
      const name = ref.path.join('.');
      if (name.length > 256) { truncated = true; return; }
      const file = fileFor(node); if (!file) return;
      const key = `${ref.location}:${name}`;
      if (!inputs.has(key) && inputs.size >= MAX_FIELDS) { truncated = true; return; }
      const existing = inputs.get(key), next = { location: ref.location, name, type, evidence, file: file.rel, line: location(file, node).line };
      const rank = { 'property-read': 0, 'route-param': 1, coercion: 2, declared: 3 };
      if (!existing || rank[evidence] > rank[existing.evidence]) inputs.set(key, next);
      else if (rank[evidence] === rank[existing.evidence] && existing.type !== type) inputs.set(key, { ...existing, type: 'unknown' });
    }
    function declaredInputs(fn) {
      const parameter = fn.parameters[0], file = nodeFiles.get(fn);
      const shape = declared(parameterType(parameter), file);
      if (shape.type !== 'object' || !shape.fields) return false;
      for (const group of shape.fields) if (LOCATIONS.has(group.name) && group.value.fields) {
        for (const field of group.value.fields) addInput({ role: 'input', location: group.name, path: [field.name] }, field.value.type, 'declared', field.node);
      }
      return shape.complete && shape.fields.length === LOCATIONS.size && [...LOCATIONS].every(name => shape.fields.some(field => field.name === name && field.value.type === 'object' && field.value.complete));
    }
    function observeResponse(node, context) {
      if (!ts.isPropertyAccessExpression(node.expression)) return false;
      const kind = node.expression.name.text;
      if (!['json', 'send', 'end'].includes(kind) || reference(node.expression.expression, context)?.role !== 'response') return false;
      let shape = node.arguments.length ? expressionShape(node.arguments[0], context) : kind === 'end' ? { type: 'unknown', shape: 'empty', complete: true } : unknown();
      // Legacy send(status, body) and extra arguments are deliberately not inferred.
      if (node.arguments.length > 1) shape = unknown();
      const file = nodeFiles.get(node), key = `${file.rel}:${node.pos}`;
      if (!responses.has(key) && responses.size >= MAX_RESPONSES) { truncated = true; return true; }
      const record = { kind, type: shape.type, shape: shape.shape, complete: shape.complete, file: file.rel, line: location(file, node).line };
      const previous = responses.get(key);
      if (previous && (previous.shape !== record.shape || previous.type !== record.type)) { record.type = 'unknown'; record.shape = 'unknown'; record.complete = false; }
      responses.set(key, record); responseNodes.set(node, record); return true;
    }
    function inspect(fn, context, depth = 0) {
      if (depth > MAX_DEPTH || contextsSeen.size >= MAX_CONTEXTS) { truncated = true; return; }
      const key = `${functionRecords.get(fn)?.id}:${[...context].filter(([,ref]) => ref).map(([binding,ref]) => `${binding.file.rel}:${binding.node.pos}:${binding.name}:${ref.role}:${ref.location || ''}:${ref.path?.join('.') || ''}`).sort().join('|')}`;
      if (contextsSeen.has(key)) return; contextsSeen.add(key);
      function visit(node) {
        if (++nodes > MAX_NODES) { truncated = true; return; }
        if (isFunction(node) && node !== fn) {
          // Captured request/response evidence inside callbacks is observable source evidence;
          // callback invocation and invocation order are deliberately not claimed.
          inspect(node, childContextFor(node, context), depth + 1); return;
        }
        if (ts.isVariableDeclaration(node) && node.initializer) {
          const ref = reference(node.initializer, context);
          bindPattern(node.name, ref, context);
          if (ref) {
            if (ts.isObjectBindingPattern(node.name)) for (const element of node.name.elements) {
              const field = propertyName(element.propertyName || element.name);
              if (!element.dotDotDotToken && field) addInput(descend(ref, field), 'unknown', 'property-read', element);
            }
          }
        }
        if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) {
          const ref = reference(node, context);
          if (ref?.role === 'input') {
            // Only leaf accesses are fields: req.body.person.name is one nested field.
            const parent = node.parent;
            const writeOnly = ts.isBinaryExpression(parent) && parent.left === node && parent.operatorToken.kind === ts.SyntaxKind.EqualsToken || ts.isDeleteExpression(parent);
            if (!writeOnly && !((ts.isPropertyAccessExpression(parent) || ts.isElementAccessExpression(parent)) && parent.expression === node)) {
              let wrapper = parent, type = null;
              if (ts.isCallExpression(wrapper) && wrapper.arguments[0] === node) type = coercion(wrapper);
              else if (ts.isPrefixUnaryExpression(wrapper)) type = coercion(wrapper);
              addInput(ref, type || 'unknown', type ? 'coercion' : 'property-read', node);
            }
          }
        }
        if (ts.isCallExpression(node)) {
          const handled = observeResponse(node, context);
          const target = resolveValue(node.expression);
          if (target?.type === 'function') {
            const childContext = childContextFor(target.node, context, node.arguments.map(argument => reference(argument, context)));
            // Only follow helpers when request/response data is passed or captured.
            if ([...childContext.values()].some(ref => ref)) inspect(target.node, childContext, depth + 1);
          } else if (!handled && !coercion(node) && reference(node, context)?.role !== 'response') opaqueCalls++;
          const type = coercion(node), ref = type && reference(node.arguments[0], context);
          if (ref) addInput(ref, type, 'coercion', node.arguments[0]);
        }
        ts.forEachChild(node, visit);
      }
      visit(fn.body);
    }
    const handlerNodes = endpointHandlerNodes.get(endpoint) || [], handlers = [];
    for (const handlerNode of handlerNodes) {
      const resolved = resolveValue(handlerNode);
      if (resolved?.type !== 'function') { unknownHandler = true; continue; }
      const fn = resolved.node, context = new Map(); handlers.push(fn);
      bindPattern(fn.parameters[0]?.name, { role: 'request' }, context);
      bindPattern(fn.parameters[1]?.name, { role: 'response' }, context);
      declaredComplete ||= declaredInputs(fn);
      inspect(fn, context);
    }
    // Route parameters are observed transport strings, not a complete input schema.
    for (const match of (endpoint.path || '').matchAll(/:([A-Za-z_$][\w$]*)/g)) {
      if (inputs.size >= MAX_FIELDS) { truncated = true; break; }
      const key = `params:${match[1]}`;
      if (!inputs.has(key)) inputs.set(key, { location: 'params', name: match[1], type: 'string', evidence: 'route-param', file: endpoint.file, line: endpoint.line });
    }
    function pure(node) {
      if (!node) return true;
      node = unwrap(node);
      if (ts.isIdentifier(node) || ts.isStringLiteralLike(node) || ts.isNumericLiteral(node) || [ts.SyntaxKind.TrueKeyword, ts.SyntaxKind.FalseKeyword, ts.SyntaxKind.NullKeyword].includes(node.kind)) return true;
      if (ts.isPropertyAccessExpression(node)) return pure(node.expression);
      if (ts.isBinaryExpression(node)) return node.operatorToken.kind < ts.SyntaxKind.FirstAssignment && pure(node.left) && pure(node.right);
      if (ts.isPrefixUnaryExpression(node)) return ![ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken, ts.SyntaxKind.DeleteKeyword].includes(node.operator) && pure(node.operand);
      return false;
    }
    function terminal(node) {
      if (!node) return false;
      if (ts.isBlock(node)) {
        for (let index = 0; index < node.statements.length; index++) {
          const statement = node.statements[index];
          if (ts.isVariableStatement(statement) && statement.declarationList.declarations.every(d => pure(d.initializer))) continue;
          return index === node.statements.length - 1 && terminal(statement);
        }
        return false;
      }
      if (ts.isIfStatement(node)) return pure(node.expression) && !!node.elseStatement && terminal(node.thenStatement) && terminal(node.elseStatement);
      const expression = ts.isReturnStatement(node) || ts.isExpressionStatement(node) ? unwrap(node.expression) : unwrap(node);
      return !!expression && ts.isCallExpression(expression) && responseNodes.get(expression)?.complete === true;
    }
    const inputList = [...inputs.values()].sort((a,b) => `${a.location}:${a.name}`.localeCompare(`${b.location}:${b.name}`, 'en'));
    const responseList = [...responses.values()].sort((a,b) => a.file.localeCompare(b.file,'en') || a.line-b.line || a.shape.localeCompare(b.shape,'en'));
    truncated ||= typeLimitHit;
    const inputComplete = declaredComplete && handlers.length === 1 && !unknownHandler && !truncated && !potentialInheritedMiddleware && inputList.every(field => field.type !== 'unknown' && field.evidence === 'declared');
    const outputComplete = handlers.length === 1 && !unknownHandler && !truncated && !potentialInheritedMiddleware && opaqueCalls === 0 && terminal(handlers[0].body) && responseList.length > 0 && responseList.every(response => response.complete);
    if (!inputComplete) limitReasons.add('Input fields are observed evidence; no complete closed request declaration was proven.');
    else limitReasons.add('Input completeness describes an explicit closed source declaration, not runtime payload validation.');
    if (!outputComplete) limitReasons.add('Response observations do not prove a complete output contract across all execution paths.');
    if (unknownHandler || !handlers.length) limitReasons.add('At least one supplied endpoint handler could not be resolved.');
    if (opaqueCalls) limitReasons.add('Opaque library or dynamic calls may consume further inputs or produce other responses.');
    if (potentialInheritedMiddleware) limitReasons.add('A possible .use middleware registration prevents complete operation claims; middleware provenance is not inferred by this helper.');
    limitReasons.add('Global and inherited middleware are not propagated; contract evidence covers supplied handlers and bounded local helpers only.');
    limitReasons.add('Captured callback evidence does not establish callback invocation or runtime reachability.');
    if (truncated) { limitReasons.add('Contract analysis reached a field, response, context, depth, shape, or AST work bound.'); truncatedOperations++; }
    operations.push({ endpointId: endpoint.id, inputs: inputList, inputComplete, responses: responseList, outputComplete, limitations: [...limitReasons] });
  }
  operations.sort((a,b) => a.endpointId.localeCompare(b.endpointId,'en'));
  return { schemaVersion: 1, operations, summary: {
    operations: operations.length, inputFields: operations.reduce((sum,operation) => sum+operation.inputs.length,0), responseObservations: operations.reduce((sum,operation) => sum+operation.responses.length,0),
    operationsWithInputs: operations.filter(operation => operation.inputs.length).length, operationsWithResponses: operations.filter(operation => operation.responses.length).length,
    completeInputs: operations.filter(operation => operation.inputComplete).length, completeOutputs: operations.filter(operation => operation.outputComplete).length, truncatedOperations,
    maxFieldsPerOperation: MAX_FIELDS, maxResponsesPerOperation: MAX_RESPONSES, maxFunctionContextsPerOperation: MAX_CONTEXTS, maxAstNodesPerOperation: MAX_NODES, maxTypeNodesPerOperation: MAX_TYPE_NODES, maxDepth: MAX_DEPTH, maxShapeLength: MAX_SHAPE,
  } };
}

module.exports = { buildContracts };
