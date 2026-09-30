import test from 'node:test';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { Api, reconcile } from './api.mjs';
import { Aws } from './aws.mjs';
import { compare } from './compare.mjs';
import { canonical, historyChecks, parseOutcomes, requireOutsideDirectory, sha256, validateCredentials,
  validateInspection, validateManifest, validateOrigin, validateRelease } from './contracts.mjs';

const clone = value => structuredClone(value);
const uuid = digit => `${digit.repeat(8)}-${digit.repeat(4)}-${digit.repeat(4)}-${digit.repeat(4)}-${digit.repeat(12)}`;
const digest = digit => 'sha256:' + digit.repeat(64);
const now = Date.now();
const ids = ['i-' + '1'.repeat(17), 'i-' + '2'.repeat(17)];
const dep = 'i-' + '3'.repeat(17);
const release = { appDigest: digest('a'), gatewayDigest: digest('b'), warehouseDigest: digest('c') };
function manifest(count = 1) {
  const ownership = { project: 'auctionhouse', sessionId: 'fixture', accountId: '111111111111',
    region: 'us-east-1', expiresAt: new Date(now + 7200000).toISOString() };
  const prefix = 'arn:aws:elasticloadbalancing:us-east-1:111111111111:';
  const runtimeSettings = { javaHeap: '-Xms256m -Xmx512m', poolSize: 8, cache: 'memcached',
    session: 'postgresql-jdbc', idempotency: 'postgresql', broker: 'redpanda-single-node-fsync',
    telemetry: true, sse: 'java', sticky: false };
  return { schemaVersion: 1, ownership, origin: 'https://auction.example.com',
    applicationHostCount: count, applicationInstanceIds: ids.slice(0, count), dependencyInstanceId: dep,
    applicationInstanceType: 't3.small', dependencyInstanceType: 't3.large',
    supportingEc2HostCount: 1, totalEc2HostCount: count + 1,
    loadBalancer: { type: 'aws-alb', arn: prefix + 'loadbalancer/app/fixture/123',
      dnsName: 'fixture.us-east-1.elb.amazonaws.com', targetGroupArn: prefix + 'targetgroup/app/123',
      gatewayTargetGroupArn: prefix + 'targetgroup/gateway/123', sticky: false, appTargetPort: 8080,
      certificateArn: 'arn:aws:acm:us-east-1:111111111111:certificate/12345678-1234-1234-1234-123456789012' },
    database: { arn: 'arn:aws:rds:us-east-1:111111111111:db:fixture-full', host: 'fixture.db.amazonaws.com', instanceClass: 'db.t4g.micro' },
    stateIsolation: { module: 'infra/full', backendKey: 'auctionhouse/fixture/full/terraform.tfstate' },
    inspectionDocumentName: 'auctionhouse-fixture-full-inspect', runtimeSettings,
    runtimeConfigurationSha256: sha256(canonical(runtimeSettings)),
    ecrRepositoryUrls: Object.fromEntries(['app', 'gateway', 'warehouse'].map(kind => [kind, `111111111111.dkr.ecr.us-east-1.amazonaws.com/fixture-${kind}`])) };
}
function credentials(m) {
  return { origin: m.origin, ...Object.fromEntries(['seller', 'bidder', 'admin'].map((role, i) => [role,
    { actorId: uuid(String(i + 1)), accessToken: `fixture-only-session-${role}-0123456789`, expiresAt: new Date(now + 3600000).toISOString() }])) };
}
function appReport(m) {
  return { schemaVersion: 1, role: 'app', sessionId: m.ownership.sessionId, readiness: true,
    configuredRuntimeSha256: m.runtimeConfigurationSha256, configuredRuntimeSettings: m.runtimeSettings, release,
    containers: ['app', 'gateway'].map(kind => ({ name: 'auctionhouse-' + kind, present: true, running: true,
      demoCredentialsAbsent: true, health: 'not-configured', imageReference: `${m.ecrRepositoryUrls[kind]}@${release[kind + 'Digest']}`,
      imageId: digest(kind === 'app' ? 'd' : 'e'), memoryLimitBytes: kind === 'app' ? 1073741824 : 268435456,
      nanoCpus: kind === 'app' ? 1000000000 : 500000000, readOnlyRootFilesystem: true,
      runtimeEnvironment: kind === 'gateway' ? { HOST: '0.0.0.0', PORT: '3001', AUCTIONHOUSE_UPSTREAM: 'http://auctionhouse-app:8080' } : {
        AUCTIONHOUSE_DB_POOL_SIZE: '8', JAVA_TOOL_OPTIONS: '-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC',
        AUCTIONHOUSE_CACHE_BACKEND: 'memcached', AUCTIONHOUSE_CACHE_HOST: '10.74.1.10', AUCTIONHOUSE_BROKERS: '10.74.1.10:9092',
        AUCTIONHOUSE_AUTH_SECURE_COOKIES: 'true', AUCTIONHOUSE_OTEL_ENABLED: 'true', OTEL_EXPORTER_OTLP_ENDPOINT: 'http://10.74.1.10:4318',
        SERVER_SERVLET_SESSION_COOKIE_SECURE: 'true', SERVER_FORWARD_HEADERS_STRATEGY: 'native',
        SPRING_FLYWAY_ENABLED: 'false', SPRING_PROFILES_ACTIVE: 'broker',
        SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI: `${m.origin}/login/oauth2/code/keycloak` } })) };
}
function dependencyReport(m) {
  const limits = { redpanda: [1536, 1000000000, false], memcached: [128, 500000000, true],
    collector: [256, 1000000000, true], tempo: [768, 1000000000, true],
    prometheus: [256, 1000000000, true], grafana: [384, 1000000000, true] };
  return { schemaVersion: 1, role: 'dependency', sessionId: m.ownership.sessionId,
    configuredRuntimeSha256: m.runtimeConfigurationSha256, configuredRuntimeSettings: m.runtimeSettings, release,
    httpRequestsByInstance: { 'app-0': 20, 'app-1': 20 }, containers: ['redpanda', 'memcached', 'collector', 'tempo', 'prometheus', 'grafana']
      .map(name => ({ name, present: true, running: true, health: 'not-configured', imageReference: `${name}@${digest('f')}`,
        memoryLimitBytes: limits[name][0] * 1048576, nanoCpus: limits[name][1], readOnlyRootFilesystem: limits[name][2] })) };
}
function tags(m) { return Object.entries({ Project: 'auctionhouse', SessionId: m.ownership.sessionId,
  ExpiresAt: m.ownership.expiresAt, Topology: 'full' }).map(([Key, Value]) => ({ Key, Value })); }

