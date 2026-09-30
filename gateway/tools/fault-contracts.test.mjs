import test from 'node:test';
import assert from 'node:assert/strict';
import {eventOracle, snapshotOracle, difference, readerTerminated} from './fault-contracts.mjs';
const source=[3,4].map(version=>({eventId:'event-'+version,aggregateId:'lot',aggregateVersion:version,payload:{version}}));
const frames=()=>source.map(e=>({event:'auction',id:`v1:lot:${e.aggregateVersion}`,data:JSON.stringify(e)}));
test('durable event oracle rejects count-preserving identity/content substitutions, duplicates and out-of-order delivery',()=>{
 assert.equal(eventOracle(frames(),source).passed,true);
 for(const change of [f=>f.reverse(),f=>{f[1]=f[0]},f=>{f[0].data=f[0].data.replace('event-3','uncommitted')},f=>{f[0].data=f[0].data.replace('"version":3','"version":9')},f=>f.pop()]){
  const actual=frames();change(actual);assert.equal(eventOracle(actual,source).passed,false);
 }
});
test('snapshot recovery requires both exact authoritative state and matching watermark',()=>{
 const state={id:'lot',version:4,highestBidAmountMinor:110};
 const frame={event:'snapshot',id:'v1:lot:4',data:JSON.stringify({auction:state,cursor:'v1:lot:4'})};
 assert.equal(snapshotOracle(frame,state),true);
 assert.equal(snapshotOracle({...frame,id:'v1:lot:3'},state),false);
 assert.equal(snapshotOracle(frame,{...state,highestBidAmountMinor:120}),false);
});
test('database accounting is a delta with an explicit access-query scope',()=>{
 const value=difference({calls:10,executionMs:2,rows:8},{calls:16,executionMs:3,rows:13,queryIds:['known']});
 assert.deepEqual([value.calls,value.executionMs,value.rows],[6,1,5]);assert.match(value.scope,/not all/);
});

test('slow receiver proof requires transport termination, not a read timeout or arbitrary message',()=>{
 for(const message of [{eof:true},{eof:false,failure:'ConnectionResetError'},{eof:false,failure:'ConnectionAbortedError'}]) assert.equal(readerTerminated(message),true);
 for(const message of [undefined,{}, {ready:true},{eof:false,failure:'TimeoutError'},{eof:false,failure:'ValueError'}]) assert.equal(readerTerminated(message),false);
});
