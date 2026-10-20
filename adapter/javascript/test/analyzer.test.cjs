'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { analyze } = require('../analyzer.cjs');

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cimet-ast-test-'));
  for (const [name, text] of Object.entries(files)) { const target = path.join(root, name); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, text); }
  t.after(() => { const target = path.resolve(root); assert.equal(path.dirname(target), path.resolve(os.tmpdir())); assert.ok(path.basename(target).startsWith('cimet-ast-test-')); fs.rmSync(target, { recursive: true, force: true }); });
  return root;
}
const extract = (root, kind = 'backend', config = {}) => analyze({ root, kind, name: 'synthetic', config });

test('TypeScript module extensions are discovered and local imports resolve consistently', t => {
  const root = fixture(t, {
    'server.mts': `import express from 'express'; import router from './routes.cts'; const app=express(); app.use('/api',router);`,
    'routes.cts': `import {Router} from 'express'; const router=Router(); const handler=(req:unknown,res:any)=>res.end(); router.get('/typed',handler); export default router;`,
    'types.d.mts': `declare const hidden: unknown;`,
  });
  const result = extract(root);
  assert.equal(result.filesAnalyzed, 2);
  assert.deepEqual(result.endpoints.map(endpoint=>endpoint.normalizedPath), ['/api/typed']);
  assert.deepEqual(result.architecture.modules.map(module=>module.file).sort(), ['routes.cts','server.mts']);
});

test('Express provenance, local router imports, mount composition and chained methods', t => {
  const root = fixture(t, {
    'server.js': `const express = require('express'); const app = express(); const router = require('./routes'); app.use('/api', router); app.get('/health', (req,res)=>res.json({ok:true})); const cache = new Map(); cache.get('/false', ()=>{});`,
    'routes.js': `const { Router } = require('express'); const router = Router(); const controllers = require('./controllers'); router.route('/clients/:id').get(controllers.read).put(controllers.update); module.exports = router;`,
    'controllers.js': `exports.read = (req,res)=>res.json({x:1}); exports.update = (req,res)=>res.json({x:2});`,
  });
  const result = extract(root);
  assert.deepEqual(result.endpoints.map(e => `${e.method} ${e.normalizedPath}`).sort(), ['GET /api/clients/{param}', 'GET /health', 'PUT /api/clients/{param}']);
  assert.ok(result.endpoints.find(e => e.method === 'PUT').handlerFile.endsWith('controllers.js'));
  assert.equal(result.diagnostics.length, 0);
});

test('Consign binds only actually loaded registration modules; no target code is executed', t => {
  const root = fixture(t, {
    'server.js': `const express = require('express'); consign = require('consign'); const app = express(); consign().include('app/routes').into(app);`,
    'app/routes/items.js': `require('node:fs').writeFileSync('MUST_NOT_EXIST', 'boom'); module.exports = app => { app.get('/items', handler); app.route('/items/:id').post(handler).delete(handler); }; const handler = (req,res)=>res.end();`,
    'unloaded.js': `module.exports = app => { app.get('/unloaded', ()=>{}); };`,
    '.env': 'SECRET_MUST_NOT_BE_READ=never',
    'node_modules/bad/index.js': `const express=require('express'); const app=express();app.get('/dependency',()=>{});`,
    'tests/ignored.js': `const express=require('express'); const app=express();app.get('/test',()=>{});`,
  });
  const result = extract(root);
  assert.equal(result.endpoints.length, 3);
  assert.equal(result.filesAnalyzed, 3);
  assert.equal(fs.existsSync(path.join(root, 'MUST_NOT_EXIST')), false);
  assert.ok(!JSON.stringify(result).includes('SECRET_MUST_NOT_BE_READ'));
});

test('frontend wrappers require import provenance, preserve dynamic paths and exclude implementation calls', t => {
  const root = fixture(t, {
    'src/container/server_requests.js': `import axios from 'axios'; export default { get_request(path) { return axios.get(path); } };`,
    'src/utilities/dataUtility.js': `import axios from 'axios'; export default (method,url)=>axios.get(url);`,
    'src/component.tsx': `import backend from './container/server_requests'; import request from './utilities/dataUtility'; import axios from 'axios'; const suffix='/clients'; const api=import.meta.env.VITE_NUTROSAL_BE; export function Component({id}:{id:string}) { backend.get_request(suffix + '/' + id); request('post', api + '/clients'); axios.get('https://user:password@example.org/items?token=DO_NOT_EXPORT'); fetch('/local', dynamicOptions); const fake={get(){}}; fake.get('/false'); return <div/>; }`,
  });
  const result = extract(root, 'frontend');
  assert.equal(result.calls.length, 4);
  assert.deepEqual(result.calls.map(c => [c.method, c.path, c.target]).sort(), [['GET', '/clients/{param}', 'backend'], ['POST', '/clients', 'backend'], ['GET', '/items', 'external'], ['UNKNOWN', '/local', 'unknown']].sort());
  assert.ok(!JSON.stringify(result).includes('DO_NOT_EXPORT'));
  assert.ok(!JSON.stringify(result).includes('password'));
  assert.ok(result.diagnostics.some(d => d.code === 'UNRESOLVED_HTTP_METHOD'));
  assert.equal(result.stats.wrapperFilesExcluded, 2);
});