function awsFixture(m, alter = () => {}) {
  const calls = [];
  const execute = async (executable, args, options) => {
    calls.push({ executable, args, options });
    const operation = args[0] + ':' + args[1];
    let data;
    switch (operation) {
      case 'sts:get-caller-identity': data = { Account: m.ownership.accountId }; break;
      case 'ec2:describe-instances': data = { Reservations: [{ Instances: [...m.applicationInstanceIds, dep].map(id => ({
        InstanceId: id, State: { Name: 'running' }, InstanceType: id === dep ? m.dependencyInstanceType : m.applicationInstanceType,
        PrivateIpAddress: id === dep ? '10.74.1.10' : '10.74.1.20', ImageId: 'ami-' + '1'.repeat(17),
        Placement: { AvailabilityZone: 'us-east-1a' }, Tags: tags(m) })) }] }; break;
      case 'elbv2:describe-load-balancers': data = { LoadBalancers: [{ LoadBalancerArn: m.loadBalancer.arn, Type: 'application',
        State: { Code: 'active' }, Scheme: 'internet-facing', DNSName: m.loadBalancer.dnsName }] }; break;
      case 'elbv2:describe-tags': data = { TagDescriptions: [m.loadBalancer.arn, m.loadBalancer.targetGroupArn].map(ResourceArn => ({ ResourceArn, Tags: tags(m) })) }; break;
      case 'elbv2:describe-target-groups': data = { TargetGroups: [{ TargetGroupArn: m.loadBalancer.targetGroupArn,
        TargetType: 'instance', Protocol: 'HTTP', Port: 8080, LoadBalancerArns: [m.loadBalancer.arn] }] }; break;
      case 'elbv2:describe-target-group-attributes': data = { Attributes: [{ Key: 'stickiness.enabled', Value: 'false' }] }; break;
      case 'elbv2:describe-target-health': data = { TargetHealthDescriptions: m.applicationInstanceIds.map(Id => ({
        Target: { Id, Port: 8080 }, TargetHealth: { State: 'healthy' } })) }; break;
      case 'elbv2:describe-listeners': data = { Listeners: [{ ListenerArn: 'listener-fixture', Port: 443, Protocol: 'HTTPS',
        Certificates: [{ CertificateArn: m.loadBalancer.certificateArn }], DefaultActions: [{ Type: 'fixed-response', FixedResponseConfig: { StatusCode: '403' } }] }] }; break;
      case 'elbv2:describe-rules': data = { Rules: [{ Priority: '100', Conditions: [{ Field: 'host-header', HostHeaderConfig: { Values: [new URL(m.origin).hostname] } }],
        Actions: [{ Type: 'forward', TargetGroupArn: m.loadBalancer.targetGroupArn }] }] }; break;
      case 'rds:describe-db-instances': data = { DBInstances: [{ DBInstanceArn: m.database.arn, DBInstanceStatus: 'available',
        PubliclyAccessible: false, StorageEncrypted: true, Engine: 'postgres', DBInstanceClass: m.database.instanceClass,
        Endpoint: { Address: m.database.host }, EngineVersion: '18.6', AllocatedStorage: 20, StorageType: 'gp3', MultiAZ: false }] }; break;
      case 'rds:list-tags-for-resource': data = { TagList: tags(m) }; break;
      case 'ssm:get-document': data = { DocumentVersion: '4', Content: JSON.stringify({ schemaVersion: '2.2',
        description: 'Fixture read-only inspection', mainSteps: [{ action: 'aws:runShellScript', name: 'inspect',
          inputs: { timeoutSeconds: '60', runCommand: ['python3 /opt/auctionhouse/full/inspect-runtime.py'] } }] }) }; break;
      case 'ssm:send-command': data = { Command: { CommandId: 'fixture-inspection-command' } }; break;
      case 'ssm:get-command-invocation': data = { Status: 'Success', ResponseCode: 0, StandardOutputContent: JSON.stringify(
        args[args.indexOf('--instance-id') + 1] === dep ? dependencyReport(m) : appReport(m)) }; break;
      default: throw new Error('Unexpected mock operation: ' + operation);
    }
    alter(operation, data);
    return { stdout: JSON.stringify(data) };
  };
  return { aws: new Aws('fixture', m.ownership.region, 'fixture-aws', execute), calls };
}

