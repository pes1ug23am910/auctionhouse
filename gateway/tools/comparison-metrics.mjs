export function distribution(values) {
  if (!values.length || values.some(value => !Number.isFinite(value))) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const pick = fraction => sorted[Math.max(0, Math.ceil(fraction * sorted.length) - 1)];
  return { count: sorted.length, min: sorted[0], p50: pick(.5), p95: pick(.95), p99: pick(.99), max: sorted.at(-1) };
}

export class Observation {
  events = new Map();
  versions = new Map();
  diagnostics = { frames: 0, duplicates: 0, conflictingIdentities: 0, outOfOrder: 0,
    gaps: 0, snapshots: 0, gapFrames: 0, malformed: 0, earlyEof: 0 };
  constructor(path, client, auctionId, initialVersion) {
    this.path = path; this.client = client; this.auctionId = auctionId; this.position = initialVersion;
  }
  record(frame, arrivalMs) {
    if (frame.event === 'gap') { this.diagnostics.gapFrames++; return; }
    if (frame.event === 'snapshot') { this.diagnostics.snapshots++; return; }
    if (frame.event !== 'auction') return;
    this.diagnostics.frames++;
    let event;
    try { event = JSON.parse(frame.data); } catch { this.diagnostics.malformed++; return; }
    if (!event || typeof event.eventId !== 'string' || event.schemaVersion !== 1
      || event.aggregateId !== this.auctionId || !Number.isSafeInteger(event.aggregateVersion)
      || event.payload?.id !== this.auctionId || event.payload?.version !== event.aggregateVersion
      || frame.id !== 'v1:' + this.auctionId + ':' + event.aggregateVersion) {
      this.diagnostics.malformed++; return;
    }
    const known = this.events.get(event.eventId);
    if (known) {
      this.diagnostics.duplicates++;
      if (known.raw !== frame.data) this.diagnostics.conflictingIdentities++;
      return;
    }
    if (this.versions.has(event.aggregateVersion)) this.diagnostics.conflictingIdentities++;
    if (event.aggregateVersion <= this.position) this.diagnostics.outOfOrder++;
    else if (event.aggregateVersion !== this.position + 1) this.diagnostics.gaps++;
    this.position = Math.max(this.position, event.aggregateVersion);
    this.versions.set(event.aggregateVersion, event.eventId);
    this.events.set(event.eventId, { version: event.aggregateVersion, arrivalMs, raw: frame.data });
  }
  missing(expected) { return expected.filter(item => !this.versions.has(item.version)).map(item => item.version); }
  clean() {
    return ['duplicates', 'conflictingIdentities', 'outOfOrder', 'gaps', 'gapFrames', 'malformed', 'earlyEof']
      .every(key => this.diagnostics[key] === 0);
  }
}

export function summarize(observations, expected) {
  const reference = observations[0];
  const identityMismatches = [];
  for (const expectedEvent of expected) {
    const ids = new Set(observations.map(observer => observer.versions.get(expectedEvent.version)).filter(Boolean));
    if (ids.size > 1) identityMismatches.push(expectedEvent.version);
  }
  const complete = observations.length > 0 && expected.length > 0
    && observations.every(observer => observer.missing(expected).length === 0);
  const clean = complete && !identityMismatches.length && observations.every(observer => observer.clean());
  const clients = observations.map(observer => {
    const samples = expected.flatMap(item => {
      const eventId = observer.versions.get(item.version);
      const event = eventId && observer.events.get(eventId);
      return event ? [{ eventId, version: item.version, arrivalMs: event.arrivalMs,
        postStartToArrivalMs: event.arrivalMs - item.postStartedMs,
        postResponseToArrivalMs: event.arrivalMs - item.postFinishedMs }] : [];
    });
    return { path: observer.path, client: observer.client, diagnostics: observer.diagnostics,
      missingVersions: observer.missing(expected), samples,
      postStartToArrivalMs: clean ? distribution(samples.map(sample => sample.postStartToArrivalMs)) : null };
  });
  const paired = clients.filter(client => client.path === 'java').flatMap(direct => {
    const proxied = clients.find(client => client.path === 'gateway' && client.client === direct.client);
    if (!proxied) return [];
    const byId = new Map(proxied.samples.map(sample => [sample.eventId, sample]));
    return direct.samples.flatMap(sample => {
      const other = byId.get(sample.eventId);
      return other ? [{ client: direct.client, eventId: sample.eventId, version: sample.version,
        javaArrivalMs: sample.arrivalMs, gatewayArrivalMs: other.arrivalMs,
        gatewayMinusJavaMs: other.arrivalMs - sample.arrivalMs }] : [];
    });
  });
  return { complete, clean, identityMismatches, clients, pairedSamples: paired,
    pairedArrivalDifferenceMs: clean ? distribution(paired.map(sample => sample.gatewayMinusJavaMs)) : null,
    expectedEventIds: expected.map(item => reference?.versions.get(item.version) ?? null) };
}
