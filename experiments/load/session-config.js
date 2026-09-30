// Pure validation shared by the workload and its local contract tests.
export function validateSession(input, origin, durationSeconds, auctionId, now = Date.now()) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!input || input.origin !== origin || !uuid.test(input.actorId) || !uuid.test(auctionId))
    throw new Error('Session identity/origin/fixture is invalid');
  if (typeof input.accessToken !== 'string' || !/^[A-Za-z0-9._~-]{20,4096}$/.test(input.accessToken))
    throw new Error('Invalid application access token');
  if (!Number.isFinite(Date.parse(input.expiresAt)) || Date.parse(input.expiresAt) < now + (durationSeconds + 60) * 1000)
    throw new Error('Application access token expires before the bounded workload finishes');
  return input;
}
