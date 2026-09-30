import { createHash } from 'node:crypto';
import { isIP } from 'node:net';
import { relative, isAbsolute, sep } from 'node:path';
import { validateSession } from '../load/session-config.js';

export const K6_IMAGE = 'grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34';
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const instance = /^i-[0-9a-f]{17}$/;
export const canonical = value => JSON.stringify(value, (_, v) => v && !Array.isArray(v) && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
export const sha256 = value => createHash('sha256').update(value).digest('hex');
export function requireThat(condition, message) { if (!condition) throw new Error(message); }
export function requireOutsideDirectory(root, destination) {
  const path = relative(root, destination);
  requireThat(path === '..' || path.startsWith('..' + sep) || isAbsolute(path),
    'Credentials and evidence must be outside the source repository');
}
export function integer(value, minimum, maximum, name) {
  requireThat(Number.isInteger(value) && value >= minimum && value <= maximum, 'Invalid ' + name);
  return value;
}
export function validateOrigin(value) {
  const url = new URL(value);
  requireThat(url.protocol === 'https:' && url.origin === value && !url.username && !url.password
    && !url.port && !isIP(url.hostname) && url.hostname.includes('.')
    && !/(^|\.)(localhost|local|internal|test|invalid)$/.test(url.hostname), 'Use an exact public HTTPS origin');
  return value;
}
export function validateManifest(m, now = Date.now()) {
  requireThat(m?.schemaVersion === 1 && m.ownership?.project === 'auctionhouse', 'Invalid project manifest');
  const o = m.ownership;
  requireThat(/^[a-z][a-z0-9-]{2,20}$/.test(o.sessionId) && /^\d{12}$/.test(o.accountId)
    && /^[a-z]{2}-[a-z]+-\d+$/.test(o.region), 'Invalid account/session/region');
  requireThat(Number.isFinite(Date.parse(o.expiresAt)) && Date.parse(o.expiresAt) > now + 600000, 'Deployment expired or too near expiry');
  validateOrigin(m.origin);
  integer(m.applicationHostCount, 1, 2, 'applicationHostCount');
  requireThat(Array.isArray(m.applicationInstanceIds) && m.applicationInstanceIds.length === m.applicationHostCount
    && new Set(m.applicationInstanceIds).size === m.applicationHostCount
    && m.applicationInstanceIds.every(id => instance.test(id)), 'Incorrect application instance set');
  requireThat(instance.test(m.dependencyInstanceId) && !m.applicationInstanceIds.includes(m.dependencyInstanceId)
    && m.supportingEc2HostCount === 1 && m.totalEc2HostCount === m.applicationHostCount + 1, 'Incorrect supporting host count');
  const lb = m.loadBalancer;
  const prefix = `arn:aws:elasticloadbalancing:${o.region}:${o.accountId}:`;
  requireThat(lb?.type === 'aws-alb' && lb.arn?.startsWith(prefix + 'loadbalancer/app/')
    && lb.targetGroupArn?.startsWith(prefix + 'targetgroup/') && lb.sticky === false && lb.appTargetPort === 8080,
  'Invalid ALB configuration');
  requireThat(lb.certificateArn?.startsWith(`arn:aws:acm:${o.region}:${o.accountId}:certificate/`), 'Wrong certificate account or region');
  requireThat(m.database?.arn?.startsWith(`arn:aws:rds:${o.region}:${o.accountId}:db:`), 'Wrong database account or region');
  requireThat(m.stateIsolation?.module === 'infra/full'
    && /^[a-zA-Z0-9/_-]+\/full\/terraform\.tfstate$/.test(m.stateIsolation.backendKey), 'Explicit isolated full-state key required');
  requireThat(m.inspectionDocumentName === `auctionhouse-${o.sessionId}-full-inspect`, 'Unexpected inspection document');
  requireThat(sha256(canonical(m.runtimeSettings)) === m.runtimeConfigurationSha256, 'Runtime settings hash mismatch');
  requireThat(m.runtimeSettings.cache === 'memcached' && m.runtimeSettings.session === 'postgresql-jdbc'
    && m.runtimeSettings.idempotency === 'postgresql' && m.runtimeSettings.sticky === false
    && m.runtimeSettings.telemetry === true, 'Shared state/telemetry contract mismatch');
  for (const kind of ['app', 'gateway', 'warehouse']) requireThat(
    m.ecrRepositoryUrls?.[kind]?.startsWith(`${o.accountId}.dkr.ecr.${o.region}.amazonaws.com/`), 'Wrong ECR repository boundary');
  return m;
}
export function validateCredentials(credentials, m, seconds, now = Date.now()) {
  requireThat(credentials?.origin === m.origin, 'Credentials origin does not match deployment');
  for (const role of ['seller', 'bidder', 'admin']) {
    validateSession({ ...credentials[role], origin: credentials.origin }, m.origin, seconds + 240,
      '00000000-0000-0000-0000-000000000001', now);
  }
  requireThat(credentials.seller.actorId !== credentials.bidder.actorId, 'Seller and bidder must be separate actors');
  return credentials;
}
export function validateTags(tags, ownership) {
  const values = Object.fromEntries((tags || []).map(t => [t.Key, t.Value]));
  requireThat(values.Project === 'auctionhouse' && values.SessionId === ownership.sessionId
    && values.ExpiresAt === ownership.expiresAt && values.Topology === 'full', 'Resource ownership tags mismatch');
}
export function validateInspection(report, m, release, role, dependencyAddress) {
  requireThat(report?.schemaVersion === 1 && report.role === role && report.sessionId === m.ownership.sessionId
    && report.configuredRuntimeSha256 === m.runtimeConfigurationSha256
    && canonical(report.configuredRuntimeSettings) === canonical(m.runtimeSettings)
    && canonical(report.release) === canonical(release), 'Inspection configuration/release mismatch');
  const containers = report.containers || [];
  if (role === 'app') {
    requireThat(report.readiness === true, 'Application is not ready');
    for (const kind of ['app', 'gateway']) {
      const c = containers.find(c => c.name === `auctionhouse-${kind}`);
      requireThat(c?.present && c.running && c.demoCredentialsAbsent && c.health !== 'unhealthy'
        && c.imageReference === `${m.ecrRepositoryUrls[kind]}@${release[kind + 'Digest']}`
        && /^sha256:[a-f0-9]{64}$/.test(c.imageId) && c.readOnlyRootFilesystem === true
        && c.memoryLimitBytes === (kind === 'app' ? 1024 : 256) * 1024 * 1024
        && c.nanoCpus === (kind === 'app' ? 1000000000 : 500000000), 'Running image/health/resource limits differ from release');
    }
    const env = containers.find(c => c.name === 'auctionhouse-app').runtimeEnvironment;
    requireThat(env.AUCTIONHOUSE_DB_POOL_SIZE === String(m.runtimeSettings.poolSize)
      && env.JAVA_TOOL_OPTIONS === m.runtimeSettings.javaHeap + ' -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC'
      && env.AUCTIONHOUSE_CACHE_BACKEND === 'memcached'
      && env.AUCTIONHOUSE_AUTH_SECURE_COOKIES === 'true' && env.AUCTIONHOUSE_OTEL_ENABLED === 'true'
      && env.SERVER_SERVLET_SESSION_COOKIE_SECURE === 'true' && env.SERVER_FORWARD_HEADERS_STRATEGY === 'native'
      && env.SPRING_FLYWAY_ENABLED === 'false' && env.SPRING_PROFILES_ACTIVE === 'broker'
      && /^10\.74\.\d+\.\d+$/.test(env.AUCTIONHOUSE_CACHE_HOST)
      && (dependencyAddress === undefined || env.AUCTIONHOUSE_CACHE_HOST === dependencyAddress)
      && env.AUCTIONHOUSE_BROKERS === env.AUCTIONHOUSE_CACHE_HOST + ':9092'
      && env.OTEL_EXPORTER_OTLP_ENDPOINT === 'http://' + env.AUCTIONHOUSE_CACHE_HOST + ':4318'
      && env.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_REDIRECT_URI === m.origin + '/login/oauth2/code/keycloak',
    'Actual application runtime settings mismatch');
    const gateway = containers.find(c => c.name === 'auctionhouse-gateway').runtimeEnvironment;
    requireThat(gateway.HOST === '0.0.0.0' && gateway.PORT === '3001'
      && gateway.AUCTIONHOUSE_UPSTREAM === 'http://auctionhouse-app:8080', 'Actual gateway runtime settings mismatch');
  } else {
    const limits = { redpanda: [1536, 1000000000, false], memcached: [128, 500000000, true],
      collector: [256, 1000000000, true], tempo: [768, 1000000000, true],
      prometheus: [256, 1000000000, true], grafana: [384, 1000000000, true] };
    for (const name of ['redpanda', 'memcached', 'collector', 'tempo', 'prometheus', 'grafana']) {
      const c = containers.find(c => c.name === name);
      requireThat(c?.present && c.running && c.health !== 'unhealthy' && /@sha256:[a-f0-9]{64}$/.test(c.imageReference)
        && c.memoryLimitBytes === limits[name][0] * 1024 * 1024 && c.nanoCpus === limits[name][1]
        && c.readOnlyRootFilesystem === limits[name][2],
        'Dependency absent, unhealthy, or not pinned');
    }
  }
  return report;
}
export function validateRelease(release) {
  requireThat(canonical(Object.keys(release || {}).sort()) === canonical(['appDigest', 'gatewayDigest', 'warehouseDigest']),
    'Supply exactly the three release digest fields');
  for (const key of ['appDigest', 'gatewayDigest', 'warehouseDigest'])
    requireThat(/^sha256:[a-f0-9]{64}$/.test(release?.[key]), 'Supply all immutable release digests');
  return release;
}
export function parseOutcomes(raw) {
  const outcomes = [];
  for (const line of raw.split(/\r?\n/)) {
    let entry; try { entry = JSON.parse(line); } catch { continue; }
    if (typeof entry.msg === 'string' && entry.msg.startsWith('AUCTIONHOUSE_OUTCOME '))
      outcomes.push(JSON.parse(entry.msg.slice('AUCTIONHOUSE_OUTCOME '.length)));
  }
  requireThat(new Set(outcomes.map(o => o.key)).size === outcomes.length, 'Duplicate workload outcome identities');
  return outcomes;
}
export function historyChecks(snapshot, history, outcomes, summary, actorId) {
  const ordered = [...history].sort((a, b) => a.auctionVersion - b.auctionVersion);
  const accepted = outcomes.filter(o => o.accepted);
  const acceptedById = new Map(accepted.map(o => [o.bidId, o]));
  return {
    everyOutcomeIdentity: outcomes.every(o => o.actorId === actorId && o.auctionId === snapshot.id
      && typeof o.accepted === 'boolean' && Number.isSafeInteger(o.amountMinor) && o.amountMinor > 0
      && /^[A-Za-z0-9._:-]{1,128}$/.test(o.key)),
    completeClientOutcomes: outcomes.length === summary.bids
      && accepted.length === summary.acceptedBids && outcomes.length - accepted.length === summary.businessRejectedBids,
    exactAcceptedHistory: ordered.length === accepted.length && new Set(ordered.map(b => b.id)).size === ordered.length
      && ordered.every(b => { const o = acceptedById.get(b.id); return o && o.actorId === actorId
        && o.auctionId === snapshot.id && b.actorId === actorId && b.amountMinor === o.amountMinor && b.auctionVersion === o.auctionVersion; }),
    versionAndPriceProgression: ordered.every((b, i) => b.auctionVersion === i + 3
      && b.amountMinor >= (i === 0 ? 100 : ordered[i - 1].amountMinor + 10)),
    stateMatchesHistory: snapshot.status === 'OPEN' && snapshot.version === ordered.length + 2
      && snapshot.highestBidAmountMinor === (ordered.at(-1)?.amountMinor ?? null)
      && snapshot.highestBidderId === (ordered.at(-1)?.actorId ?? null),
    noUnreconciledClientErrors: summary.infrastructureErrorCount === 0 && summary.completed === summary.started,
  };
}
