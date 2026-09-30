import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const output = resolve(process.env.PROBE_OUTPUT || 'probe-evidence');
const duration = Number(process.env.PROBE_DURATION_SECONDS || 120);
if (!Number.isInteger(duration) || duration < 10 || duration > 600) throw new Error('Duration must be 10..600 seconds');
const target = 'http://127.0.0.1:8080/actuator/health';
await mkdir(output, { recursive: true });
const rows = [], transitions = [];
let previousState;
const beginning = Date.now();
while (Date.now() - beginning < duration * 1000) {
  const started = Date.now();
  let success = false, status = 0;
  try {
    const response = await fetch(target, { signal: AbortSignal.timeout(1500) });
    status = response.status;
    success = response.ok && (await response.json()).status === 'UP';
  } catch { /* Connection refusal and timeout are failed probes. */ }
  const metric = { resourceMetrics: [{
    resource: { attributes: [{ key: 'service.name', value: { stringValue: 'auctionhouse-local-probe' } }] },
    scopeMetrics: [{ scope: { name: 'auctionhouse.probe' }, metrics: [{
      name: 'auctionhouse.probe.success', description: 'One for successful HTTP health, zero for failure',
      gauge: { dataPoints: [{ timeUnixNano: String(BigInt(Date.now()) * 1000000n), asDouble: success ? 1 : 0 }] },
    }] }],
  }] };
  let exported = false;
  try {
    const response = await fetch('http://127.0.0.1:4318/v1/metrics', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(metric), signal: AbortSignal.timeout(1500),
    });
    exported = response.ok;
  } catch { /* A missing telemetry pipeline is recorded independently. */ }
  const row = { recordedAt: new Date().toISOString(), success, status, exported, elapsedMilliseconds: Date.now() - started };
  rows.push(row);
  await appendFile(resolve(output, 'probes.jsonl'), JSON.stringify(row) + '\n');
  if (rows.length % 5 === 0) {
    try {
      const response = await fetch('http://127.0.0.1:9090/api/v1/alerts', { signal: AbortSignal.timeout(1500) });
      const body = await response.json();
      const alerts = body.data.alerts.filter(a => a.labels.alertname === 'AuctionhouseProbeUnavailable')
        .map(a => ({ state: a.state, activeAt: a.activeAt, name: a.labels.alertname }));
      const state = alerts[0]?.state || 'inactive';
      if (state !== previousState) {
        const transition = { recordedAt: new Date().toISOString(), state, alerts };
        transitions.push(transition); previousState = state;
        await appendFile(resolve(output, 'alert-transitions.jsonl'), JSON.stringify(transition) + '\n');
      }
    } catch { await appendFile(resolve(output, 'alert-query-errors.jsonl'), JSON.stringify({ recordedAt: new Date().toISOString() }) + '\n'); }
  }
  await new Promise(resolve => setTimeout(resolve, Math.max(0, 1000 - (Date.now() - started))));
}
const failures = rows.filter(row => !row.success).length;
const result = {
  recordedAt: new Date().toISOString(), target, requestedDurationSeconds: duration,
  attempts: rows.length, successful: rows.length - failures, failures,
  availabilityRatio: (rows.length - failures) / rows.length,
  telemetryExportFailures: rows.filter(row => !row.exported).length,
  proposedAvailabilitySlo: 0.99, allowedFailedProbes: rows.length * 0.01,
  consumedErrorBudgetMultiple: failures / (rows.length * 0.01),
  alertFired: transitions.some(row => row.state === 'firing'),
  alertRecovered: transitions.some((row, index) => row.state === 'inactive' && transitions.slice(0, index).some(before => before.state === 'firing')),
  transitions,
  scope: 'Controlled local process-stop fixture; probe-count availability is illustrative, not a production SLO assessment.',
};
await writeFile(resolve(output, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
