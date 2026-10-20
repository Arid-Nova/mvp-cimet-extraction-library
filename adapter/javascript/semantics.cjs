'use strict';

// Source observations only: consumes already parsed ASTs, never reads configuration
// files, imports target packages, evaluates target expressions, or exports SQL values.
function buildSemanticEvidence({ ts, validFiles, name, kind, lookup, resolveModule, unwrap, isFunction, literal, location, endpoints, endpointHandlers, resolveValue, functionRecords, nodeFiles, mutations, localExports, exportDeclarations, requireIdentifier, hash, sourceInspectionComplete = true, sourceExclusionCounts = {} }) {
  const MAX_RECORDS = 20000, MAX_WORK = 2000000, MAX_SQL = 16384, MAX_TOKENS = 4096, MAX_NAMES = 64, MAX_FIELDS = 128, MAX_DEPTH = 64;
  const libraries = new Set(['mysql', 'mysql2', 'mysql2/promise', 'pg', 'knex', '@prisma/client', 'mongoose', 'sequelize', 'express', 'http-proxy-middleware', 'http-proxy', 'opossum', 'p-retry', 'retry', 'express-rate-limit', 'rate-limiter-flexible', 'bottleneck', 'p-limit']);
  const dataAccess = [], entities = [], healthEndpoints = [], proxies = [], resilience = [];
  const occurrences = new Map(), roleCache = new WeakMap(), callSites = [], wrappers = new Map();
  const invalidBindings = new Set(mutations);
  for (const binding of mutations) {
    let current = binding;
    const visited = new Set();
    while (current?.init && !visited.has(current) && visited.size <= MAX_DEPTH) {
      visited.add(current); let initializer = unwrap(current.init);
      while (initializer && (ts.isPropertyAccessExpression(initializer) || ts.isElementAccessExpression(initializer))) initializer = unwrap(initializer.expression);
      if (!initializer || !ts.isIdentifier(initializer)) break;
      current = lookup(initializer); if (current) invalidBindings.add(current);
    }
  }
  let work = 0, boundsReached = false, unresolvedDataAccessCandidates = 0, unregisteredProxyCreations = 0;
  const safeName = value => typeof value === 'string' && /^[A-Za-z_$][\w$]{0,79}$/.test(value) ? value : null;
  const member = node => node && (ts.isIdentifier(node) ? safeName(node.text) : safeName(literal(node)));
  function budget() { if (++work > MAX_WORK) { boundsReached = true; return false; } return true; }
  function owner(node) { for (let parent = node.parent; parent; parent = parent.parent) if (isFunction(parent)) return functionRecords.get(parent) || null; return null; }
  function baseRecord(category, node, library, operation) {
    const file = nodeFiles.get(node), parent = owner(node), key = JSON.stringify([name, kind, category, file.rel, parent?.id || '<module>', library, operation]);
    const ordinal = (occurrences.get(key) || 0) + 1; occurrences.set(key, ordinal);
    return { id: `${category}-${hash(`${key}:${ordinal}`).slice(0,24)}`, ...location(file,node), functionId: parent?.id || null };
  }
  function push(array, value) { if (array.length < MAX_RECORDS) array.push(value); else boundsReached = true; }
  function property(node, key, seen = new Set()) {
    node = unwrap(node);
    if (seen.size > MAX_DEPTH) { boundsReached = true; return null; }
    if (!node || seen.has(node) || !budget()) return null;
    seen = new Set(seen).add(node);
    if (ts.isIdentifier(node)) { const binding = lookup(node); if (binding && !binding.assigned && !invalidBindings.has(binding)) return property(binding.init,key,seen); }
    if (!node || !ts.isObjectLiteralExpression(node) || node.properties.some(p => ts.isSpreadAssignment(p) || p.name && ts.isComputedPropertyName(p.name))) return null;
    const found = node.properties.filter(p => member(p.name) === key);
    if (found.length !== 1) return null;
    return ts.isPropertyAssignment(found[0]) ? found[0].initializer : ts.isShorthandPropertyAssignment(found[0]) ? found[0].name : null;
  }
  function exportRole(file, exported, seen) {
    if (seen.size > MAX_DEPTH) { boundsReached = true; return null; }
    if (!file) return null;
    const key = `${file.rel}:${exported}`; if (seen.has(key)) return null; seen = new Set(seen).add(key);
    const table = localExports.get(file);
    if (table?.has(exported)) return role(table.get(exported),seen);
    if (exported !== 'default' && table?.get('default')) {
      const result = propertyRole(role(table.get('default'),seen),exported,seen); if (result) return result;
    }
    const candidates = [];
    for (const declaration of exportDeclarations.get(file) || []) {
      if (declaration.isTypeOnly) continue;
      const source = literal(declaration.moduleSpecifier), clause = declaration.exportClause;
      if (clause && ts.isNamedExports(clause)) for (const item of clause.elements) {
        if (item.isTypeOnly || item.name.text !== exported) continue;
        const imported = (item.propertyName || item.name).text;
        const found = source ? importedRole(file,source,imported,seen) : bindingRole(lookup(item,imported),seen);
        if (found) candidates.push(found);
      }
      else if (!clause && source && exported !== 'default') { const found = importedRole(file,source,exported,seen); if (found) candidates.push(found); }
    }
    if (candidates.length !== 1) return null; return candidates[0];
  }
  function importedRole(file, source, imported, seen) {
    if (libraries.has(source)) return imported === '*' || imported === 'default' ? { type:'package', library:source } : propertyRole({type:'package',library:source},imported,seen);
    const local = resolveModule(file,source); if (!local) return null;
    return imported === '*' ? { type:'module', file:local } : exportRole(local,imported,seen);
  }
  function bindingRole(binding, seen) {
    if (!binding || binding.assigned || invalidBindings.has(binding) || binding.typeOnly) return null;
    if (binding.module) {
      if (ts.isImportDeclaration(binding.node) && (binding.node.importClause?.isTypeOnly || binding.node.importClause?.namedBindings?.elements?.some(e=>e.name.text===binding.name&&e.isTypeOnly))) return null;
      return importedRole(binding.file,binding.module,binding.imported,seen);
    }
    const result = role(binding.init,seen); return binding.selector ? propertyRole(result,binding.selector,seen) : result;
  }
  function propertyRole(base, key, seen) {
    if (!base || !key) return null;
    if (base.type === 'module') return exportRole(base.file,key,seen);
    if (base.type === 'object') return role(property(base.node,key),seen);
    if (base.type === 'package') return { type:'package-member',library:base.library,member:key };
    if (base.type === 'sql-client' && ['query','execute'].includes(key)) return { type:'sql-method', library:base.library, client:base };
    if (base.type === 'sql-client' && key === 'promise') return { type:'promise-client',library:base.library };
    if (base.type === 'prisma-client') return { type:'orm-model',library:base.library,model:key };
    if (['orm-model','knex-query','knex-client'].includes(base.type)) return { type:'orm-method',library:base.library,model:base.model,method:key };
    if (base.type === 'sequelize-client' && key === 'define') return {type:'entity-factory',library:base.library};
    if (base.type === 'sequelize-client' && key === 'query') return {type:'sql-method',library:base.library};
    if (base.type === 'express' && key === 'use') return {type:'express-use'};
    if (base.type === 'proxy-server' && ['web','ws','listen'].includes(key)) return {...base,type:'proxy-operation',operation:key};
    if (base.type === 'resilience-instance') return {...base,type:'resilience-operation',operation:key};
    return null;
  }
  function role(node, seen = new Set()) {
    node = unwrap(node);
    if (seen.size > MAX_DEPTH) { boundsReached = true; return null; }
    if (!node || seen.has(node) || !budget()) return null;
    if (roleCache.has(node)) return roleCache.get(node);
    seen = new Set(seen).add(node);
    let result = null;
    if (ts.isAwaitExpression(node)) result = role(node.expression,seen);
    else if (ts.isIdentifier(node)) result = bindingRole(lookup(node),seen);
    else if (ts.isObjectLiteralExpression(node)) result = {type:'object',node};
    else if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) result = propertyRole(role(node.expression,seen),ts.isPropertyAccessExpression(node)?node.name.text:literal(node.argumentExpression),seen);
    else if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      if (ts.isCallExpression(node) && requireIdentifier(node.expression)) {
        const source = literal(node.arguments[0]);
        if (libraries.has(source)) result = {type:'package',library:source};
        else { const local=resolveModule(nodeFiles.get(node),source); if(local) result=exportRole(local,'default',seen)||{type:'module',file:local}; }
      } else {
        const callee = role(node.expression,seen), args=node.arguments||[];
        if (callee?.type === 'package-member' && ['mysql','mysql2','mysql2/promise'].includes(callee.library) && ['createConnection','createPool'].includes(callee.member) || callee?.type === 'package-member' && callee.library==='pg' && ['Pool','Client'].includes(callee.member)) result={type:'sql-client',library:callee.library};
        else if(callee?.type==='promise-client') result={type:'sql-client',library:callee.library};
        else if(callee?.type==='package'&&callee.library==='knex') result={type:'knex-client',library:'knex'};
        else if(callee?.type==='knex-client') result={type:'knex-query',library:'knex',model:safeName(literal(args[0]))};
        else if(callee?.type==='orm-method'&&callee.library==='knex'&&['select','insert','update','delete','del','where','andWhere','orWhere','whereIn','whereNotIn','whereNull','whereNotNull','join','leftJoin','rightJoin','innerJoin','orderBy','groupBy','limit','offset','first','returning','transacting'].includes(callee.method)) result={type:'knex-query',library:callee.library,model:callee.model};
        else if(callee?.type==='package-member'&&callee.library==='@prisma/client'&&callee.member==='PrismaClient') result={type:'prisma-client',library:callee.library};
        else if(callee?.type==='package-member'&&callee.library==='mongoose'&&callee.member==='Schema') result={type:'schema',library:'mongoose',definition:args[0],node};
        else if(callee?.type==='package-member'&&callee.library==='mongoose'&&callee.member==='model') result={type:'orm-model',library:'mongoose',model:safeName(literal(args[0]))};
        else if((callee?.type==='package'&&callee.library==='sequelize')||(callee?.type==='package-member'&&callee.library==='sequelize'&&callee.member==='Sequelize')) result={type:'sequelize-client',library:'sequelize'};
        else if(callee?.type==='entity-factory') result={type:'orm-model',library:callee.library,model:safeName(literal(args[0]))};
        else if(callee?.type==='package'&&callee.library==='express'||callee?.type==='package-member'&&callee.library==='express'&&callee.member==='Router') result={type:'express'};
        else if(callee?.type==='package'&&callee.library==='http-proxy-middleware'||callee?.type==='package-member'&&callee.library==='http-proxy-middleware'&&callee.member==='createProxyMiddleware') result={type:'proxy-middleware',library:'http-proxy-middleware',config:args[0],node};
        else if(callee?.type==='package-member'&&callee.library==='http-proxy'&&['createProxyServer','createServer'].includes(callee.member)) result={type:'proxy-server',library:'http-proxy',config:args[0],node};
        else if(callee?.type==='package'&&['opossum','bottleneck','p-limit'].includes(callee.library)) result={type:'resilience-instance',library:callee.library,definition:node};
        else if(callee?.type==='package-member'&&callee.library==='rate-limiter-flexible'&&/^RateLimiter/.test(callee.member)) result={type:'resilience-instance',library:callee.library,definition:node};
        else if(callee?.type==='package-member'&&callee.library==='retry'&&callee.member==='operation') result={type:'resilience-instance',library:'retry',definition:node};
        else if(callee?.type==='package'&&callee.library==='express-rate-limit'||callee?.type==='package-member'&&callee.library==='express-rate-limit'&&callee.member==='rateLimit') result={type:'rate-middleware',library:'express-rate-limit',definition:node};
      }
    }
    // Nulls are not cached while a recursive export may still be under resolution.
    if (result) roleCache.set(node,result); return result;
  }
  function sqlExpression(node, env = new Map(), seen = new Set()) {
    node=unwrap(node);
    if(seen.size>MAX_DEPTH){boundsReached=true;return {text:'?',dynamic:true};}
    if(!node||seen.has(node)||!budget())return {text:'?',dynamic:true};
    seen=new Set(seen).add(node);
    if(ts.isStringLiteralLike(node)) return node.text.length<=MAX_SQL?{text:node.text,dynamic:false}:(boundsReached=true,{text:'?',dynamic:true});
    if(ts.isIdentifier(node)) {
      const binding=lookup(node);if(env.has(binding))return sqlExpression(env.get(binding),env,seen);
      if(binding&&!binding.assigned&&!invalidBindings.has(binding))return sqlExpression(binding.init,env,seen);
    }
    if(ts.isTemplateExpression(node)) {
      const text=node.head.text+node.templateSpans.map(span=>' ? '+span.literal.text).join('');
      if(text.length>MAX_SQL){boundsReached=true;return {text:'?',dynamic:true};}return {text,dynamic:true};
    }
    if(ts.isBinaryExpression(node)&&node.operatorToken.kind===ts.SyntaxKind.PlusToken){const left=sqlExpression(node.left,env,seen),right=sqlExpression(node.right,env,seen);if(left.text.length+right.text.length>MAX_SQL){boundsReached=true;return {text:'?',dynamic:true};}return {text:left.text+right.text,dynamic:left.dynamic||right.dynamic};}
    if(ts.isObjectLiteralExpression(node)) return sqlExpression(property(node,'sql')||property(node,'text'),env,seen);
    return {text:'?',dynamic:true};
  }
  function sqlShape(value) {
    const tokens=[]; let incomplete=value.dynamic, at=0;
    const text=value.text;
    while(at<text.length&&tokens.length<MAX_TOKENS){
      const c=text[at];if(/\s/.test(c)){at++;continue;}
      if(text.startsWith('--',at)||c==='#'){at=text.indexOf('\n',at);if(at<0){at=text.length;break;}continue;}
      if(text.startsWith('/*',at)){const end=text.indexOf('*/',at+2);if(end<0){incomplete=true;break;}at=end+2;continue;}
      const dollarQuote=/^\$(?:[A-Za-z_]\w*)?\$/.exec(text.slice(at));
      if(dollarQuote){const end=text.indexOf(dollarQuote[0],at+dollarQuote[0].length);if(end<0){incomplete=true;at=text.length;}else at=end+dollarQuote[0].length;tokens.push('?');continue;}
      if(c==="'"||c==='"'){const quote=c;if(c==='"')incomplete=true;at++;let closed=false;while(at<text.length){if(text[at]==='\\'){at+=2;continue;}if(text[at]===quote){if(text[at+1]===quote){at+=2;continue;}at++;closed=true;break;}at++;}if(!closed)incomplete=true;tokens.push('?');continue;}
      if(c==='`'){const end=text.indexOf('`',at+1);if(end<0){incomplete=true;break;}const identifier=safeName(text.slice(at+1,end));tokens.push(identifier||'?');if(!identifier)incomplete=true;at=end+1;continue;}
      const number=/^(?:\$\d+|\d+(?:\.\d+)?)/.exec(text.slice(at));if(number){tokens.push('?');at+=number[0].length;continue;}
      const identifier=/^[A-Za-z_$][\w$]*/.exec(text.slice(at));if(identifier){tokens.push(safeName(identifier[0])||'?');at+=identifier[0].length;continue;}
      tokens.push('(),.*;=<>!+-/?'.includes(c)?c:'?');at++;
    }
    if(tokens.length>=MAX_TOKENS&&at<text.length){boundsReached=true;incomplete=true;}
    const upper=tokens.map(token=>token.toUpperCase()), first=upper[0];
    const operation=({SELECT:'select',INSERT:'insert',REPLACE:'insert',UPDATE:'update',DELETE:'delete',CREATE:'ddl',ALTER:'ddl',DROP:'ddl'})[first]||'unknown';
    const tables=new Set(),columns=new Set();
    const keywords=new Set('SELECT DISTINCT ALL FROM JOIN LEFT RIGHT INNER OUTER FULL CROSS ON WHERE AND OR NOT IS NULL IN AS GROUP ORDER BY HAVING LIMIT OFFSET SET VALUES INTO UPDATE INSERT DELETE CREATE ALTER DROP TABLE IF EXISTS RETURNING ASC DESC TRUE FALSE CASE WHEN THEN ELSE END COUNT SUM AVG MIN MAX COALESCE UNION WITH'.split(' '));
    function add(set,token){const safe=safeName(token);if(safe&&!keywords.has(safe.toUpperCase())){if(set.size<MAX_NAMES)set.add(safe);else{boundsReached=true;incomplete=true;}}}
    for(let i=0;i<tokens.length;i++){
      if(['FROM','JOIN','INTO','UPDATE','TABLE'].includes(upper[i])){if(tokens[i+1]==='('||!safeName(tokens[i+1])||keywords.has(upper[i+1]))incomplete=true;else if(tokens[i+2]==='.'){
        if(safeName(tokens[i+3])&&!keywords.has(upper[i+3])){if(tables.size<MAX_NAMES)tables.add(`${tokens[i+1]}.${tokens[i+3]}`);else{boundsReached=true;incomplete=true;}}else incomplete=true;
      }else add(tables,tokens[i+1]);}
    }
    const from=upper.indexOf('FROM');
    if(operation==='select'&&from>0){for(let i=1;i<from;i++){if(tokens[i]==='*'){incomplete=true;continue;}if(safeName(tokens[i])&&upper[i-1]!=='AS'&&tokens[i+1]!=='.'&&tokens[i+1]!=='(')add(columns,tokens[i]);}}
    if(operation==='insert'){const into=upper.indexOf('INTO');const start=tokens.indexOf('(',into+2),end=tokens.indexOf(')',start+1);if(start>=0&&end>=0&&start<upper.indexOf('VALUES'))for(let i=start+1;i<end;i++)add(columns,tokens[i]);else incomplete=true;}
    for(let i=0;i<tokens.length;i++)if(['=','<','>'].includes(tokens[i])&&safeName(tokens[i-1]))add(columns,tokens[i-1]);
    if(['unknown','ddl'].includes(operation)||upper.filter(x=>x==='SELECT').length>1||upper.some(x=>['UNION','WITH','JOIN','GROUP','ORDER','HAVING','CASE','IN','IS','LIKE','BETWEEN'].includes(x))||tokens.some((t,i)=>t===';'&&i!==tokens.length-1))incomplete=true;
    const tableList=[...tables].sort(),columnList=[...columns].sort();
    const structure=`${operation}|tables:${tableList.join(',')}|columns:${columnList.join(',')}|dynamic:${value.dynamic}|wildcard:${tokens.includes('*')}`;
    if(structure.length>2048){boundsReached=true;incomplete=true;}
    return {operation,tables:tableList,columns:columnList,shape:structure.length>2048?'unknown':structure,fingerprint:hash(structure),complete:!incomplete};
  }
  function parameterDependencies(node, env=new Map(), seen=new Set()) {
    node=unwrap(node);if(seen.size>MAX_DEPTH){boundsReached=true;return new Set();}if(!node||seen.has(node)||!budget())return new Set();seen=new Set(seen).add(node);
    if(ts.isIdentifier(node)){const binding=lookup(node);if(env.has(binding))return parameterDependencies(env.get(binding),env,seen);if(binding?.parameter)return new Set([binding]);if(binding&&!binding.assigned&&!invalidBindings.has(binding))return parameterDependencies(binding.init,env,seen);return new Set();}
    const result=new Set();ts.forEachChild(node,child=>{for(const binding of parameterDependencies(child,env,seen))result.add(binding);});return result;
  }
  function addWrapperAncestors(call, descriptor) {
    const parameters=parameterDependencies(descriptor.sqlNode,descriptor.env);
    let additions=0;
    for(let parent=call.parent;parent;parent=parent.parent)if(isFunction(parent)){
      const own=parent.parameters.flatMap(p=>ts.isIdentifier(p.name)?[lookup(p.name)]:[]);
      if(!own.some(binding=>parameters.has(binding)))continue;
      const entries=wrappers.get(parent)||[];
      if(entries.some(entry=>entry.key===descriptor.key))continue;
      if(entries.length>=8){boundsReached=true;continue;}entries.push(descriptor);wrappers.set(parent,entries);additions++;
    }
    return additions;
  }
  for(const file of validFiles){function visit(node){if(!budget())return;if(ts.isCallExpression(node)||ts.isNewExpression(node))callSites.push(node);ts.forEachChild(node,visit);}visit(file.sf);}
  const directSql=new Map();
  for(const call of callSites){const callee=role(call.expression);if(callee?.type==='sql-method'){
    const descriptor={library:callee.library,sqlNode:call.arguments?.[0],env:new Map(),key:`${nodeFiles.get(call).rel}:${call.pos}`};directSql.set(call,descriptor);addWrapperAncestors(call,descriptor);
  }}
  for(let pass=0;pass<4;pass++){
    let added=0;
    for(const call of callSites){if(!budget())break;const target=resolveValue(call.expression);if(target?.type!=='function'||!wrappers.has(target.node))continue;
      for(const descriptor of [...wrappers.get(target.node)]){
        const env=new Map(descriptor.env);target.node.parameters.forEach((parameter,index)=>{if(ts.isIdentifier(parameter.name))env.set(lookup(parameter.name),call.arguments?.[index]);});
        added+=addWrapperAncestors(call,{...descriptor,env,key:`${descriptor.key}>${nodeFiles.get(call).rel}:${call.pos}`});
      }
    }
    if(!added)break;if(pass===3)boundsReached=true;
  }
  const ormOperation=method=>['find','findOne','findMany','findUnique','findFirst','findAll','select','count','aggregate','groupBy'].includes(method)?'select':['create','createMany','insert','insertMany','bulkCreate'].includes(method)?'insert':['update','updateMany','updateOne','findOneAndUpdate'].includes(method)?'update':['delete','deleteMany','deleteOne','destroy','remove','del'].includes(method)?'delete':null;
  function objectNames(node){node=unwrap(node);if(!node||!ts.isObjectLiteralExpression(node))return [];if(node.properties.length>MAX_NAMES)boundsReached=true;return [...new Set(node.properties.slice(0,MAX_NAMES).map(p=>member(p.name)).filter(Boolean))].sort();}
  function declarationFields(node,library){
    node=unwrap(node);if(node&&ts.isIdentifier(node)){const binding=lookup(node);node=binding&&!binding.assigned&&!invalidBindings.has(binding)?unwrap(binding.init):null;}
    if(!node||!ts.isObjectLiteralExpression(node))return {fields:[],complete:false};
    let complete=node.properties.length<=MAX_FIELDS; if(!complete)boundsReached=true;
    const fields=[];
    for(const item of node.properties.slice(0,MAX_FIELDS)){
      const field=member(item.name);if(!field||!ts.isPropertyAssignment(item)){complete=false;continue;}
      let value=unwrap(item.initializer),type='unknown';if(value&&ts.isObjectLiteralExpression(value))value=unwrap(property(value,'type'));
      if(value&&ts.isIdentifier(value)&&!lookup(value))type=({String:'string',Number:'number',Boolean:'boolean',Date:'date',Buffer:'binary',Object:'object',Array:'array'})[value.text]||'unknown';
      else if(value&&ts.isArrayLiteralExpression(value))type='array';
      else if(value&&ts.isPropertyAccessExpression(value)){
        const base=role(value.expression);if(base?.library===library)type=({STRING:'string',TEXT:'string',INTEGER:'number',BIGINT:'number',FLOAT:'number',DOUBLE:'number',DECIMAL:'number',BOOLEAN:'boolean',DATE:'date',DATEONLY:'date',JSON:'object',JSONB:'object',BLOB:'binary',ARRAY:'array',ObjectId:'identifier',Mixed:'unknown'})[value.name.text]||'unknown';
      }
      if(type==='unknown')complete=false;
      const duplicate=fields.find(existing=>existing.name===field);if(duplicate){duplicate.type='unknown';complete=false;}else fields.push({name:field,type});
    }
    fields.sort((a,b)=>a.name.localeCompare(b.name,'en'));return {fields,complete};
  }
  function targetKind(config){const value=property(config,'target');return !value?'unknown':ts.isStringLiteralLike(unwrap(value))?'literal-redacted':'dynamic';}
  function appliesTo(node){const target=resolveValue(node);return target?.type==='function'?functionRecords.get(target.node)?.id:null;}
  const registeredProxies=new Set();
  for(const call of callSites){if(!budget())break;
    const callee=role(call.expression),args=call.arguments||[],file=nodeFiles.get(call);
    const sqlDescriptors=[];
    if(directSql.has(call))sqlDescriptors.push(directSql.get(call));
    const target=resolveValue(call.expression);
    if(target?.type==='function')for(const descriptor of wrappers.get(target.node)||[]){const env=new Map(descriptor.env);target.node.parameters.forEach((parameter,index)=>{if(ts.isIdentifier(parameter.name))env.set(lookup(parameter.name),args[index]);});sqlDescriptors.push({...descriptor,env});}
    const sqlSeen=new Set();
    for(const descriptor of sqlDescriptors){const shape=sqlShape(sqlExpression(descriptor.sqlNode,descriptor.env)),key=`${descriptor.library}:${shape.fingerprint}`;if(sqlSeen.has(key))continue;sqlSeen.add(key);push(dataAccess,{...baseRecord('data-access',call,descriptor.library,'sql'),kind:'sql',library:descriptor.library,...shape});if(shape.operation==='unknown')unresolvedDataAccessCandidates++;}
    const method=callee?.type==='orm-method'&&ormOperation(callee.method);
    if(method){const tables=callee.model?[callee.model]:[],columns=callee.library==='knex'&&callee.method==='select'?[...new Set(args.slice(0,MAX_NAMES).map(argument=>safeName(literal(argument))).filter(Boolean))].sort():objectNames(property(args[0],'select')||property(args[0],'data')||args[0]);const shape=`${method}|models:${tables.join(',')}|fields:${columns.join(',')}`;if(shape.length>2048||args.length>MAX_NAMES)boundsReached=true;push(dataAccess,{...baseRecord('data-access',call,callee.library,callee.method),kind:'orm',library:callee.library,operation:method,tables,columns,shape:shape.length>2048?'unknown':shape,fingerprint:hash(shape),complete:false});}
    else if(!sqlDescriptors.length&&ts.isPropertyAccessExpression(call.expression)&&['query','execute','findMany','findOne','findAll','select'].includes(call.expression.name.text)&&!callee)unresolvedDataAccessCandidates++;
    if(callee?.type==='entity-factory'||callee?.type==='package-member'&&callee.library==='mongoose'&&callee.member==='model'){
      const library=callee.library,entityName=safeName(literal(args[0]));let schema=args[1];if(library==='mongoose'){const schemaRole=role(schema);schema=schemaRole?.type==='schema'?schemaRole.definition:null;}
      const fields=declarationFields(schema,library);if(entityName){const structural={library,name:entityName,fields:fields.fields};push(entities,{...baseRecord('entity',call,library,entityName),...structural,complete:fields.complete,fingerprint:hash(JSON.stringify(structural))});}
    }
    if(callee?.type==='express-use')for(const argument of args){const middleware=role(argument);
      if(middleware?.type==='proxy-middleware'){registeredProxies.add(middleware.node);const structure={library:middleware.library,kind:'middleware',operation:'mount',target:targetKind(middleware.config)};push(proxies,{...baseRecord('proxy',call,middleware.library,'mount'),...structure,fingerprint:hash(JSON.stringify(structure))});}
      if(middleware?.type==='rate-middleware'){const structure={library:middleware.library,kind:'rate-limit',operation:'mount'};push(resilience,{...baseRecord('resilience',call,middleware.library,'mount'),...structure,fingerprint:hash(JSON.stringify(structure))});}
    }
    if(callee?.type==='proxy-operation'){registeredProxies.add(callee.node);const structure={library:callee.library,kind:'server',operation:callee.operation,target:targetKind(args[2]||callee.config)};push(proxies,{...baseRecord('proxy',call,callee.library,callee.operation),...structure,fingerprint:hash(JSON.stringify(structure))});}
    let resilienceKind=null,operation=null,applies=null;
    if(callee?.type==='package'&&['opossum','bottleneck','p-limit'].includes(callee.library)){resilienceKind=callee.library==='opossum'?'circuit-breaker':'concurrency-limit';operation='construct';applies=callee.library==='opossum'?appliesTo(args[0]):null;}
    if(callee?.type==='package'&&callee.library==='p-retry'||callee?.type==='package-member'&&callee.library==='p-retry'&&callee.member==='default'){resilienceKind='retry';operation='invoke';applies=appliesTo(args[0]);}
    if(callee?.type==='package-member'&&callee.library==='retry'&&callee.member==='operation'){resilienceKind='retry';operation='construct';}
    if(callee?.type==='package-member'&&callee.library==='rate-limiter-flexible'&&/^RateLimiter/.test(callee.member)){resilienceKind='rate-limit';operation='construct';}
    if(callee?.type==='resilience-operation'){
      const allowed=({opossum:['fire'],bottleneck:['schedule','submit','wrap'],retry:['attempt','retry'],'rate-limiter-flexible':['consume']})[callee.library]||[];
      if(allowed.includes(callee.operation)){resilienceKind=callee.library==='opossum'?'circuit-breaker':callee.library==='retry'?'retry':callee.library==='rate-limiter-flexible'?'rate-limit':'concurrency-limit';operation=callee.operation;applies=appliesTo(args.find(argument=>resolveValue(argument)?.type==='function'));}
    }
    if(callee?.type==='resilience-instance'&&callee.library==='p-limit'){resilienceKind='concurrency-limit';operation='invoke';applies=appliesTo(args[0]);}
    if(resilienceKind){const structure={library:callee.library,kind:resilienceKind,operation};push(resilience,{...baseRecord('resilience',call,callee.library,operation),...structure,fingerprint:hash(JSON.stringify(structure)),...(applies?{appliesToFunctionId:applies}:{})});}
  }
  for(const call of callSites){const value=role(call);if(value?.type==='proxy-middleware'&&value.node===call&&!registeredProxies.has(call))unregisteredProxyCreations++;}
  for(const endpoint of endpoints){const route=endpoint.normalizedPath||endpoint.path;if(typeof route==='string'&&/(?:^|\/)(?:health|healthz|ready|readyz|readiness|live|livez|liveness|ping)(?:\/|$)/i.test(route)){push(healthEndpoints,{endpointId:endpoint.id,functionIds:endpointHandlers.filter(edge=>edge.endpointId===endpoint.id).map(edge=>edge.functionId).sort(),classification:'health-like-route',basis:'path-name'});}}
  const order=(a,b)=>a.file.localeCompare(b.file,'en')||a.line-b.line||a.id.localeCompare(b.id,'en');for(const array of [dataAccess,entities,proxies,resilience])array.sort(order);healthEndpoints.sort((a,b)=>a.endpointId.localeCompare(b.endpointId,'en'));
  return {schemaVersion:1,dataAccess,entities,healthEndpoints,proxies,resilience,summary:{dataAccess:dataAccess.length,sql:dataAccess.filter(x=>x.kind==='sql').length,orm:dataAccess.filter(x=>x.kind==='orm').length,entities:entities.length,healthLikeEndpoints:healthEndpoints.length,proxies:proxies.length,resilience:resilience.length,unresolvedDataAccessCandidates,unregisteredProxyCreations,inspectionComplete:sourceInspectionComplete&&!boundsReached,boundsReached,...sourceExclusionCounts,astAndResolutionWork:work,maxWork:MAX_WORK,maxRecordsPerCategory:MAX_RECORDS,maxSqlCharacters:MAX_SQL,maxSqlTokens:MAX_TOKENS,maxNames:MAX_NAMES,maxEntityFields:MAX_FIELDS,maxResolutionDepth:MAX_DEPTH},limitations:[
    'These are bounded source observations, not proof of runtime execution, deployment topology, recovery behavior, or missing production capability.',
    'Only recognized package provenance and local aliases/exports are followed. Target dependencies, environment files, deployment manifests, generated Prisma schemas, and runtime configuration are not inspected.',
    'Parser errors, oversized source files, and skipped symbolic links prevent complete source-inspection claims. These source exclusions are reported separately from semantic work-budget limits.',
    'SQL output contains only coarse operation/table/column shapes. Values, raw SQL, connection settings, and proxy URLs are omitted. Dynamic SQL, quoted dialect-specific identifiers, joins, computed columns, and unknown wrappers can reduce coverage.',
    'SQL wrapper propagation is bounded to four local forwarding rounds and eight descriptors per function. Function IDs identify observed callsites; source graph reachability is candidate impact evidence only.',
    'ORM table names are logical model names, not verified physical database mappings. Only explicit Mongoose and Sequelize declarations are observed; imported or generated schemas remain unknown.',
    'Health-like routes are name heuristics with supplied handler evidence, not validated probes. Proxies require recognized Express mounting or http-proxy web/ws/listen calls; unused imports and unmounted factories are not registrations.',
    'Resilience observations distinguish construction, invocation, mounting, and recognized methods. They do not establish effective limits, successful retries, circuit state, or protection against failures.',
  ]};
}

module.exports = { buildSemanticEvidence };