test('manifest validates exact public origin, account, host inventory and expiry boundaries', () => {
  const m = manifest(2); assert.equal(validateManifest(m, now), m);
  for (const origin of ['http://auction.example.com', 'https://auction.example.com/', 'https://user:secret@auction.example.com',
    'https://127.0.0.1', 'https://localhost', 'https://service.internal', 'https://auction.example.com:8443']) assert.throws(() => validateOrigin(origin));
  for (const change of [m => { m.applicationInstanceIds[1] = m.applicationInstanceIds[0]; },
    m => { m.ownership.accountId = 'bad'; }, m => { m.totalEc2HostCount = 2; },
    m => { m.ownership.expiresAt = new Date(now + 600000).toISOString(); },
    m => { m.runtimeSettings.poolSize++; }]) {
    const bad = clone(m); change(bad); assert.throws(() => validateManifest(bad, now));
  }
});
test('thin state key cannot be mistaken for full deployment isolation', () => {
  const bad = manifest(); bad.stateIsolation.backendKey = 'auctionhouse/fixture/terraform.tfstate';
  assert.throws(() => validateManifest(bad, now));
});
test('sessions require distinct seller/bidder and enough remaining access lifetime', () => {
  const m = manifest(), c = credentials(m); assert.equal(validateCredentials(c, m, 60, now), c);
  const same = clone(c); same.seller.actorId = same.bidder.actorId; assert.throws(() => validateCredentials(same, m, 60, now));
  const short = clone(c); short.admin.expiresAt = new Date(now + 1000).toISOString(); assert.throws(() => validateCredentials(short, m, 60, now));
  const foreign = clone(c); foreign.origin = 'https://elsewhere.example.com'; assert.throws(() => validateCredentials(foreign, m, 60, now));
  assert.throws(() => validateRelease({ ...release, appDigest: 'latest' }));
});
test('inspection cannot replace observed security and resource constraints with claimed settings hash', () => {
  const m = manifest(); assert.equal(validateInspection(appReport(m), m, release, 'app').readiness, true);
  for (const change of [r => { r.containers[0].runtimeEnvironment.AUCTIONHOUSE_DB_POOL_SIZE = '16'; },
    r => { r.containers[0].readOnlyRootFilesystem = false; }, r => { r.containers[0].memoryLimitBytes = 0; },
    r => { r.containers[1].runtimeEnvironment.AUCTIONHOUSE_UPSTREAM = 'http://foreign.example'; },
    r => { r.containers[0].runtimeEnvironment.SPRING_PROFILES_ACTIVE = ''; },
    r => { r.containers[0].runtimeEnvironment.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI = 'https://foreign.example/callback'; }]) {
    const bad = appReport(m); change(bad); assert.throws(() => validateInspection(bad, m, release, 'app'));
  }
});
test('AWS preflight uses only scoped read inspection commands and verifies true two-app inventory', async () => {
  const m = manifest(2); const { aws, calls } = awsFixture(m);
  const observed = await aws.preflight(m, release); assert.equal(observed.instances.length, 3);
  assert.ok(calls.every(c => c.args.includes('--profile') && c.args[c.args.indexOf('--profile') + 1] === 'fixture'));
  assert.ok(calls.every(c => c.args.includes('--region') && c.options.windowsHide === true));
  const sent = calls.find(c => c.args[0] === 'ssm' && c.args[1] === 'send-command');
  assert.equal(sent.args[sent.args.indexOf('--document-name') + 1], m.inspectionDocumentName);
  assert.equal(sent.args[sent.args.indexOf('--document-version') + 1], '4');
  assert.ok(!calls.some(c => ['start-instances', 'terminate-instances', 'put-parameter', 'register-targets'].includes(c.args[1])));
});
test('AWS preflight rejects wrong account and unhealthy application before load can start', async () => {
  const m = manifest();
  for (const alter of [(op, data) => { if (op === 'sts:get-caller-identity') data.Account = '222222222222'; },
    (op, data) => { if (op === 'elbv2:describe-target-health') data.TargetHealthDescriptions[0].TargetHealth.State = 'unhealthy'; }]) {
    const { aws } = awsFixture(m, alter); await assert.rejects(aws.preflight(m, release));
  }
});
test('AWS preflight rejects foreign instance descriptor even when count and tags match', async () => {
  const m = manifest(); const { aws } = awsFixture(m, (op, data) => {
    if (op === 'ec2:describe-instances') data.Reservations[0].Instances[0].InstanceId = 'i-' + '4'.repeat(17);
  });
  await assert.rejects(aws.preflight(m, release));
});
test('AWS preflight rejects a weighted rule that also forwards to an unexpected target', async () => {
  const m = manifest(); const { aws } = awsFixture(m, (op, data) => {
    if (op === 'elbv2:describe-rules') data.Rules[0].Actions = [{ Type: 'forward', ForwardConfig: { TargetGroups: [
      { TargetGroupArn: m.loadBalancer.targetGroupArn, Weight: 50 }, { TargetGroupArn: 'unexpected-group', Weight: 50 }] } }];
  });
  await assert.rejects(aws.preflight(m, release));
});
test('AWS execution errors cannot spill captured CLI credentials into evidence', async () => {
  const aws = new Aws('fixture', 'us-east-1', 'fixture', async () => { throw new Error('private-session-fixture'); });
  await assert.rejects(aws.call('sts', 'get-caller-identity'), error => !error.message.includes('private-session-fixture'));
});

