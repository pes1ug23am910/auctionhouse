import { readFile, writeFile, mkdir, mkdtemp, unlink, rmdir, realpath } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { tmpdir, cpus, totalmem, freemem, release as osRelease, platform } from 'node:os';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { Aws } from './aws.mjs';
import { Api, prepareAuction, reconcile } from './api.mjs';
import { K6_IMAGE, canonical, integer, parseOutcomes, requireThat, requireOutsideDirectory, sha256, validateCredentials,
  validateManifest, validateRelease } from './contracts.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
function outsideRepository(path) {
  requireOutsideDirectory(repo, path);
}
async function dockerRun(args, output) {
  const log = createWriteStream(output, { flags: 'wx', mode: 0o600 });
  const containerName = 'auctionhouse-load-' + randomUUID();
  const child = spawn('docker', ['run', '--name', containerName, ...args.slice(1)], { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  let expired = false;
  const timeout = setTimeout(() => { expired = true; child.kill(); }, 360000);
  try { const [code, signal] = await once(child, 'close'); return { code, signal }; }
  finally {
    clearTimeout(timeout); log.end(); await once(log, 'finish');
    if (expired) {
      const cleanup = spawn('docker', ['rm', '--force', containerName], { windowsHide: true, stdio: 'ignore' });
      await once(cleanup, 'close');
    }
  }
}
function requestCounts(inspection, dependencyId) {
  return inspection.results.find(r => r.instanceId === dependencyId)?.report.httpRequestsByInstance;
}
function servingEvidence(before, after, count) {
  const deltas = Array.from({ length: count }, (_, i) => {
    const key = `app-${i}`;
    return { instanceOrdinal: key, before: before?.[key] ?? null, after: after?.[key] ?? null,
      delta: Number.isFinite(before?.[key]) && Number.isFinite(after?.[key]) ? after[key] - before[key] : null };
  });
  return { deltas, everyApplicationServed: deltas.every(d => d.delta !== null && d.delta > 0),
    scope: 'Private collector cumulative /api/auctions HTTP counters. Includes fixture/setup/reconciliation; demonstrates use of each app during the experiment, not per-request routing or balanced distribution.' };
}

async function main() {
  requireThat(process.argv.length === 3, 'Usage: node experiments/hosts/run.mjs private-config.json');
  const configPath = await realpath(process.argv[2]);
  const config = await json(configPath); const base = dirname(configPath);
  requireThat(/^[A-Za-z0-9._-]{1,48}$/.test(config.label), 'Invalid experiment label');
  const workload = { ratePerSecond: integer(config.ratePerSecond ?? 20, 1, 200, 'ratePerSecond'),
    durationSeconds: integer(config.durationSeconds ?? 60, 10, 120, 'durationSeconds'),
    warmupSeconds: integer(config.warmupSeconds ?? 15, 10, 60, 'warmupSeconds'),
    seed: integer(config.seed ?? 42, 1, 2147483647, 'seed'),
    preAllocatedVUs: integer(config.preAllocatedVUs ?? 40, 1, 500, 'preAllocatedVUs'),
    maxVUs: integer(config.maxVUs ?? 80, 1, 500, 'maxVUs'),
    clientMemoryMiB: 256, clientCPUs: 1, k6Image: K6_IMAGE };
  requireThat(workload.maxVUs >= workload.preAllocatedVUs, 'maxVUs is below preAllocatedVUs');
  const manifest = validateManifest(await json(resolve(base, config.manifest)));
  const release = validateRelease(await json(resolve(base, config.release)));
  const credentialsPath = await realpath(resolve(base, config.credentials)); outsideRepository(credentialsPath);
  const credentials = validateCredentials(await json(credentialsPath), manifest, workload.durationSeconds + workload.warmupSeconds);
  const parent = await realpath(dirname(resolve(base, config.outputDirectory)));
  const output = join(parent, resolve(base, config.outputDirectory).split(/[\\/]/).at(-1)); outsideRepository(output);
  await mkdir(output); // Fails rather than replacing a previous experiment.
  const aws = new Aws(config.profile, manifest.ownership.region, config.awsExecutable);
  const startedAt = new Date().toISOString();
  const report = { schemaVersion: 1, label: config.label, startedAt, manifest, release, workload,
    workloadSha256: sha256(await readFile(join(repo, 'experiments/load/k6-workload.js'))),
    sessionValidatorSha256: sha256(await readFile(join(repo, 'experiments/load/session-config.js'))),
    client: { platform: platform(), release: osRelease(), node: process.version, cpu: cpus()[0]?.model,
      logicalProcessors: cpus().length, totalMemoryBytes: totalmem(), freeMemoryBeforeBytes: freemem() },
    phases: [], status: 'incomplete' };
  let privateDirectory;
  try {
    report.before = await aws.preflight(manifest, release);
    const seller = new Api(manifest.origin, credentials.seller);
    const bidder = new Api(manifest.origin, credentials.bidder);
    const admin = new Api(manifest.origin, credentials.admin);
    report.identities = { seller: await seller.identity(), bidder: await bidder.identity(), admin: await admin.identity('ADMIN') };
    privateDirectory = await mkdtemp(join(tmpdir(), 'auctionhouse-load-'));
    const sessionPath = join(privateDirectory, 'session.json');
    await writeFile(sessionPath, JSON.stringify({ origin: manifest.origin, ...credentials.bidder }), { flag: 'wx', mode: 0o600 });
    for (const phase of ['warmup', 'measurement']) {
      const seconds = phase === 'warmup' ? workload.warmupSeconds : workload.durationSeconds;
      validateCredentials(credentials, manifest, seconds);
      const auctionId = await prepareAuction(seller, seconds, workload.seed, config.label + '-' + phase);
      const phaseDirectory = join(output, phase); await mkdir(phaseDirectory);
      const phaseResult = { phase, auctionId, startedAt: new Date().toISOString() };
      report.phases.push(phaseResult);
      const args = ['run', '--rm', '--memory=256m', '--cpus=1', '--log-driver=none',
        '-e', 'AUTH_MODE=session', '-e', `BASE_URL=${manifest.origin}`, '-e', 'SESSION_FILE=/run/auth/session.json',
        '-e', `FIXTURE_AUCTION_ID=${auctionId}`, '-e', `RATE=${workload.ratePerSecond}`, '-e', `DURATION_SECONDS=${seconds}`,
        '-e', `SEED=${workload.seed}`, '-e', `PREALLOCATED_VUS=${workload.preAllocatedVUs}`, '-e', `MAX_VUS=${workload.maxVUs}`,
        '-e', `COMPARISON_LABEL=${config.label}-${phase}`, '-e', 'SUMMARY_PATH=/evidence/summary.json',
        '-v', `${join(repo, 'experiments/load')}:/scripts:ro`, '-v', `${privateDirectory}:/run/auth:ro`,
        '-v', `${phaseDirectory}:/evidence`, K6_IMAGE, 'run', '--no-usage-report', '--log-format=json', '/scripts/k6-workload.js'];
      phaseResult.exit = await dockerRun(args, join(phaseDirectory, 'raw.log'));
      phaseResult.finishedAt = new Date().toISOString();
      let raw = await readFile(join(phaseDirectory, 'raw.log'), 'utf8');
      const leaked = Object.values(credentials).filter(v => v && typeof v === 'object').some(v => raw.includes(v.accessToken));
      if (leaked) {
        for (const v of Object.values(credentials)) if (v?.accessToken) raw = raw.replaceAll(v.accessToken, '[REDACTED]');
        await writeFile(join(phaseDirectory, 'raw.log'), raw); throw new Error('Credential appeared in workload output; output was redacted');
      }
      phaseResult.summary = await json(join(phaseDirectory, 'summary.json'));
      phaseResult.outcomeCount = parseOutcomes(raw).length;
      phaseResult.reconciliation = await reconcile(bidder, admin, auctionId, parseOutcomes(raw), phaseResult.summary);
      await save(join(phaseDirectory, 'reconciliation.json'), phaseResult.reconciliation);
      if (phase === 'warmup') {
        requireThat(phaseResult.reconciliation.passed, 'Warmup correctness failed');
        await new Promise(resolve => setTimeout(resolve, 12000));
        report.measurementBaseline = await aws.inspect(manifest, release,
          report.before.instances.find(i => i.instanceId === manifest.dependencyInstanceId).privateIpAddress);
      }
    }
    // Allow the bounded exporter/collector interval to make the last requests observable.
    await new Promise(resolve => setTimeout(resolve, 12000));
    report.after = await aws.preflight(manifest, release);
    const artifacts = inspected => canonical(inspected.results.map(r => ({ instanceId: r.instanceId,
      containers: r.report.containers.map(c => ({ name: c.name, image: c.imageReference, id: c.imageId,
        environment: c.runtimeEnvironment, memory: c.memoryLimitBytes, cpu: c.nanoCpus })) })));
    requireThat(artifacts(report.before.inspection) === artifacts(report.after.inspection), 'Running artifacts/settings changed during measurement');
    report.serving = servingEvidence(requestCounts(report.measurementBaseline, manifest.dependencyInstanceId),
      requestCounts(report.after.inspection, manifest.dependencyInstanceId), manifest.applicationHostCount);
    const measured = report.phases.find(p => p.phase === 'measurement');
    report.metrics = await aws.metrics(manifest, measured.startedAt, measured.finishedAt);
    report.correctnessPassed = report.phases.every(p => p.reconciliation.passed);
    report.loadTargetsPassed = report.phases.every(p => p.exit.code === 0);
    report.status = report.correctnessPassed && report.loadTargetsPassed && report.serving.everyApplicationServed ? 'passed' : 'failed-or-incomplete';
  } catch (error) {
    report.status = 'incomplete'; report.failure = error.message;
  } finally {
    if (privateDirectory) {
      await unlink(join(privateDirectory, 'session.json')).catch(error => { if (error.code !== 'ENOENT') throw error; });
      await rmdir(privateDirectory);
    }
    report.finishedAt = new Date().toISOString(); report.client.freeMemoryAfterBytes = freemem();
    await save(join(output, 'run.json'), report);
  }
  console.log(JSON.stringify({ label: report.label, status: report.status, outputDirectory: output,
    applicationHosts: manifest.applicationHostCount, totalEc2Hosts: manifest.totalEc2HostCount }));
  if (report.status !== 'passed') process.exitCode = 1;
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
