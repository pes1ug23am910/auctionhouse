import assert from 'node:assert/strict';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { randomBytes, createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const base=process.env.AUCTIONHOUSE_UPSTREAM ?? 'http://127.0.0.1:8080';
const password=process.env.AUCTIONHOUSE_DEMO_PASSWORD;
const output=process.env.AUCTIONHOUSE_TRACE_OUTPUT;
const telemetry=process.env.AUCTIONHOUSE_TELEMETRY_DIR;
if(!password || !output || !telemetry || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Require local upstream, password, output and telemetry directory');
class Session {
  cookies=new Map();
  header() {return [...this.cookies].map(([name,value])=>name+'='+value).join('; ');}
  async request(path,method='GET',body,extra={}) {
    let csrf={};
    if(method!=='GET') {const value=await this.request('/api/auth/csrf');csrf={[value.headerName]:value.token};}
    const response=await fetch(base+path,{method,redirect:'error',signal:AbortSignal.timeout(20000),
      headers:{Cookie:this.header(),...csrf,...(body?{'Content-Type':'application/json'}:{}),...extra},
      body:body?JSON.stringify(body):undefined});
    for(const entry of response.headers.getSetCookie()) {
      const pair=entry.split(';',1)[0],split=pair.indexOf('=');
      if(/max-age=0/i.test(entry)) this.cookies.delete(pair.slice(0,split));
      else this.cookies.set(pair.slice(0,split),pair.slice(split+1));
    }
    const value=await response.json();
    assert.ok(response.ok,'Local trace request HTTP '+response.status+' code='+(value.code??'unknown'));
    return value;
  }
  async login(name) {return this.request('/api/auth/demo/login','POST',{username:name,password});}
}
function hex(value) {return /^[0-9a-f]+$/i.test(value ?? '')?value.toLowerCase():Buffer.from(value??'','base64').toString('hex');}
async function spansFor(traceId) {
  const spans=new Map();
  const names=(await readdir(telemetry)).filter(name=>name.startsWith('traces')&&name.endsWith('.jsonl'));
  for(const name of names) {
    for(const line of (await readFile(telemetry+'/'+name,'utf8')).split('\n').filter(Boolean)) {
      let batch;try {batch=JSON.parse(line);}catch {continue;}
      for(const resource of batch.resourceSpans??[]) for(const scope of resource.scopeSpans??[]) for(const span of scope.spans??[]) {
        if(hex(span.traceId)===traceId) spans.set(hex(span.spanId),{...span,traceId:hex(span.traceId),spanId:hex(span.spanId),parentSpanId:hex(span.parentSpanId),scope:scope.scope?.name});
      }
    }
  }
  return [...spans.values()];
}
function dbEffects(auctionId,version) {
  assert.match(auctionId,/^[0-9a-f-]{36}$/);assert.ok(Number.isSafeInteger(version));
  const sql="SELECT json_build_object('outbox',count(*),'traceParents',json_agg(trace_parent),'sinkEffects',"+
    "(SELECT count(*) FROM notification_effects n JOIN outbox_events o ON n.event_id=o.event_id WHERE o.aggregate_id='"+auctionId+"' AND o.aggregate_version="+version+"))"+
    " FROM outbox_events WHERE aggregate_id='"+auctionId+"' AND aggregate_version="+version;
  return JSON.parse(execFileSync('docker',['exec','auctionhouse-postgres-1','psql','-X','-U','auctionhouse','-d','auctionhouse','-At','-c',sql],{encoding:'utf8'}));
}
const report={startedAt:new Date().toISOString(),base,node:process.version,complete:false};
await mkdir(output,{recursive:true});
try {
  const seller=new Session(),bidder=new Session();await seller.login('seller');await bidder.login('bidder');
  const auction=await seller.request('/api/auctions','POST',{title:'Trace continuity '+report.startedAt,
    description:'Local HTTP to durable outbox to Kafka to notification trace fixture.',openingPriceMinor:100,
    minimumIncrementMinor:10,endsAt:new Date(Date.now()+300000).toISOString()});
  await seller.request('/api/auctions/'+auction.id+'/publish','POST');
  const traceId=randomBytes(16).toString('hex'),parentId=randomBytes(8).toString('hex');
  const outcome=await bidder.request('/api/auctions/'+auction.id+'/bids','POST',{amountMinor:150},
    {'Idempotency-Key':'trace-'+traceId,traceparent:'00-'+traceId+'-'+parentId+'-01'});
  assert.equal(outcome.accepted,true);
  Object.assign(report,{traceId,auctionId:auction.id,outcome});
  const deadline=Date.now()+45000;
  let spans=[],effects;
  do {
    await delay(1000);spans=await spansFor(traceId);effects=dbEffects(auction.id,outcome.auctionVersion);
    if(effects.sinkEffects===1 && ['outbox.record','outbox.publish','notification.apply'].every(name=>spans.some(span=>span.name===name))) break;
  } while(Date.now()<deadline);
  const named=name=>spans.filter(span=>span.name===name);
  const http=spans.filter(span=>span.kind===2 && span.name.includes('POST'));
  const kafka=spans.filter(span=>(span.attributes??[]).some(attr=>attr.key==='messaging.system'&&attr.value?.stringValue==='kafka'));
  const database=spans.filter(span=>(span.attributes??[]).some(attr=>['db.system','db.system.name'].includes(attr.key)&&['postgresql','postgres'].includes(attr.value?.stringValue)));
  assert.equal(effects.outbox,1);assert.equal(effects.sinkEffects,1);
  assert.ok(effects.traceParents[0].startsWith('00-'+traceId+'-'));
  assert.ok(http.length>0,'Missing inbound HTTP server span');assert.ok(database.length>0,'Missing actual JDBC spans');
  assert.ok(kafka.length>0,'Missing actual Kafka spans');
  for(const name of ['outbox.record','outbox.publish','notification.apply']) assert.ok(named(name).length>0,'Missing '+name);
  const byId=new Map(spans.map(span=>[span.spanId,span]));
  const ancestors=span=>{const ids=[];let current=span;for(let i=0;current&&i<100;i++){ids.push(current.spanId);current=byId.get(current.parentSpanId);}return ids;};
  const record=named('outbox.record')[0],publish=named('outbox.publish')[0],sink=named('notification.apply')[0];
  assert.ok(ancestors(publish).includes(record.spanId),'Relay does not descend from persisted recording span');
  assert.ok(ancestors(sink).includes(publish.spanId),'Notification trace does not descend from relay/Kafka chain');
  report.effects=effects;report.spans=spans;report.spanCounts={total:spans.length,http:http.length,jdbc:database.length,kafka:kafka.length};
  report.complete=true;
} catch(error) {report.failure={name:error.name,message:error.message,stack:error.stack};process.exitCode=1;}
finally {
  report.finishedAt=new Date().toISOString();
  report.scriptSha256=createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex');
  report.limits='One actual local HTTP bid and asynchronous durable delivery. Trace identity is supplied by this fixture. No throughput or cross-host claim.';
  await writeFile(output+'/result.json',JSON.stringify(report,null,2)+'\n');
  process.stdout.write(JSON.stringify({complete:report.complete,traceId:report.traceId,spanCounts:report.spanCounts,failure:report.failure?.message,output})+'\n');
}