test('API uses same-origin cookies, fresh CSRF and expected actor without following redirects', async () => {
  const m = manifest(), c = credentials(m).seller, calls = [];
  const api = new Api(m.origin, c, async (url, options) => {
    calls.push({ url, options });
    return url.endsWith('/csrf') ? new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf-fixture' }),
      { headers: { 'set-cookie': 'XSRF-TOKEN=csrf-cookie; Secure; Path=/' } }) : new Response(JSON.stringify({ ok: true }));
  });
  assert.deepEqual(await api.request('/api/auctions', { method: 'POST', body: { title: 'fixture' } }), { ok: true });
  assert.deepEqual(calls.map(c => c.url), [m.origin + '/api/auth/csrf', m.origin + '/api/auctions']);
  assert.equal(calls[1].options.headers['X-XSRF-TOKEN'], 'csrf-fixture');
  assert.equal(calls[1].options.headers['X-Expected-Actor'], c.actorId);
  assert.match(calls[1].options.headers.Cookie, /XSRF-TOKEN=csrf-cookie/);
  assert.equal(calls[1].options.redirect, 'manual');
  await assert.rejects(api.request('https://foreign.example/api/auctions'));
  await assert.rejects(api.request('//foreign.example/api/auctions'));
  assert.equal(calls.length, 2);
});
test('API rejects redirects and unknown session identity before any follow-up request', async () => {
  const m = manifest(), c = credentials(m).seller; let calls = 0;
  const redirect = new Api(m.origin, c, async () => { calls++; return new Response(null, { status: 302, headers: { Location: 'https://foreign.example/' } }); });
  await assert.rejects(redirect.request('/api/auth/session')); assert.equal(calls, 1);
  const other = new Api(m.origin, c, async () => new Response(JSON.stringify({ id: uuid('9'), role: 'ADMIN' })));
  await assert.rejects(other.identity());
});

