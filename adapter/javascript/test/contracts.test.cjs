'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { analyze } = require('../analyzer.cjs');
function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cimet-contracts-test-'));
  for (const [file, source] of Object.entries(files)) { const target = path.join(root,file); fs.mkdirSync(path.dirname(target),{recursive:true}); fs.writeFileSync(target,source); }
  t.after(() => { const target=path.resolve(root); assert.equal(path.dirname(target),path.resolve(os.tmpdir())); assert.ok(path.basename(target).startsWith('cimet-contracts-test-')); fs.rmSync(target,{recursive:true,force:true}); });
  return root;
}
const extract = root => analyze({root,kind:'backend',name:'synthetic'});
function operation(result, route) { return result.architecture.contracts.operations.find(item=>item.endpointId===result.endpoints.find(endpoint=>endpoint.path===route).id); }

test('request aliases, destructuring, coercions, imported local helpers and callback responses preserve provenance', t => {
  const root=fixture(t,{
    'server.js':`const app=require('express')();const controller=require('./controller');app.post('/people/:id',controller.entry);`,
    'controller.js':`const helper=require('./helper');exports.entry=function entry(request,response){const r=request;const {name:person}=r.body;const {query:q}=r;const page=Number(q.page);helper.save(r.body,response);service.run(()=>response.json({ok:true,rows:[],message:'HIDDEN_SECRET_VALUE'}));const fake={body:{bad:true}};fake.body.bad;request.body.writeOnly='VALUE';};`,
    'helper.js':`exports.save=function save(payload,out){const {age}=payload;parseInt(payload.age);out.status(201).send({accepted:true,age:Number(age)});};`,
  });
  const result=extract(root),contract=operation(result,'/people/:id');
  assert.deepEqual(contract.inputs.map(field=>[field.location,field.name,field.type]),[['body','age','number'],['body','name','unknown'],['params','id','string'],['query','page','number']]);
  assert.deepEqual(contract.responses.map(response=>response.shape).sort(),['object{accepted:boolean,age:number}','object{message:string,ok:boolean,rows:array<empty>}']);
  assert.equal(contract.inputComplete,false); assert.equal(contract.outputComplete,false);
  assert.ok(!JSON.stringify(result.architecture.contracts).includes('HIDDEN_SECRET_VALUE'));
  assert.ok(result.architecture.functions.find(fn=>fn.name==='entry').unresolvedCalls>0);
});

test('closed TypeScript request declarations and exhaustive response branches provide positive completeness evidence', t => {
  const root=fixture(t,{
    'main.ts':`import express from 'express';const app=express();type Payload={name:string,count:number,enabled:boolean,tags:string[]};type Input={body:Payload,query:{},params:{id:string},headers:{}};function handler(req:Input,res:any){if(req.body.enabled){return res.json({ok:true,data:null});}else{return res.status(400).json({ok:false,data:null});}}app.post('/item/:id',handler);`,
  });
  const contract=operation(extract(root),'/item/:id');
  assert.equal(contract.inputComplete,true);
  assert.equal(contract.outputComplete,true);
  assert.deepEqual(contract.inputs.map(field=>[field.name,field.type,field.evidence]),[['count','number','declared'],['enabled','boolean','declared'],['name','string','declared'],['tags','array','declared'],['id','string','declared']]);
  assert.ok(contract.responses.every(response=>response.shape==='object{data:null,ok:boolean}'&&response.complete));
});

test('closed JSDoc request declarations are supported without reading or exporting comment prose', t => {
  const root=fixture(t,{'main.js':`const app=require('express')();\n/** Hidden annotation prose SECRET_DO_NOT_EXPORT. @param {{body:{name:string},query:{},params:{},headers:{}}} req */\nfunction handler(req,res){return res.json(null);}app.post('/typed',handler);`});
  const result=extract(root),contract=operation(result,'/typed');
  assert.equal(contract.inputComplete,true);
  assert.equal(contract.outputComplete,true);
  assert.deepEqual(contract.inputs.map(field=>[field.name,field.type]),[['name','string']]);
  assert.equal(contract.responses[0].type,'null');
  assert.ok(!JSON.stringify(result.architecture.contracts).includes('SECRET_DO_NOT_EXPORT'));
});

