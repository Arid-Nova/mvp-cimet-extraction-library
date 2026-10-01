'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {analyze}=require('../analyzer.cjs');
function fixture(t,files){const root=fs.mkdtempSync(path.join(os.tmpdir(),'cimet-semantics-test-'));for(const [file,source] of Object.entries(files)){const target=path.join(root,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,source);}t.after(()=>{const resolved=path.resolve(root);assert.equal(path.dirname(resolved),path.resolve(os.tmpdir()));assert.ok(path.basename(resolved).startsWith('cimet-semantics-test-'));fs.rmSync(resolved,{recursive:true,force:true});});return root;}
const extract=root=>analyze({root,kind:'backend',name:'synthetic'});

test('proven mysql/pg clients and imported forwarding wrappers expose sanitized SQL at caller functions',t=>{
  const root=fixture(t,{
    'database.js':`const mysql=require('mysql2');const {Pool}=require('pg');const connection=mysql.createPool({password:'DO_NOT_EXPORT'});const pg=new Pool({connectionString:'PRIVATE_URL'});module.exports={connection,pg};`,
    'runner.js':`const {connection,pg}=require('./database');exports.execute=(query,values)=>new Promise((resolve,reject)=>connection.query(query,values,()=>resolve()));exports.executePG=(text)=>pg.query(text);`,
    'barrel.js':`export {execute as run} from './runner';`,
    'model.js':`import {run} from './barrel';const {executePG}=require('./runner');export function load(){return run("SELECT id,name FROM users WHERE secret = 'SQL_PRIVATE_VALUE'",[]);}export function change(){return executePG('UPDATE users SET enabled = $1 WHERE id = $2');}`,
  });
  const result=extract(root),sem=result.architecture.semanticEvidence;
  const load=sem.dataAccess.find(row=>row.file==='model.js'&&row.operation==='select');
  assert.ok(load);assert.deepEqual(load.tables,['users']);assert.deepEqual(load.columns,['id','name','secret']);assert.ok(load.functionId);
  assert.ok(sem.dataAccess.some(row=>row.file==='model.js'&&row.library==='pg'&&row.operation==='update'));
  const serialized=JSON.stringify(sem);for(const secret of ['DO_NOT_EXPORT','PRIVATE_URL','SQL_PRIVATE_VALUE','SELECT id,name'])assert.ok(!serialized.includes(secret));
  assert.equal(sem.summary.inspectionComplete,true);
});

test('SQL fingerprints ignore literal value changes but retain table and column changes',t=>{
  const root=fixture(t,{'main.js':`const db=require('mysql').createConnection({});function load(){db.query("SELECT name FROM users WHERE id = 123 AND token='SECRET_A'");}`});
  const before=extract(root).architecture.semanticEvidence.dataAccess[0];
  fs.writeFileSync(path.join(root,'main.js'),`const db=require('mysql').createConnection({});function load(){db.query("SELECT name FROM users WHERE id = 456 AND token='SECRET_B'");}`);
  const values=extract(root).architecture.semanticEvidence.dataAccess[0];assert.equal(before.id,values.id);assert.equal(before.fingerprint,values.fingerprint);
  fs.writeFileSync(path.join(root,'main.js'),`const db=require('mysql').createConnection({});function load(){db.query("SELECT email FROM accounts WHERE id = 456");}`);
  const changed=extract(root).architecture.semanticEvidence.dataAccess[0];assert.equal(before.id,changed.id);assert.notEqual(before.fingerprint,changed.fingerprint);
});

test('lookalikes, shadowed require, reassigned clients and type-only imports do not invent data access',t=>{
  const root=fixture(t,{'main.ts':`import type {Pool} from 'pg';const fake={query(){}};fake.query('SELECT id FROM fake');function scoped(require){require('mysql2').createPool({}).query('SELECT id FROM fake');}let real=require('mysql').createPool({});real=unknown;real.query('SELECT id FROM fake');const typed=new Pool();typed.query('SELECT id FROM fake');`});
  const sem=extract(root).architecture.semanticEvidence;assert.deepEqual(sem.dataAccess,[]);assert.ok(sem.summary.unresolvedDataAccessCandidates>=3);
});

