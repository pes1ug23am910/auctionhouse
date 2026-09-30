import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
const output = resolve(process.argv[2] || '');
const summary = JSON.parse(await readFile(resolve(output, 'summary.json'), 'utf8'));
const environment = JSON.parse((await readFile(resolve(output, 'environment.json'), 'utf8')).replace(/^\uFEFF/, ''));
const since = new Date(environment.StartedAt).toISOString();
const completion = JSON.parse((await readFile(resolve(output, 'exit.json'), 'utf8')).replace(/^\uFEFF/, ''));
const until = new Date(completion.FinishedAt).toISOString();
const seed = summary.configuration.seed;
if (!Number.isInteger(seed)) throw new Error('Invalid seed');
function query(sql) {
  return JSON.parse(execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'auctionhouse', '-d', 'auctionhouse', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql], { encoding: 'utf8', timeout: 10000 }));
}
const fixture = query("SELECT coalesce(json_agg(id),'[]'::json) FROM auctions WHERE title='Load experiment seed " + seed + "' AND created_at>='" + since + "'::timestamptz AND created_at<='" + until + "'::timestamptz");
if (fixture.length !== 1) throw new Error('Expected exactly one new fixture auction, observed ' + fixture.length);
const id = fixture[0];
const sql = `WITH history AS (
 SELECT amount,auction_version,lag(amount) OVER(ORDER BY auction_version) previous_amount,
 lag(auction_version) OVER(ORDER BY auction_version) previous_version FROM bids WHERE auction_id='${id}'
), event_rows AS (SELECT event_id,published_at FROM outbox_events WHERE aggregate_id='${id}')
SELECT json_build_object(
 'auctionId','${id}','bids',(SELECT count(*) FROM history),
 'acceptedIntents',(SELECT count(*) FROM bid_intents WHERE auction_id='${id}' AND accepted),
 'rejectedIntents',(SELECT count(*) FROM bid_intents WHERE auction_id='${id}' AND NOT accepted),
 'events',(SELECT count(*) FROM event_rows),
 'pendingEvents',(SELECT count(*) FROM event_rows WHERE published_at IS NULL),
 'notificationEffects',(SELECT count(*) FROM notification_effects WHERE event_id IN(SELECT event_id FROM event_rows)),
 'progressionViolations',(SELECT count(*) FROM history WHERE previous_amount IS NOT NULL AND(amount<previous_amount+10 OR auction_version<>previous_version+1)),
 'firstBidVersion',(SELECT min(auction_version) FROM history),
 'stateMatchesHistory',(SELECT highest_bid_amount IS NOT DISTINCT FROM (SELECT max(amount) FROM history) AND version=2+(SELECT count(*) FROM history) FROM auctions WHERE id='${id}')
)`;
let state;
for (let attempt=0; attempt<15; attempt++) {
 state=query(sql);
 if(state.pendingEvents===0 && state.notificationEffects===state.events) break;
 await new Promise(resolve=>setTimeout(resolve,1000));
}
const checks = {
 uniqueAcceptedEffects: state.bids === summary.acceptedBids && state.acceptedIntents === summary.acceptedBids,
 durableRejections: state.rejectedIntents === summary.businessRejectedBids,
 noMissingEventEffects: state.events === state.bids+2 && state.pendingEvents === 0 && state.notificationEffects === state.events,
 monotonicHistory: state.progressionViolations===0 && (state.bids===0 || state.firstBidVersion===3) && state.stateMatchesHistory,
 noUnreconciledClientErrors: summary.infrastructureErrorCount===0,
};
const result={recordedAt:new Date().toISOString(),state,checks,passed:Object.values(checks).every(Boolean),
 scope:'Counts, accepted price/version progression, durable intents and one sink effect per event for this isolated load auction. Full concurrent history checks are covered separately by the contention experiment.'};
await writeFile(resolve(output,'reconciliation.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));
if(!result.passed) process.exitCode=1;