test('axios instances, named aliases, fetch shadowing, unresolved bases and parse errors', t => {
  const root = fixture(t, {
    'good.ts': "import client from 'axios'; import {get as load} from 'axios'; const api=client.create({baseURL:import.meta.env.VITE_NUTROSAL_BE}); function x(){ api.get('/records'); load('/unknown-origin'); globalThing.get('/false'); const fetch=()=>{};fetch('/shadowed'); } function y(){ fetch(variable); fetch(`${unknownBase}/items`); }",
    'broken.ts': `function { THIS_SECRET_SHOULD_NEVER_APPEAR`,
  });
  const result = extract(root, 'frontend');
  assert.equal(result.calls.length, 4);
  assert.equal(result.calls.find(c => c.path === '/records').target, 'backend');
  assert.equal(result.calls.filter(c => c.path === null).length, 2);
  assert.equal(result.stats.parseErrorFiles, 1);
  assert.ok(!JSON.stringify(result).includes('THIS_SECRET_SHOULD_NEVER_APPEAR'));
});

test('IDs survive whitespace and body edits while resolved handler fingerprints change', t => {
  const root = fixture(t, { 'server.js': `const express=require('express'); const app=express(); const handler=require('./handler'); app.get('/x', handler);`, 'handler.js': `module.exports=(req,res)=>res.send('one');` });
  const first = extract(root);
  fs.writeFileSync(path.join(root, 'server.js'), '\n// comment\n' + fs.readFileSync(path.join(root, 'server.js'), 'utf8'));
  const whitespace = extract(root);
  assert.equal(first.endpoints[0].id, whitespace.endpoints[0].id);
  assert.equal(first.endpoints[0].fingerprint, whitespace.endpoints[0].fingerprint);
  fs.writeFileSync(path.join(root, 'handler.js'), `module.exports=(req,res)=>res.send('two');`);
  const changed = extract(root);
  assert.equal(first.endpoints[0].id, changed.endpoints[0].id);
  assert.equal(first.endpoints[0].contractFingerprint, changed.endpoints[0].contractFingerprint);
  assert.notEqual(first.endpoints[0].bodyFingerprint, changed.endpoints[0].bodyFingerprint);
});

test('repeated extraction is deterministic except performance measurements', t => {
  const root = fixture(t, { 'index.js': `const express=require('express'); const app=express(); app.get('/x',()=>{}); app.get('/x',()=>{});` });
  const a=extract(root), b=extract(root);
  delete a.stats.elapsedMs; delete b.stats.elapsedMs; delete a.stats.peakRssKiB; delete b.stats.peakRssKiB;
  assert.deepEqual(a,b);
  assert.equal(new Set(a.endpoints.map(e=>e.id)).size,2);
});

test('body fingerprints detect automatic semicolon insertion while ignoring harmless whitespace and comments', t => {
  const root=fixture(t,{'server.js':`const express=require('express');const app=express();app.get('/x',function handler(){return {ok:true};});`});
  const first=extract(root).endpoints[0];
  fs.writeFileSync(path.join(root,'server.js'),`const express=require('express');const app=express();\napp.get('/x',function handler(){\n/* harmless comment */ return  {ok: true};\n});`);
  const formatting=extract(root).endpoints[0];
  assert.equal(first.bodyFingerprint,formatting.bodyFingerprint);
  fs.writeFileSync(path.join(root,'server.js'),`const express=require('express');const app=express();app.get('/x',function handler(){return\n {ok:true};});`);
  const asi=extract(root).endpoints[0];
  assert.equal(first.id,asi.id);
  assert.equal(first.contractFingerprint,asi.contractFingerprint);
  assert.notEqual(first.bodyFingerprint,asi.bodyFingerprint);
});