test('ORM operations and explicit mongoose/sequelize schema fields require package provenance',t=>{
  const root=fixture(t,{'main.js':`const mongoose=require('mongoose');const schema=new mongoose.Schema({name:String,age:Number,active:Boolean,secret:{type:String,default:'SECRET_DEFAULT'},dynamic:unknown});const User=mongoose.model('User',schema);User.find({name:'SECRET_FILTER'});const {Sequelize,DataTypes}=require('sequelize');const orm=new Sequelize('SECRET_CONNECTION');const Person=orm.define('Person',{name:DataTypes.STRING,age:DataTypes.INTEGER});Person.create({name:'SECRET_PAYLOAD'});const {PrismaClient}=require('@prisma/client');const prisma=new PrismaClient();prisma.user.findMany({select:{email:true}});const knex=require('knex')({});knex('accounts').select('id');`});
  const sem=extract(root).architecture.semanticEvidence;
  assert.deepEqual(sem.entities.map(row=>row.name).sort(),['Person','User']);
  assert.equal(sem.entities.find(row=>row.name==='User').complete,false);
  assert.deepEqual(sem.entities.find(row=>row.name==='Person').fields,[{name:'age',type:'number'},{name:'name',type:'string'}]);
  assert.equal(sem.dataAccess.length,4);assert.ok(sem.dataAccess.every(row=>row.complete===false));
  for(const secret of ['SECRET_DEFAULT','SECRET_FILTER','SECRET_CONNECTION','SECRET_PAYLOAD'])assert.ok(!JSON.stringify(sem).includes(secret));
});

test('health names retain handler evidence; proxies require real mount/forward registrations',t=>{
  const root=fixture(t,{'main.js':`const express=require('express');const app=express();app.get('/healthz',(req,res)=>res.json({ok:true}));const {createProxyMiddleware}=require('http-proxy-middleware');const unused=createProxyMiddleware({target:'https://user:PRIVATE@example.test'});const proxy=createProxyMiddleware({target:process.env.TARGET});app.use('/api',proxy);const httpProxy=require('http-proxy');const server=httpProxy.createProxyServer({target:'https://secret.example.test'});function forward(req,res){server.web(req,res);}const fake={use(){}};fake.use(unused);`});
  const result=extract(root),sem=result.architecture.semanticEvidence;
  assert.equal(sem.healthEndpoints.length,1);assert.equal(sem.healthEndpoints[0].endpointId,result.endpoints[0].id);assert.equal(sem.healthEndpoints[0].functionIds.length,1);
  assert.deepEqual(sem.proxies.map(row=>row.operation).sort(),['mount','web']);assert.equal(sem.summary.unregisteredProxyCreations,1);
  assert.ok(!JSON.stringify(sem).includes('PRIVATE'));assert.ok(!JSON.stringify(sem).includes('example.test'));
});

test('resilience distinguishes construction and invocation and rejects unused imports/lookalikes',t=>{
  const root=fixture(t,{'main.js':`const Breaker=require('opossum');const pRetry=require('p-retry');const retry=require('retry');const Bottleneck=require('bottleneck');const pLimit=require('p-limit');const limiterFactory=require('express-rate-limit');const app=require('express')();function work(){}const breaker=new Breaker(work,{timeout:123});breaker.fire();pRetry(work,{retries:3});const task=retry.operation();task.attempt(work);const limiter=new Bottleneck({maxConcurrent:2});limiter.schedule(work);const limit=pLimit(2);limit(work);app.use(limiterFactory({limit:100}));const unused=require('rate-limiter-flexible');const fake={retry(){},fire(){}};fake.fire();fake.retry();`});
  const result=extract(root),sem=result.architecture.semanticEvidence;
  assert.deepEqual([...new Set(sem.resilience.map(row=>row.kind))].sort(),['circuit-breaker','concurrency-limit','rate-limit','retry']);
  assert.ok(sem.resilience.some(row=>row.operation==='fire'));assert.ok(sem.resilience.some(row=>row.operation==='schedule'));assert.ok(sem.resilience.some(row=>row.operation==='mount'));
  assert.ok(sem.resilience.filter(row=>row.appliesToFunctionId).every(row=>result.architecture.functions.some(fn=>fn.id===row.appliesToFunctionId&&fn.name==='work')));
  assert.ok(!sem.resilience.some(row=>row.library==='rate-limiter-flexible'));
});

