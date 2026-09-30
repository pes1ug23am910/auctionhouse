import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const origins=(process.env.AUCTIONHOUSE_INSTANCE_ORIGINS ?? 'http://127.0.0.1:8080,http://127.0.0.1:8082').split(',');
const password=process.env.AUCTIONHOUSE_DEMO_PASSWORD;
const output=process.env.AUCTIONHOUSE_INSTANCE_OUTPUT;
if(!password || !output || origins.length!==2 || origins.some(origin => !/^http:\/\/127\.0\.0\.1:\d+$/.test(origin))) {
  throw new Error('Provide a local demo password, output directory and two loopback HTTP origins.');
}
class Session {
  cookies=new Map();
  header() {return [...this.cookies].map(([name,value]) => name+'='+value).join('; ');}
  async send(instance,path,method='GET',body,extra={},discard=false) {
    const response=await fetch(origins[instance]+path,{method,redirect:'error',signal:AbortSignal.timeout(30000),
      headers:{Cookie:this.header(),...(body?{'Content-Type':'application/json'}:{}),...extra},
      body:body?JSON.stringify(body):undefined});
    for(const entry of response.headers.getSetCookie()) {
      const pair=entry.split(';',1)[0],split=pair.indexOf('='),name=pair.slice(0,split),value=pair.slice(split+1);
      if(/max-age=0/i.test(entry)) this.cookies.delete(name);else this.cookies.set(name,value);
    }
    if(discard) {await response.body?.cancel();return {status:response.status,bodyDiscarded:true};}
    return {status:response.status,body:response.status===204?null:await response.json()};
  }
  async csrf(instance=0) {
    const response=await this.send(instance,'/api/auth/csrf');
    assert.equal(response.status,200);
    return {[response.body.headerName]:response.body.token};
  }
  async login(name) {
    // The pre-auth JDBC session and its CSRF token originate on instance 0.
    const csrf=await this.csrf(0);
    const response=await this.send(1,'/api/auth/demo/login','POST',{username:name,password},csrf);
    assert.equal(response.status,200,'CSRF session from instance 0 must authenticate on instance 1');
    return response.body;
  }
}
const report={startedAt:new Date().toISOString(),origins,node:process.version,
  platform:process.platform,architecture:process.arch,checks:[],complete:false,
  label:'Two live HTTP JVM processes sharing PostgreSQL; local demo authentication, no load/capacity claim'};
await mkdir(output,{recursive:true});
try {
  const seller=new Session(),bidder=new Session();
  const sellerAccount=await seller.login('seller'),bidderAccount=await bidder.login('bidder');
  const identities=await Promise.all(origins.map((_,i) => bidder.send(i,'/api/auth/session')));
  identities.forEach(response => {assert.equal(response.status,200);assert.deepEqual(response.body,bidderAccount);});
  report.checks.push({name:'shared pre-auth CSRF session and application auth cookies',passed:true,instances:2});
  const sellerCsrf=await seller.csrf(0);
  const created=await seller.send(1,'/api/auctions','POST',{
    title:'Two-instance verification '+report.startedAt,description:'Isolated local HTTP integration fixture; no payment.',
    openingPriceMinor:100,minimumIncrementMinor:10,endsAt:new Date(Date.now()+600000).toISOString()
  },sellerCsrf);
  assert.equal(created.status,201);
  const id=created.body.id;report.auctionId=id;report.sellerId=sellerAccount.id;report.bidderId=bidderAccount.id;
  const published=await seller.send(0,'/api/auctions/'+id+'/publish','POST',undefined,sellerCsrf);
  assert.equal(published.status,200);
  const csrf=await bidder.csrf(0),key='two-instance-'+randomUUID();
  const responses=await Promise.all(Array.from({length:12},(_,i) => bidder.send(i%2,'/api/auctions/'+id+'/bids',
    'POST',{amountMinor:150},{...csrf,'Idempotency-Key':key})));
  responses.forEach(response => {assert.equal(response.status,200);assert.deepEqual(response.body,responses[0].body);});
  assert.equal(responses[0].body.accepted,true);
  const history1=await bidder.send(1,'/api/auctions/'+id+'/bids');
  assert.equal(history1.status,200);assert.equal(history1.body.length,1);
  assert.equal(history1.body[0].id,responses[0].body.bidId);
  report.checks.push({name:'same key across two ports has one bid effect',passed:true,requests:12,
    requestsPerInstance:6,statuses:responses.map(r => r.status),retainedOutcome:responses[0].body,history:history1.body});
  const lostKey='discarded-response-'+randomUUID();
  const discarded=await bidder.send(0,'/api/auctions/'+id+'/bids','POST',{amountMinor:200},
    {...csrf,'Idempotency-Key':lostKey},true);
  assert.equal(discarded.status,200);
  const recovered=await bidder.send(1,'/api/auctions/'+id+'/bid-intents/'+lostKey);
  assert.equal(recovered.status,200);assert.equal(recovered.body.accepted,true);assert.equal(recovered.body.amountMinor,200);
  const replay=await bidder.send(1,'/api/auctions/'+id+'/bids','POST',{amountMinor:200},
    {...csrf,'Idempotency-Key':lostKey});
  assert.equal(replay.status,200);assert.deepEqual(replay.body,recovered.body);
  const conflict=await bidder.send(1,'/api/auctions/'+id+'/bids','POST',{amountMinor:250},
    {...csrf,'Idempotency-Key':lostKey});
  assert.equal(conflict.status,409);assert.equal(conflict.body.code,'IDEMPOTENCY_CONFLICT');
  const history2=await bidder.send(0,'/api/auctions/'+id+'/bids');
  assert.equal(history2.status,200);assert.equal(history2.body.length,2);
  assert.deepEqual(history2.body.map(row => row.amountMinor),[200,150]);
  report.checks.push({name:'discarded response reconciles on second instance and replay has no extra effect',passed:true,
    discarded,recovered:recovered.body,replayStatus:replay.status,history:history2.body});
  report.checks.push({name:'same intent with a different amount conflicts',passed:true,status:conflict.status,code:conflict.body.code});
  report.complete=true;
} catch(error) {
  // Errors contain assertion details about public auction payloads, never request headers or credentials.
  report.failure={name:error.name,message:error.message,stack:error.stack};
  process.exitCode=1;
} finally {
  report.finishedAt=new Date().toISOString();
  report.gitHead=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
  report.scriptSha256=createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex');
  report.limits='Response loss is deliberately discarding the successful response body after headers; this does not simulate arbitrary TCP loss or process crash. HTTP history proves bid effects; database outbox counting is a separate SQL check. No secrets or cookie values are retained.';
  await writeFile(output+'/result.json',JSON.stringify(report,null,2)+'\n');
  process.stdout.write(JSON.stringify({complete:report.complete,checks:report.checks.map(({name,passed}) => ({name,passed})),
    auctionId:report.auctionId,output})+'\n');
}