test('axios.all is not a request; request config, constant methods, spreads and computed options are conservative', t => {
  const root = fixture(t, { 'requests.ts': `import axios,{all,request as send} from 'axios'; const M='PATCH'; axios.all([]); all([]); axios.request({url:'/a',method:M}); send({url:'/b'}); fetch('/c',{...options}); axios('/d',{[key]:value}); fetch('/e',{method:M}); const conf={url:'/f',method:'DELETE'}; axios(conf); const cyclic=cyclic;fetch('/g',cyclic);` });
  const result=extract(root,'frontend');
  assert.deepEqual(Object.fromEntries(result.calls.map(c=>[c.path,c.method])), {'/a':'PATCH','/b':'GET','/c':'UNKNOWN','/d':'UNKNOWN','/e':'PATCH','/f':'DELETE','/g':'UNKNOWN'});
  assert.equal(result.calls.filter(c=>c.method==='UNKNOWN').length,3);
  assert.ok(result.diagnostics.some(d=>d.code==='UNRESOLVED_HTTP_METHOD'));
});

test('Express optional, wildcard and regular-expression paths are not collapsed to exact contracts', t => {
  const root=fixture(t,{'server.js':`const express=require('express');const app=express();app.get('/items/:id?',()=>{});app.get('/files/*',()=>{});app.get('/items/:id',()=>{});app.get(/^pattern/,()=>{});const router=express.Router();app.use('/base/:id?',router);router.get('/x',()=>{});`});
  const result=extract(root);
  assert.equal(result.endpoints.find(e=>e.path==='/items/:id?').normalizedPath,null);
  assert.equal(result.endpoints.find(e=>e.path==='/items/:id').normalizedPath,'/items/{param}');
  assert.equal(result.endpoints.find(e=>e.path==='/base/:id?/x').normalizedPath,null);
  assert.ok(result.diagnostics.some(d=>d.code==='UNSUPPORTED_EXPRESS_PATTERN'));
  assert.ok(result.diagnostics.some(d=>d.code==='UNRESOLVED_ENDPOINT_PATH'));
});

test('method-first wrapper profile preserves external and unknown origins and declared method semantics', t => {
  const root=fixture(t,{'dataUtility.js':`export default (m,u)=>{};`,'index.js':`import request from './dataUtility';request('DELETE','/x');request('get','https://user:SECRET@example.org/path?secret=FORBIDDEN');request('post',url);request('post',unknownOrigin+'/x');`});
  const result=extract(root,'frontend');
  assert.equal(result.calls.find(c=>c.path==='/x').method,'POST');
  assert.equal(result.calls.find(c=>c.path==='/path').target,'external');
  assert.equal(result.calls.filter(c=>c.target==='unknown'&&c.path===null).length,2);
  assert.ok(!JSON.stringify(result).includes('FORBIDDEN'));
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});

test('axios instance default method and absolute URL templates keep static semantics', t => {
  const root=fixture(t,{'index.js':"import axios from 'axios';const api=axios.create({method:'POST',baseURL:'https://api.example.org'});api(`/items/${id}`);api.request({url:'/other',method:'DELETE'});axios.get(`https://api.example.org/items/${id}?secret=HIDDEN`);"});
  const result=extract(root,'frontend',{backendOrigins:['https://api.example.org']});
  assert.equal(result.calls.filter(c=>c.method==='POST').length,1);
  assert.equal(result.calls.filter(c=>c.method==='DELETE').length,1);
  assert.equal(result.calls.filter(c=>c.normalizedPath==='/items/{param}'&&c.resolution==='template'&&c.target==='backend').length,2);
  assert.ok(!JSON.stringify(result).includes('HIDDEN'));
});

test('bounded source traversal ignores symlink trees and oversized source without running target modules', t => {
  const root=fixture(t,{'index.js':`export const x=1;`,'huge.js':' '.repeat(5*1024*1024+1)});
  const outside=fixture(t,{'secret.js':`fetch('/must-not-read');`});
  fs.symlinkSync(outside,path.join(root,'linked'),'junction');
  const sideEffect=path.join(root,'executed-marker');
  fs.writeFileSync(path.join(root,'evil.js'),`require('node:fs').writeFileSync(${JSON.stringify(sideEffect)},'executed'); fetch('/visible');`);
  const result=extract(root,'frontend');
  assert.equal(result.filesAnalyzed,2);
  assert.equal(result.calls.length,1);
  assert.equal(fs.existsSync(sideEffect),false);
  assert.ok(result.diagnostics.some(d=>d.code==='FILE_TOO_LARGE'));
  assert.ok(result.diagnostics.some(d=>d.code==='SYMLINK_SKIPPED'));
});
