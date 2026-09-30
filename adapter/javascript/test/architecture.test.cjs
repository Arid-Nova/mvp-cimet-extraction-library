'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { analyze } = require('../analyzer.cjs');
function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cimet-architecture-test-'));
  for (const [file, source] of Object.entries(files)) { const target = path.join(root, file); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, source); }
  t.after(() => { const target=path.resolve(root); assert.equal(path.dirname(target),path.resolve(os.tmpdir())); assert.ok(path.basename(target).startsWith('cimet-architecture-test-')); fs.rmSync(target,{recursive:true,force:true}); });
  return root;
}
const extract = root => analyze({root,kind:'backend',name:'synthetic'});
const edges = graph => graph.dependencies.filter(edge=>edge.resolution==='resolved'&&!edge.typeOnly).map(edge=>`${edge.source}->${edge.target}`).sort();
const callNames = graph => graph.calls.map(call=>`${graph.functions.find(f=>f.id===call.callerId).name}->${graph.functions.find(f=>f.id===call.targetId).name}`).sort();

test('module graph preserves real cycles, acyclic branches, mixed type imports, and external uncertainty', t => {
  const root=fixture(t,{
    'a.ts':`import {b,type B} from './b';import type {Leaf} from './leaf';import remote from 'https://user:SECRET@example.org/module?token=HIDDEN';export function a(){return b();}`,
    'b.ts':`import {a} from './a';import './leaf';export interface B{};export function b(){return a();}`,
    'leaf.ts':`export interface Leaf{};export const leaf=1;`,
  });
  const graph=extract(root).architecture;
  assert.deepEqual(edges(graph),['a.ts->b.ts','b.ts->a.ts','b.ts->leaf.ts']);
  assert.equal(graph.dependencies.filter(d=>d.typeOnly).length,2);
  assert.equal(graph.dependencies.find(d=>d.resolution==='external').target,null);
  assert.deepEqual(callNames(graph),['a->b','b->a']);
  assert.ok(!JSON.stringify(graph).includes('SECRET'));
  assert.ok(!JSON.stringify(graph).includes('HIDDEN'));
});

test('function aliases resolve through named exports, star barrels, namespace imports and require aliases', t => {
  const root=fixture(t,{
    'leaf.js':`export function leaf(){return 1;}const other=()=>2;export {other as renamed};`,
    'barrel.js':`export {leaf as work} from './leaf';export * from './leaf';export * as namespace from './leaf';`,
    'main.js':`import {work as alias,renamed,namespace} from './barrel';import * as all from './barrel';export function entry(){alias();renamed();all.work();namespace.leaf();}`,
    'legacy.js':`exports.task=function task(){return 3;};`,
    'cjs.js':`const load=require;const {task:alias}=load('./legacy');module.exports=function run(){alias();};`,
  });
  const graph=extract(root).architecture;
  assert.deepEqual(callNames(graph),['entry->leaf','entry->leaf','entry->leaf','entry->other','run->task']);
  assert.ok(edges(graph).includes('cjs.js->legacy.js'));
  assert.equal(graph.functions.length,5);
});

test('shadowed require, type-only bindings, reassigned aliases and missing local files do not invent edges', t => {
  const root=fixture(t,{
    'target.ts':`export function fn(){};`,
    'main.ts':`import type {fn} from './target';const missing=require('./missing');const dynamic=require(name);function test(require){require('./target');fn();}function leaf(){};let alias=leaf;alias=unknown;function caller(){alias();}const object={fn:leaf};object.fn=unknown;function other(){object.fn();}`,
  });
  const graph=extract(root).architecture;
  assert.deepEqual(edges(graph),[]);
  assert.equal(graph.dependencies.filter(d=>d.resolution==='unresolved').length,2);
  assert.equal(graph.calls.length,0);
  assert.ok(graph.summary.unresolvedCallSites>=6);
});

test('endpoint handlers bind to resolved functions including middleware and transitive local chains', t => {
  const root=fixture(t,{
    'server.js':`const express=require('express');const app=express();const controller=require('./controller');const middleware=require('./middleware');app.get('/x',middleware,controller.entry);`,
    'middleware.js':`module.exports=function authorize(req,res,next){next();};`,
    'controller.js':`const helper=require('./helper');exports.entry=function entry(){helper.work();};`,
    'helper.js':`function core(){return 1;}exports.work=function work(){return core();};`,
  });
  const result=extract(root),graph=result.architecture;
  assert.deepEqual(callNames(graph),['entry->work','work->core']);
  assert.equal(graph.endpointHandlers.length,2);
  assert.ok(graph.endpointHandlers.every(edge=>edge.endpointId===result.endpoints[0].id));
  assert.deepEqual(graph.endpointHandlers.map(edge=>graph.functions.find(f=>f.id===edge.functionId).name).sort(),['authorize','entry']);
});

