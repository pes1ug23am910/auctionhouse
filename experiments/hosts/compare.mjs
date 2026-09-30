import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { canonical, requireThat } from './contracts.mjs';

function comparisonKey(run) {
  const m = run.manifest;
  return canonical({ ownership: { account: m.ownership.accountId, region: m.ownership.region, session: m.ownership.sessionId },
    origin: m.origin, dependency: m.dependencyInstanceId, database: m.database, state: m.stateIsolation,
    appType: m.applicationInstanceType, dependencyType: m.dependencyInstanceType, settings: m.runtimeSettings,
    actualDatabase: run.before.database, appAmis: [...new Set(run.before.instances.filter(i => i.instanceId !== m.dependencyInstanceId).map(i => i.imageId))].sort(),
    dependencyImages: run.before.inspection.results.find(i => i.instanceId === m.dependencyInstanceId).report.containers
      .map(c => ({ name: c.name, image: c.imageReference, memory: c.memoryLimitBytes, nanoCpus: c.nanoCpus })).sort((a, b) => a.name.localeCompare(b.name)),
    applicationContainers: run.before.inspection.results.find(i => i.instanceId === m.applicationInstanceIds[0]).report.containers
      .map(c => ({ name: c.name, image: c.imageReference, memory: c.memoryLimitBytes, nanoCpus: c.nanoCpus })).sort((a, b) => a.name.localeCompare(b.name)),
    release: run.release, workload: run.workload, script: run.workloadSha256, validator: run.sessionValidatorSha256,
    actors: run.identities, client: { platform: run.client.platform, release: run.client.release, cpu: run.client.cpu,
      logicalProcessors: run.client.logicalProcessors, totalMemoryBytes: run.client.totalMemoryBytes, node: run.client.node } });
}
export function compare(runs) {
  requireThat(runs.length >= 2, 'At least one observation per topology is required');
  requireThat(runs.every(r => r.schemaVersion === 1 && r.before && r.after && r.phases?.find(p => p.phase === 'measurement')?.summary),
    'Incomplete runs cannot form a host comparison');
  const ordered = [...runs].sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt));
  requireThat(new Set(ordered.map(r => r.label)).size === ordered.length, 'Repeated run label');
  requireThat(ordered.every((r, i) => i === 0 || Date.parse(r.startedAt) >= Date.parse(ordered[i - 1].finishedAt)),
    'Overlapping workload runs confound comparison');
  const key = comparisonKey(ordered[0]);
  requireThat(ordered.every(r => comparisonKey(r) === key), 'Workload, artifacts, shared dependencies, state, or client environment differ');
  const counts = [1, 2].map(n => ordered.filter(r => r.manifest.applicationHostCount === n).length);
  requireThat(counts.every(n => n > 0), 'Both one-app-host and two-app-host observations are required');
  const balanced = counts[0] >= 3 && counts[0] === counts[1];
  const alternating = ordered.every((r, i) => i === 0 || r.manifest.applicationHostCount !== ordered[i - 1].manifest.applicationHostCount);
  const rows = ordered.map(run => {
    const p = run.phases.find(p => p.phase === 'measurement'); const s = p.summary;
    return { label: run.label, applicationHosts: run.manifest.applicationHostCount, totalEc2Hosts: run.manifest.totalEc2HostCount,
      startedAt: p.startedAt, durationSeconds: run.workload.durationSeconds, offered: s.actualOffered, started: s.started,
      completed: s.completed, dropped: s.dropped, infrastructureErrors: s.infrastructureErrorCount,
      retries: s.retryAttempts, p50Ms: s.latencyMilliseconds['p(50)'], p95Ms: s.latencyMilliseconds['p(95)'], p99Ms: s.latencyMilliseconds['p(99)'],
      accepted: s.acceptedBids, rejected: s.businessRejectedBids, loadExit: p.exit.code,
      correctnessPassed: p.reconciliation.passed, eachAppServed: run.serving?.everyApplicationServed === true,
      clientFreeMemoryBeforeBytes: run.client.freeMemoryBeforeBytes, clientFreeMemoryAfterBytes: run.client.freeMemoryAfterBytes };
  });
  return { schemaVersion: 1, recordedAt: new Date().toISOString(), balanced, alternating,
    measurementQuality: balanced && alternating && rows.every(r => r.correctnessPassed && r.eachAppServed)
      ? 'repeated comparable observations' : 'preliminary or incomplete observations', rows,
    allLoadTargetsPassed: rows.every(r => r.loadExit === 0 && r.dropped === 0 && r.infrastructureErrors === 0),
    limitations: [
      'One/two refers to application EC2 hosts; each topology also uses one dependency EC2 host and shared RDS plus an ALB.',
      'Aggregate pool capacity doubles at two application hosts; shared database, broker, cache and sink may become bottlenecks.',
      'Dataset and shared cache evolve between runs; warmups do not make cache residency identical. Review alternating order and host metrics.',
      'CloudWatch series can lag and one-minute buckets overlap short workloads; missing metrics are unknown.',
      'A lower latency sample is not a capacity result. Report dropped work, retries, correctness, client saturation and CPU credits with it.',
      'This is browse/bid/session/idempotency/event-sink traffic. It does not measure gateway SSE capacity or failover during a workload.',
    ] };
}
async function main() {
  requireThat(process.argv.length >= 5, 'Usage: node experiments/hosts/compare.mjs new-output.json run1/run.json run2/run.json [...]');
  const runs = await Promise.all(process.argv.slice(3).map(async path => JSON.parse(await readFile(path, 'utf8'))));
  const result = compare(runs);
  await writeFile(resolve(process.argv[2]), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ measurementQuality: result.measurementQuality, observations: result.rows.length,
    allLoadTargetsPassed: result.allLoadTargetsPassed }));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