test('dynamic SQL and oversized inputs remain bounded, deterministic, and incomplete',t=>{
  const root=fixture(t,{'main.js':`const db=require('mysql2').createPool({});function run(table){db.query(\`SELECT id FROM \${table} WHERE token='HIDDEN'\`);db.query('${'SELECT '.padEnd(17000,' ')}');}const a=b;const b=a;const x=require('http-proxy-middleware').createProxyMiddleware(a);`});
  const first=extract(root).architecture.semanticEvidence,second=extract(root).architecture.semanticEvidence;
  assert.deepEqual(first,second);assert.ok(first.dataAccess.every(row=>row.complete===false));assert.equal(first.summary.boundsReached,true);assert.equal(first.summary.inspectionComplete,false);
  assert.ok(!JSON.stringify(first).includes('HIDDEN'));assert.ok(first.dataAccess.every(row=>row.shape.length<2048));
});

test('PostgreSQL dollar quotes, SQL comments and response-like arbitrary ORM chains cannot leak or invent evidence',t=>{
  const root=fixture(t,{'main.js':`const db=new (require('pg').Pool)();db.query("SELECT $$DOLLAR_SECRET$$, $tag$TAGGED_SECRET$tag$ FROM users WHERE id=$1 -- COMMENT_SECRET");const {PrismaClient}=require('@prisma/client');const prisma=new PrismaClient();prisma.user.getDescription().findMany({});const model=require('mongoose').model('Thing',new (require('mongoose').Schema)({name:String}));model.toString().find({});`});
  const sem=extract(root).architecture.semanticEvidence;assert.equal(sem.dataAccess.length,1);assert.equal(sem.summary.boundsReached,false);
  for(const secret of ['DOLLAR_SECRET','TAGGED_SECRET','COMMENT_SECRET'])assert.ok(!JSON.stringify(sem).includes(secret));
  assert.deepEqual(sem.dataAccess[0].columns,['id']);
});

test('writes through client and package aliases invalidate original provenance',t=>{
  const root=fixture(t,{'main.js':`const db=require('mysql2').createPool({});const alias=db;alias.query=()=>{};db.query('SELECT id FROM users');const express=require('express');const expressAlias=express;expressAlias.Router=()=>{};const app=express();const proxy=require('http-proxy-middleware').createProxyMiddleware({});app.use(proxy);`});
  const sem=extract(root).architecture.semanticEvidence;assert.deepEqual(sem.dataAccess,[]);assert.deepEqual(sem.proxies,[]);assert.ok(sem.summary.unresolvedDataAccessCandidates>0);
});

test('parser and oversized source exclusions invalidate semantic inspection without pretending budget exhaustion',t=>{
  const root=fixture(t,{'main.js':`const app=require('express')();app.get('/health',(req,res)=>res.end());`,'broken.js':`const db = ; db.query('SELECT id FROM users');`});
  const broken=extract(root).architecture.semanticEvidence;
  assert.equal(broken.summary.inspectionComplete,false);assert.equal(broken.summary.boundsReached,false);
  assert.equal(broken.summary.sourceExcludedPaths,1);assert.equal(broken.summary.sourceParseErrorFiles,1);
  fs.writeFileSync(path.join(root,'large.js'),'/*'+'x'.repeat(5*1024*1024)+'*/');
  const large=extract(root).architecture.semanticEvidence;
  assert.equal(large.summary.inspectionComplete,false);assert.equal(large.summary.boundsReached,false);
  assert.equal(large.summary.sourceExcludedPaths,2);assert.equal(large.summary.sourceOversizedFiles,1);
});

test('skipped symlink source is missing inspection coverage rather than observed absence',t=>{
  const root=fixture(t,{'target/database.js':`const db=require('mysql2').createPool({});db.query('SELECT id FROM users');`});
  try{fs.symlinkSync(path.join(root,'target'),path.join(root,'linked'),'junction');}catch(error){if(['EPERM','EACCES','ENOSYS'].includes(error.code)){t.skip('Symlink creation is unavailable');return;}throw error;}
  const sem=extract(root).architecture.semanticEvidence;
  assert.equal(sem.summary.inspectionComplete,false);assert.equal(sem.summary.boundsReached,false);
  assert.equal(sem.summary.sourceSymlinkPaths,1);assert.equal(sem.summary.sourceExcludedPaths,1);
});