test('anonymous function IDs are distinct and line independent, including several callbacks on one line', t => {
  const root=fixture(t,{'main.js':`function leaf(){}function outer(){[1].map(()=>leaf());[2].map(()=>leaf());}outer();`});
  const first=extract(root).architecture;
  assert.equal(new Set(first.functions.map(f=>f.id)).size,4);
  assert.equal(first.calls.length,2);
  assert.equal(first.summary.resolvedTopLevelCallsOmitted,1);
  fs.writeFileSync(path.join(root,'main.js'),'\n// inserted comment\n'+fs.readFileSync(path.join(root,'main.js'),'utf8'));
  const second=extract(root).architecture;
  assert.deepEqual(first.functions.map(f=>f.id),second.functions.map(f=>f.id));
  assert.deepEqual(first.functions.map(f=>f.bodyFingerprint),second.functions.map(f=>f.bodyFingerprint));
});

test('Consign loads are grounded in proven app provenance and excluded symlink targets remain unresolved', t => {
  const root=fixture(t,{
    'server.js':`const express=require('express');const consign=require('consign');const app=express();consign().include('routes').into(app);const outside=require('./linked/outside');`,
    'routes/x.js':`module.exports=app=>app.get('/x',function x(){});`,
  });
  const outside=fixture(t,{'outside.js':`module.exports=function sensitive(){};`});
  fs.symlinkSync(outside,path.join(root,'linked'),'junction');
  const result=extract(root),graph=result.architecture;
  assert.ok(graph.dependencies.some(d=>d.kind==='consign'&&d.source==='server.js'&&d.target==='routes/x.js'));
  assert.ok(graph.dependencies.some(d=>d.kind==='require'&&d.resolution==='unresolved'&&d.target===null));
  assert.ok(!graph.modules.some(m=>m.file.startsWith('linked/')));
  assert.equal(graph.endpointHandlers.length,1);
});

test('ambiguous star reexports, reexport cycles and shadowed CommonJS exports stay unresolved', t => {
  const root=fixture(t,{
    'a.js':`export function work(){}`,
    'b.js':`export function work(){}`,
    'ambiguous.js':`export * from './a';export * from './b';`,
    'explicit.js':`export * from './b';export {work} from './a';`,
    'cycle1.js':`export * from './cycle2';`,
    'cycle2.js':`export * from './cycle1';`,
    'shadowed.js':`function configure(exports){exports.work=function fake(){};}`,
    'main.js':`import {work} from './ambiguous';import {work as exact} from './explicit';import {missing} from './cycle1';const fake=require('./shadowed');function entry(){work();exact();missing();fake.work();}`,
  });
  const graph=extract(root).architecture;
  assert.equal(graph.calls.length,1);
  assert.equal(graph.functions.find(f=>f.id===graph.calls[0].targetId).file,'a.js');
  assert.equal(graph.summary.resolvedLocalCalls,1);
});

test('direct route middleware binds but global and mounted middleware remains explicitly unsupported', t => {
  const root=fixture(t,{'server.js':`const express=require('express');const app=express();const router=express.Router();function global(req,res,next){next();}function mounted(req,res,next){next();}function direct(req,res,next){next();}function handler(req,res){}app.use(global);app.use('/api',mounted,router);router.get('/x',direct,handler);`});
  const graph=extract(root).architecture;
  assert.deepEqual(graph.endpointHandlers.map(edge=>graph.functions.find(f=>f.id===edge.functionId).name).sort(),['direct','handler']);
  assert.ok(graph.limitations.some(text=>text.includes('Global app.use middleware')));
});

test('TypeScript import-equals resolves a local CommonJS function without reading JSON or assets', t => {
  const root=fixture(t,{'legacy.cjs':`module.exports=function work(){};`,'main.ts':`import legacy=require('./legacy.cjs');import './style.css';import config from './config.json';function entry(){legacy();}`,'config.json':'{"SECRET":"DO_NOT_READ"}','style.css':'/* EXCLUDED */'});
  const graph=extract(root).architecture;
  assert.deepEqual(callNames(graph),['entry->work']);
  assert.equal(graph.summary.modules,2);
  assert.equal(graph.summary.unresolvedDependencies,2);
  assert.ok(graph.dependencies.some(d=>d.kind==='import-equals'&&d.resolution==='resolved'));
  assert.ok(!JSON.stringify(graph).includes('DO_NOT_READ'));
});