function historyFixture() {
  const actor = uuid('2'), auction = uuid('4');
  const accepted = { actorId: actor, auctionId: auction, key: 'fixture-1', amountMinor: 100,
    accepted: true, bidId: uuid('5'), auctionVersion: 3, rejection: null, decidedAt: new Date(now).toISOString() };
  const rejected = { ...accepted, key: 'fixture-2', amountMinor: 100, accepted: false, bidId: null, rejection: 'BID_TOO_LOW' };
  return { actor, outcomes: [accepted, rejected], summary: { bids: 2, acceptedBids: 1, businessRejectedBids: 1,
    infrastructureErrorCount: 0, completed: 2, started: 2 }, history: [{ id: accepted.bidId, actorId: actor, amountMinor: 100, auctionVersion: 3 }],
    snapshot: { id: auction, status: 'OPEN', version: 3, highestBidAmountMinor: 100, highestBidderId: actor } };
}
test('durable-history oracle detects missing, duplicate, wrong-version and state-divergent accepted bids', () => {
  const f = historyFixture(); assert.ok(Object.values(historyChecks(f.snapshot, f.history, f.outcomes, f.summary, f.actor)).every(Boolean));
  for (const alter of [f => { f.history = []; }, f => { f.history.push(clone(f.history[0])); },
    f => { f.history[0].auctionVersion = 4; }, f => { f.snapshot.highestBidAmountMinor = 110; },
    f => { f.summary.completed = 1; }]) {
    const bad = clone(f); alter(bad); assert.ok(Object.values(historyChecks(bad.snapshot, bad.history, bad.outcomes, bad.summary, bad.actor)).some(value => !value));
  }
});
test('rejected outcomes must belong to the tested actor and auction too', () => {
  const f = historyFixture(); f.outcomes[1].actorId = uuid('9'); f.outcomes[1].auctionId = uuid('8');
  assert.ok(Object.values(historyChecks(f.snapshot, f.history, f.outcomes, f.summary, f.actor)).some(value => !value));
});
test('outcome log parser preserves business rejections and rejects duplicate logical identities', () => {
  const f = historyFixture(); const line = o => JSON.stringify({ level: 'info', msg: 'AUCTIONHOUSE_OUTCOME ' + JSON.stringify(o) });
  assert.deepEqual(parseOutcomes('noise\n' + f.outcomes.map(line).join('\n')), f.outcomes);
  assert.throws(() => parseOutcomes([f.outcomes[0], f.outcomes[0]].map(line).join('\n')));
  assert.throws(() => parseOutcomes(JSON.stringify({ msg: 'AUCTIONHOUSE_OUTCOME not-json' })));
});