test('untyped empty inputs, opaque responses, missing branches, open types and inherited middleware stay incomplete', t => {
  const root=fixture(t,{'main.ts':`const app=require('express')();app.use((req,res,next)=>{Number(req.headers.x);next();});app.get('/empty',(req,res)=>res.json({ok:true}));app.get('/dynamic',(req,res)=>res.json(data));app.get('/branch',(req,res)=>{if(req.query.ok){return res.send('secret');}});app.get('/spread',(req:{body:{[key:string]:unknown},params:{},query:{},headers:{}},res:any)=>res.json({...value,ok:true}));app.get('/fake',(req,res)=>{const other={json(){}};other.json({wrong:true});});`});
  const result=extract(root);
  assert.equal(operation(result,'/empty').inputComplete,false);
  assert.equal(operation(result,'/empty').outputComplete,false);
  assert.equal(operation(result,'/dynamic').responses[0].shape,'unknown');
  assert.equal(operation(result,'/dynamic').outputComplete,false);
  assert.equal(operation(result,'/branch').outputComplete,false);
  assert.equal(operation(result,'/spread').inputComplete,false);
  assert.equal(operation(result,'/spread').outputComplete,false);
  assert.deepEqual(operation(result,'/fake').responses,[]);
  assert.ok(result.architecture.contracts.operations.every(item=>item.inputs.every(field=>field.name!=='x')));
  assert.match(operation(result,'/empty').limitations.join(' '),/Global and inherited middleware/);
});

test('reassigned aliases, unrelated callback parameters and shadowed coercions do not invent request provenance', t => {
  const root=fixture(t,{'main.js':`const app=require('express')();app.post('/x',(req,res)=>{let r=req;r=other;r.body.bad;function Number(x){return x;}Number(req.body.count);list.map(req=>req.body.unrelated);const {body:payload}=req;res.json({token:'PRIVATE_PAYLOAD',count:payload.count});});`});
  const contract=operation(extract(root),'/x');
  assert.deepEqual(contract.inputs.map(field=>[field.name,field.type]),[['count','unknown']]);
  assert.equal(contract.responses[0].complete,false);
  assert.ok(!JSON.stringify(contract).includes('PRIVATE_PAYLOAD'));
});

test('contract work and output are bounded and structural results deterministic', t => {
  const fields=Array.from({length:300},(_,index)=>`req.body.field${index};`).join('');
  const root=fixture(t,{'main.js':`const app=require('express')();app.post('/x',(req,res)=>{${fields}return res.json({ok:true});});`});
  const first=extract(root).architecture.contracts,second=extract(root).architecture.contracts;
  assert.deepEqual(first,second);
  assert.equal(first.operations[0].inputs.length,256);
  assert.equal(first.summary.truncatedOperations,1);
  assert.equal(first.operations[0].outputComplete,false);
  assert.match(first.operations[0].limitations.join(' '),/bound/);
});

test('mutable and escaped aggregate aliases never inherit a falsely complete literal response shape', t => {
  const root=fixture(t,{'main.js':`const app=require('express')();const payload={ok:true};payload.ok=external;const other={ok:true};const alias=other;alias.extra=unknown;const escaped={ok:true};change(escaped);app.get('/direct',(req,res)=>res.json(payload));app.get('/alias',(req,res)=>res.json(other));app.get('/escaped',(req,res)=>res.json(escaped));`});
  for (const contract of extract(root).architecture.contracts.operations) {
    assert.equal(contract.responses[0].shape,'unknown');
    assert.equal(contract.responses[0].complete,false);
    assert.equal(contract.outputComplete,false);
  }
});

test('recursive argument contexts clear opaque parameters and captured callback parameters', t => {
  const root=fixture(t,{'main.js':`const app=require('express')();function walk(first,second,out){const local=first;second && walk(opaque,opaque,out);service.run(function callback(first){first.body.foreign;});out.json({ok:true});}app.post('/x',(req,res)=>walk(req,null,res));`});
  const contract=operation(extract(root),'/x');
  assert.deepEqual(contract.inputs,[]);
  assert.equal(contract.inputComplete,false); assert.equal(contract.outputComplete,false);
});

test('tuple and repeated alias expansion respect declared-type bounds', t => {
  const tuple=Array.from({length:300},()=>`string`).join(',');
  const members=Array.from({length:180},(_,index)=>`field${index}:string`).join(';');
  const repeated=Array.from({length:180},(_,index)=>`field${index}:Wide`).join(';');
  const root=fixture(t,{'main.ts':`const app=require('express')();type Wide={${members}};type Deep={${repeated}};app.post('/tuple',(req:{body:{value:[${tuple}]},params:{},query:{},headers:{}},res:any)=>res.json(null));app.post('/deep',(req:{body:Deep,params:{},query:{},headers:{}},res:any)=>res.json(null));`});
  const contracts=extract(root).architecture.contracts;
  assert.equal(contracts.summary.truncatedOperations,2);
  assert.ok(contracts.operations.every(operation=>!operation.inputComplete&&!operation.outputComplete));
  assert.ok(contracts.operations.every(operation=>operation.responses.every(response=>response.shape.length<=2048)));
});
