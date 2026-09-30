import { fork, spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import { dirname, resolve, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { Parser } from '../dist/src/sse.js';
import { canonical, hash, requireThat, eventOracle, snapshotOracle, difference, readerTerminated } from './fault-contracts.mjs';
const exec = promisify(execFile), here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '../..');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
requireThat(process.argv.length === 3, 'Usage: node gateway/tools/real-stack-faults.mjs private-config.json');
const config = await readJson(process.argv[2]);
const base = new URL(config.javaOrigin), port = config.gatewayPort;
requireThat(base.protocol === 'http:' && base.hostname === '127.0.0.1' && base.origin === config.javaOrigin
  && Number.isInteger(port) && port >= 1024 && port <= 65535 && String(port) !== base.port
  && /^auctionhouse-gateway-fault-[a-z0-9-]+$/.test(config.databaseContainer)
  && Number.isInteger(config.javaPid) && config.javaPid > 0, 'Use the explicit owned loopback fault fixture');
const password = process.env.AUCTIONHOUSE_DEMO_PASSWORD;
requireThat(password, 'Supply the private local demo credential through the environment');
const output = resolve(config.outputDirectory), parent = await realpath(dirname(output));
requireThat(parent === dirname(output) && relative(repo, output).startsWith('..' + (process.platform === 'win32' ? '\\' : '/')), 'Evidence must be outside source, under an existing real parent');
await mkdir(output);
const gatewayOrigin = 'http://127.0.0.1:' + port;
const report = { schemaVersion: 1, startedAt: new Date().toISOString(), status: 'incomplete',
  environment: { platform: process.platform, node: process.version, javaPid: config.javaPid, javaOrigin: base.origin,
    databaseContainer: config.databaseContainer, gatewayPort: port, gatewayLimits: { maxEvents:128,maxBytes:262144,authIntervalMs:750,authTimeoutMs:650 },
    instrumentation: '100ms gateway IPC write/socket/RSS counters; 500ms exact-PID Windows netstat samples; private owned PostgreSQL pg_stat_statements.' },
  sourceHashes: {}, cases: [], accounting: [], gatewayProcesses: [], socketSamples: [] };
for (const name of ['src/server.ts','src/buffer.ts','src/sse.ts','tools/real-stack-faults.mjs','tools/fault-gateway-child.mjs','tools/slow-reader.py','tools/fault-contracts.mjs'])
  report.sourceHashes[name] = hash(await readFile(join(here, '..', name), 'utf8'));
const save = async () => writeFile(join(output, 'report.json'), JSON.stringify(report,null,2)+'\n');
let child, currentProcess, slow, sampling = false, ending = false;
const connections = new Set();
async function until(predicate, name, milliseconds=15000) {
  const end=performance.now()+milliseconds;
  while(!await predicate()){ requireThat(performance.now()<end, 'Timed out: '+name); await delay(25); }
}
async function db(sql) {
  const result=await exec('docker',['exec',config.databaseContainer,'psql','-X','-At','-v','ON_ERROR_STOP=on','-U','auctionhouse','-d','auctionhouse','-c',sql],{windowsHide:true,timeout:15000,maxBuffer:32*1024*1024});
  return JSON.parse(result.stdout.trim());
}
async function durable(auction) {
  requireThat(/^[a-f0-9-]{36}$/.test(auction), 'Invalid fixture auction');
  return db(`SELECT COALESCE(json_agg(jsonb_build_object('eventId',event_id,'eventType',event_type,'schemaVersion',schema_version,'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'occurredAt',occurred_at,'payload',payload) ORDER BY aggregate_version),'[]') FROM outbox_events WHERE aggregate_id='${auction}'::uuid`);
}
async function authStats() {
  const rows=await db("SELECT COALESCE(json_agg(json_build_object('queryId',queryid::text,'calls',calls,'executionMs',total_exec_time,'rows',rows)),'[]') FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname='auctionhouse') AND query LIKE 'SELECT a.id,a.display_name,a.role FROM auth_access_tokens t%'");
  return rows.reduce((sum,row)=>({calls:sum.calls+row.calls,executionMs:sum.executionMs+row.executionMs,rows:sum.rows+row.rows,queryIds:[...sum.queryIds,row.queryId]}),{calls:0,executionMs:0,rows:0,queryIds:[]});
}
async function health(){return (await fetch(gatewayOrigin+'/health',{signal:AbortSignal.timeout(3000)})).json();}
async function startGateway(){
  requireThat(!child,'Owned gateway is already running');
  child=fork(join(here,'fault-gateway-child.mjs'),[],{silent:true,env:Object.fromEntries(Object.entries(process.env).filter(([key])=>!/(PASSWORD|TOKEN|SECRET|COOKIE)/i.test(key)))});
  currentProcess={pid:child.pid,startedAt:new Date().toISOString(),observations:[]};report.gatewayProcesses.push(currentProcess);
  child.stdout.on('data',()=>{});child.stderr.on('data',()=>{});
  child.on('message',message=>{if(message.observation)currentProcess.observations.push({at:new Date().toISOString(),...message.observation});});
  const ready=once(child,'message');child.send({upstream:base.origin,port});
  const [message]=await Promise.race([ready,delay(10000).then(()=>{throw new Error('Gateway child startup timed out');})]);
  requireThat(message.ready,'Gateway did not confirm readiness');
}
async function killGateway(){
  if(!child)return;
  const owned=child, processRecord=currentProcess;child=undefined;
  const exited=once(owned,'exit'); owned.kill('SIGKILL');
  const [code,signal]=await exited;processRecord.stoppedAt=new Date().toISOString();processRecord.stop={requested:'SIGKILL',code,signal};
}
class Session {
  cookies=new Map();csrf;
  header(){return [...this.cookies].map(([key,value])=>key+'='+value).join('; ');}
  async request(path,{method='GET',body,headers={},expected=200}={}){
    if(method!=='GET'&&!this.csrf)this.csrf=await this.request('/api/auth/csrf');
    const response=await fetch(base.origin+path,{method,redirect:'error',signal:AbortSignal.timeout(12000),headers:{Cookie:this.header(),...headers,
      ...(method==='GET'?{}:{[this.csrf.headerName]:this.csrf.token}),...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body)});
    for(const line of response.headers.getSetCookie()){const pair=line.split(';')[0],split=pair.indexOf('=');if(/max-age=0/i.test(line))this.cookies.delete(pair.slice(0,split));else this.cookies.set(pair.slice(0,split),pair.slice(split+1));}
    requireThat(response.status===expected,`HTTP_${response.status} ${method} ${path.split('?')[0]}`);
    return response.status===204?null:response.json();
  }
  async login(name){await this.request('/api/auth/demo/login',{method:'POST',body:{username:name,password}});this.csrf=undefined;return this;}
}
async function connect(origin,auction,cookie,cursor){
  const abort=new AbortController();
  const response=await fetch(origin+`/api/auctions/${auction}/events`,{headers:{Cookie:cookie,...(cursor?{'Last-Event-ID':cursor}:{})},signal:abort.signal,redirect:'error'});
  requireThat(response.status===200,'Stream HTTP_'+response.status);
  const c={frames:[],closed:false,failure:null,abort};connections.add(c);
  const parser=new Parser();const reader=response.body.getReader();
  c.task=(async()=>{try{for(;;){const next=await reader.read();if(next.done)break;for(const frame of parser.feed(next.value))c.frames.push(frame);}}catch(error){if(!abort.signal.aborted)c.failure=error.name;}finally{c.closed=true;await reader.cancel().catch(()=>{});}})();
  c.close=async()=>{abort.abort();await c.task;connections.delete(c);};return c;
}
const version=c=>Math.max(0,...c.frames.map(f=>Number(f.id?.split(':').at(-1)||0)));
async function waitVersion(c,v){await until(()=>version(c)>=v||c.closed,'stream version '+v);requireThat(version(c)>=v,'Stream ended before expected version');}
async function expectDenied(auction,cookie){const r=await fetch(gatewayOrigin+`/api/auctions/${auction}/events`,{headers:{Cookie:cookie},signal:AbortSignal.timeout(3000)});await r.body?.cancel();requireThat(r.status===401,'Revoked credential reopened a stream');return r.status;}
let seller,bidder;
async function auction(label,large=false){const value=await seller.request('/api/auctions',{method:'POST',expected:201,body:{title:'Gateway fault '+label+' '+report.startedAt,description:large?'x'.repeat(3900):'Owned local fault fixture; no payment.',openingPriceMinor:100,minimumIncrementMinor:10,endsAt:new Date(Date.now()+600000).toISOString()}});await seller.request(`/api/auctions/${value.id}/publish`,{method:'POST'});return value.id;}
async function bid(id,index,session=bidder){const key='gateway-fault-'+index;return session.request(`/api/auctions/${id}/bids`,{method:'POST',headers:{'Idempotency-Key':key},body:{amountMinor:100+10*index}});}
async function durableBids(id,outcomes,session=bidder){
  let history=[],before;for(;;){const page=await session.request(`/api/auctions/${id}/bids?limit=100${before?'&beforeVersion='+before:''}`);history.push(...page);if(page.length<100)break;before=page.at(-1).auctionVersion;}
  const accepted=outcomes.filter(o=>o.accepted);requireThat(history.length===accepted.length,'Durable bid history count mismatch');
  const byId=new Map(history.map(b=>[b.id,b]));
  for(const o of outcomes){const replay=await session.request(`/api/auctions/${id}/bid-intents/${o.key}`);requireThat(canonical(replay)===canonical(o),'Durable intent replay mismatch');const b=byId.get(o.bidId);requireThat(o.accepted&&b?.amountMinor===o.amountMinor&&b?.auctionVersion===o.auctionVersion,'Accepted history identity mismatch');}
  return {accepted:accepted.length,history:history.length,exactOutcomeReplays:outcomes.length};
}
async function restartCase(){
 const id=await auction('process-restart'),direct=await connect(base.origin,id,bidder.header(),'v1:'+id+':2');
 const first=await connect(gatewayOrigin,id,bidder.header());await waitVersion(first,2);await until(async()=>(await health()).metrics.upstreamOpened>=1,'initial source');
 const outcomes=[];for(let i=0;i<2;i++)outcomes.push(await bid(id,i));await waitVersion(first,4);const cursor='v1:'+id+':4';
 const beforePid=child.pid;await killGateway();await until(()=>first.closed,'forced process EOF');
 for(let i=2;i<4;i++)outcomes.push(await bid(id,i));await waitVersion(direct,6);
 const state=await bidder.request('/api/auctions/'+id);await startGateway();const second=await connect(gatewayOrigin,id,bidder.header(),cursor);await waitVersion(second,6);
 requireThat(snapshotOracle(second.frames.find(f=>f.event==='snapshot'),state),'Restart snapshot/state watermark mismatch');
 outcomes.push(await bid(id,4));await waitVersion(second,7);await waitVersion(direct,7);
 const source=await durable(id);const checks={direct:eventOracle(direct.frames,source),beforeCrash:eventOracle(first.frames,source,{through:4}),afterRecovery:eventOracle(second.frames,source,{after:6})};
 requireThat(Object.values(checks).every(c=>c.passed),'Restart event identity oracle failed');
 report.cases.push({name:'process-kill-and-original-cursor-recovery',auctionId:id,beforePid,afterPid:child.pid,originalCursor:cursor,offlineAcceptedVersions:[5,6],recoverySnapshotVersion:6,checks,bids:await durableBids(id,outcomes),passed:true});
 await Promise.all([first.close(),second.close(),direct.close()]);
}
async function authCase(){
 const id=await auction('rotation-and-revocation'),a=await new Session().login('bidder'),b=await new Session().login('bidder');
 const oldCookie=a.header(),oldRefresh=a.cookies.get('AH_REFRESH');
 const first=await connect(gatewayOrigin,id,oldCookie);await waitVersion(first,2);const second=await connect(gatewayOrigin,id,b.header());await waitVersion(second,2);
 await a.request('/api/auth/refresh',{method:'POST',expected:204});a.csrf=undefined;const rotatedCookie=a.header();requireThat(rotatedCookie!==oldCookie,'Refresh did not rotate credentials');
 const rotated=await connect(gatewayOrigin,id,rotatedCookie);await waitVersion(rotated,2);
 const outcomes=[await bid(id,0,b)];await Promise.all([first,second,rotated].map(c=>waitVersion(c,3)));
 const revokedAt=performance.now();const reuse=await fetch(base.origin+'/api/auth/mobile/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:oldRefresh}),signal:AbortSignal.timeout(5000)});await reuse.body?.cancel();requireThat(reuse.status===401,'Refresh reuse was not rejected');
 await until(()=>first.closed&&rotated.closed,'revoked family stream closure',5000);const disconnectMs=performance.now()-revokedAt;
 requireThat(!second.closed,'Independent session family was incorrectly revoked');
 await delay(250);outcomes.push(await bid(id,1,b));await waitVersion(second,4);
 const denied=[await expectDenied(id,oldCookie),await expectDenied(id,rotatedCookie)];
 const source=await durable(id),checks={revokedOriginal:eventOracle(first.frames,source,{through:3}),revokedRotated:eventOracle(rotated.frames,source,{through:3}),survivingFamily:eventOracle(second.frames,source)};
 requireThat(Object.values(checks).every(c=>c.passed),'Revocation event oracle failed');const bids=await durableBids(id,outcomes,b);
 const lastCookie=b.header();await b.request('/api/auth/logout',{method:'POST',expected:204});await until(()=>second.closed,'logout closure',5000);
 await killGateway();await startGateway();denied.push(await expectDenied(id,lastCookie));
 report.cases.push({name:'real-refresh-reuse-family-revocation-and-nonresurrection',auctionId:id,refreshRotated:true,reuseStatus:reuse.status,revocationDisconnectMs:disconnectMs,deniedStatuses:denied,checks,bids,passed:true});
 await Promise.all([first.close(),second.close(),rotated.close()]);
}
async function slowCase(){
 const id=await auction('blocked-tcp-reader',true),healthy=await connect(gatewayOrigin,id,bidder.header());await waitVersion(healthy,2);
 slow=spawn(config.pythonExecutable??'python',[join(here,'slow-reader.py')],{windowsHide:true,stdio:['pipe','pipe','pipe']});
 let lines='',messages=[];slow.stdout.on('data',data=>{lines+=data;let index;while((index=lines.indexOf('\n'))>=0){messages.push(JSON.parse(lines.slice(0,index)));lines=lines.slice(index+1);}});slow.stderr.on('data',()=>{});
 slow.stdin.write(JSON.stringify({host:'127.0.0.1',port,path:`/api/auctions/${id}/events`,cookie:bidder.header()})+'\n');
 await until(()=>messages.length,'blocked reader headers');requireThat(messages[0].status===200,'Blocked reader did not authenticate');
 const before=await health(),outcomes=[],start=performance.now();
 while(outcomes.length<2000&&performance.now()-start<90000){outcomes.push(await bid(id,outcomes.length));await delay(20);if(outcomes.length%10===0&&(await health()).metrics.slowDisconnected>before.metrics.slowDisconnected)break;requireThat(!healthy.closed,'Healthy peer disconnected during slow-reader case');}
 const after=await health();requireThat(after.metrics.slowDisconnected>before.metrics.slowDisconnected,'No observed slow-client overflow within the declared event/time bound');
 await waitVersion(healthy,outcomes.length+2);slow.stdin.end('drain\n');await until(()=>messages.length>1,'blocked reader transport termination',15000);
 requireThat(readerTerminated(messages[1]),'Blocked receiver did not observe EOF or an actual transport reset');
 const source=await durable(id),check=eventOracle(healthy.frames,source);requireThat(check.passed,'Healthy peer durable event oracle failed');
 const snapshot=await bidder.request(`/api/auctions/${id}/snapshot`),reconnected=await connect(gatewayOrigin,id,bidder.header(),'v1:'+id+':2');await waitVersion(reconnected,snapshot.auction.version);
 requireThat(snapshotOracle(reconnected.frames.find(f=>f.event==='snapshot'),snapshot.auction),'Slow-client reconnect did not recover exact authoritative snapshot');
 report.cases.push({name:'real-blocked-tcp-reader',auctionId:id,acceptedBids:outcomes.length,durationMs:performance.now()-start,reader:messages,slowDisconnectDelta:after.metrics.slowDisconnected-before.metrics.slowDisconnected,healthyPeer:check,recoveredVersion:snapshot.auction.version,bids:await durableBids(id,outcomes),passed:true});
 await healthy.close();await reconnected.close();slow.kill();slow=undefined;
}
async function accountingCase(mode,ordinal){
 const id=await auction('auth-accounting-'+mode+'-'+ordinal),before=await authStats(),startedAt=new Date().toISOString(),clients=[];
 const metricBefore=await health();
 for(let i=0;i<8;i++)clients.push(await connect(mode==='java'?base.origin:gatewayOrigin,id,bidder.header(),'v1:'+id+':2'));
 await delay(1000);const outcomes=[],lateness=[],started=performance.now();
 for(let i=0;i<20;i++){const target=started+i*250;await delay(Math.max(0,target-performance.now()));lateness.push(Math.max(0,performance.now()-target));outcomes.push(await bid(id,i));}
 await Promise.all(clients.map(c=>waitVersion(c,22)));const finishedAt=new Date().toISOString(),after=await authStats(),metricAfter=await health(),source=await durable(id);
 const checks=clients.map(c=>eventOracle(c.frames,source));requireThat(checks.every(c=>c.passed),'Accounting event oracle failed');
 report.accounting.push({mode,ordinal,auctionId:id,startedAt,finishedAt,clients:8,events:20,scheduledIntervalMs:250,submissionLatenessMs:lateness,databaseAccessLookups:difference(before,after),gatewayAuthorizationHttpAttempts:metricAfter.metrics.authorizationRequests-metricBefore.metrics.authorizationRequests,checks,passed:true});
 await Promise.all(clients.map(c=>c.close()));await delay(1000);
}
const sampler=setInterval(async()=>{
 if(sampling||ending)return;sampling=true;
 try{if(process.platform==='win32'){const targetPids=[config.javaPid,...(child?[child.pid]:[])];const r=await exec('netstat',['-ano','-p','tcp'],{windowsHide:true,timeout:3000,maxBuffer:4*1024*1024});const counts={};for(const pid of targetPids)counts[pid]={total:0,established:0};for(const line of r.stdout.split(/\r?\n/)){const parts=line.trim().split(/\s+/),pid=Number(parts.at(-1));if(parts[0]==='TCP'&&counts[pid]){counts[pid].total++;if(parts[3]==='ESTABLISHED')counts[pid].established++;}}report.socketSamples.push({at:new Date().toISOString(),counts});}}
 catch{report.socketSamples.push({at:new Date().toISOString(),unavailable:true});}finally{sampling=false;}
},500);
try{
 const inspected=JSON.parse((await exec('docker',['inspect',config.databaseContainer],{windowsHide:true})).stdout)[0];
 requireThat(inspected.Config.Labels?.Purpose==='gateway-real-faults','Database is not the owned labelled fixture');
 requireThat((await fetch(base.origin+'/actuator/health/readiness')).status===200,'Java not ready');
 seller=await new Session().login('seller');bidder=await new Session().login('bidder');await startGateway();
 await restartCase();await save();await authCase();await save();await slowCase();await save();
 for(const [index,mode] of ['java','gateway','gateway','java','java','gateway'].entries()){await accountingCase(mode,index+1);await save();}
 report.status='passed';
}catch(error){report.failure=error.message;report.status='failed';process.exitCode=1;}
finally{
 ending=true;clearInterval(sampler);for(const c of [...connections])await c.close().catch(()=>{});slow?.kill();await killGateway();
 report.finishedAt=new Date().toISOString();report.scope='Real local Java/PostgreSQL tokens, durable committed event IDs and gateway process/socket faults. Instrumented fixture, not hosted OIDC/AWS/capacity evidence.';
 await save();console.log(JSON.stringify({status:report.status,cases:report.cases.length,accountingRuns:report.accounting.length,outputDirectory:output,failure:report.failure??null}));
}