function comparisonRun(count, ordinal) {
  const m = manifest(count); const start = Date.UTC(2026, 9, 1, 1, ordinal * 3);
  return { schemaVersion: 1, label: `run-${ordinal}`, startedAt: new Date(start).toISOString(), finishedAt: new Date(start + 120000).toISOString(),
    manifest: m, release, workload: { durationSeconds: 60, ratePerSecond: 20 }, workloadSha256: 'workload-fixture', sessionValidatorSha256: 'validator-fixture',
    identities: { seller: { id: uuid('1'), role: 'USER' }, bidder: { id: uuid('2'), role: 'USER' }, admin: { id: uuid('3'), role: 'ADMIN' } },
    client: { platform: 'fixture', release: 'fixture', cpu: 'fixture-cpu', logicalProcessors: 4, totalMemoryBytes: 1000, node: 'v24.15.0',
      freeMemoryBeforeBytes: 800, freeMemoryAfterBytes: 700 },
    before: { database: { arn: m.database.arn, engineVersion: '18.6' }, instances: [...m.applicationInstanceIds, dep].map(instanceId => ({ instanceId, imageId: 'ami-fixed' })),
      inspection: { results: [...m.applicationInstanceIds.map(instanceId => ({ instanceId, report: appReport(m) })), { instanceId: dep, report: dependencyReport(m) }] } },
    after: {}, serving: { everyApplicationServed: true }, phases: [{ phase: 'measurement', startedAt: new Date(start + 30000).toISOString(),
      exit: { code: 0 }, reconciliation: { passed: true }, summary: { actualOffered: 1200, started: 1200, completed: 1200, dropped: 0,
        infrastructureErrorCount: 0, retryAttempts: 0, acceptedBids: 200, businessRejectedBids: 100,
        latencyMilliseconds: { 'p(50)': 10, 'p(95)': 20, 'p(99)': 40 } } }] };
}
test('comparison refuses unmatched workloads, overlapping intervals and missing topology', () => {
  const one = comparisonRun(1, 0), two = comparisonRun(2, 1);
  const changed = clone(two); changed.workload.ratePerSecond++;
  assert.throws(() => compare([one, changed]));
  const overlapping = clone(two); overlapping.startedAt = one.startedAt;
  assert.throws(() => compare([one, overlapping]));
  assert.throws(() => compare([one, comparisonRun(1, 1)]));
});
test('two observations stay preliminary and dropped work cannot become a successful capacity claim', () => {
  const one = comparisonRun(1, 0), two = comparisonRun(2, 1);
  two.phases[0].summary.dropped = 30; two.phases[0].summary.actualOffered = 1230; two.phases[0].exit.code = 99;
  const result = compare([one, two]);
  assert.equal(result.balanced, false); assert.equal(result.allLoadTargetsPassed, false);
  assert.equal(result.rows[1].dropped, 30); assert.equal(result.rows[1].loadExit, 99);
  assert.notEqual(result.measurementQuality, 'repeated comparable observations');
});
test('three alternating pairs remain descriptive and expose a missing serving host', () => {
  const runs = Array.from({ length: 6 }, (_, index) => comparisonRun(index % 2 + 1, index));
  assert.equal(compare(runs).measurementQuality, 'repeated comparable observations');
  runs[5].serving.everyApplicationServed = false;
  assert.notEqual(compare(runs).measurementQuality, 'repeated comparable observations');
});


test('inspection rejects changed shell commands or parameterized documents before sending SSM', async () => {
  const m = manifest();
  for (const change of [d => { d.mainSteps[0].inputs.runCommand.push('unexpected mutation'); },
    d => { d.parameters = { command: { type: 'String' } }; }, d => { d.mainSteps[0].inputs.timeoutSeconds = '600'; }]) {
    const { aws, calls } = awsFixture(m, (operation, data) => {
      if (operation === 'ssm:get-document') { const d = JSON.parse(data.Content); change(d); data.Content = JSON.stringify(d); }
    });
    await assert.rejects(aws.preflight(m, release));
    assert.ok(!calls.some(c => c.args[0] === 'ssm' && c.args[1] === 'send-command'));
  }
});

function reconciliationFixture(alter = () => {}) {
  const f = historyFixture(), calls = [];
  const cutId = uuid('6');
  const source = [1, 2, 3].map(version => ({ eventId: uuid(String(version)), aggregateId: f.snapshot.id,
    aggregateVersion: version, type: ['AUCTION_CREATED', 'AUCTION_PUBLISHED', 'BID_ACCEPTED'][version - 1],
    payload: { immutable: 'fixture', version } }));
  const request = async (path, options = {}) => {
    calls.push({ path, options });
    let value;
    if (path === `/api/auctions/${f.snapshot.id}`) value = f.snapshot;
    else if (path.startsWith(`/api/auctions/${f.snapshot.id}/bids?`)) value = f.history;
    else if (path.includes('/bid-intents/')) value = f.outcomes.find(o => path.endsWith('/' + o.key));
    else if (path === '/api/admin/event-cuts') { assert.equal(options.method, 'POST'); value = { cutId }; }
    else if (path.endsWith('/events?limit=1000')) value = source;
    else if (path.endsWith('/notifications?limit=1000')) value = source;
    else if (path === `/api/admin/event-cuts/${cutId}`) value = { missing: [], unexpected: [] };
    else throw new Error('Unexpected fixture request: ' + path);
    const result = clone(value); alter(path, result); return result;
  };
  return { f, calls, bidder: { credential: { actorId: f.actor }, request }, admin: { request } };
}

test('reconciliation proves every accepted and rejected client outcome against durable replay and exact sink envelopes', async () => {
  const { f, bidder, admin, calls } = reconciliationFixture();
  const result = await reconcile(bidder, admin, f.snapshot.id, f.outcomes, f.summary);
  assert.equal(result.passed, true); assert.equal(result.durableOutcomeMatches, 2);
  assert.equal(result.source.length, 3); assert.deepEqual(result.notifications, result.source);
  assert.deepEqual(calls.filter(c => c.path.includes('/bid-intents/')).map(c => c.path.split('/').at(-1)), ['fixture-1', 'fixture-2']);
  assert.equal(calls.filter(c => c.options.method === 'POST').length, 1);
});

test('reconciliation stops before creating a source cut when a rejected outcome differs from durable replay', async () => {
  const { f, bidder, admin, calls } = reconciliationFixture((path, value) => {
    if (path.endsWith('/bid-intents/fixture-2')) value.rejection = 'AUCTION_CLOSED';
  });
  await assert.rejects(reconcile(bidder, admin, f.snapshot.id, f.outcomes, f.summary), /durable replay/);
  assert.ok(!calls.some(c => c.path === '/api/admin/event-cuts'));
});

test('sink payload mismatch and orphan effects cannot pass exact immutable source reconciliation', async () => {
  for (const alter of [(path, value) => { if (path.includes('/notifications?')) value[0].payload.immutable = 'corrupted'; },
    (path, value) => { if (!Array.isArray(value) && value.unexpected) value.unexpected.push(uuid('9')); }]) {
    const { f, bidder, admin } = reconciliationFixture(alter);
    const result = await reconcile(bidder, admin, f.snapshot.id, f.outcomes, f.summary);
    assert.equal(result.passed, false);
    assert.ok(!result.checks.exactSinkEffects || !result.checks.noOrphanSinkEffects);
  }
});

test('duplicate or nonmonotonic source and sink pagination cursors are rejected', async () => {
  for (const suffix of ['/events?limit=1000', '/notifications?limit=1000']) {
    for (const corrupt of [values => values.push(clone(values[0])), values => values.reverse()]) {
      const { f, bidder, admin } = reconciliationFixture((path, value) => { if (path.endsWith(suffix)) corrupt(value); });
      await assert.rejects(reconcile(bidder, admin, f.snapshot.id, f.outcomes, f.summary), /pagination cursor/);
    }
  }
});


test('observed dependency instance address must match actual application dependency endpoints', async () => {
  const m = manifest(); const { aws } = awsFixture(m, (operation, data) => {
    if (operation === 'ec2:describe-instances') data.Reservations[0].Instances.find(i => i.InstanceId === dep).PrivateIpAddress = '10.74.1.11';
  });
  await assert.rejects(aws.preflight(m, release), /dependency|runtime/);
});


test('private paths beginning with two dots remain inside the repository unless they are actual parent segments', () => {
  const root = resolve('fixture-source-root');
  for (const destination of [root, resolve(root, '..secret', 'session.json'), resolve(root, '.hidden', 'evidence')])
    assert.throws(() => requireOutsideDirectory(root, destination));
  assert.doesNotThrow(() => requireOutsideDirectory(root, resolve(root, '..', 'private', 'session.json')));
});

test('inspection release record and exact release key set cannot conceal mismatched artifacts', () => {
  const m = manifest();
  const bad = appReport(m); bad.release = { ...release, appDigest: digest('9') };
  assert.throws(() => validateInspection(bad, m, release, 'app'));
  assert.throws(() => validateRelease({ ...release, foreignDigest: digest('8') }));
});
