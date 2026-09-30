import { createGateway } from './server.js';

const port = Number(process.env.PORT ?? '3001');
const host = process.env.HOST ?? '127.0.0.1';
if (!['127.0.0.1', '0.0.0.0'].includes(host)) throw new Error('HOST must be loopback or the explicit container bind address');
const gateway = createGateway({
  upstream: process.env.AUCTIONHOUSE_UPSTREAM ?? 'http://127.0.0.1:8080',
});
gateway.server.listen(port, host, () => {
  process.stdout.write(`Auction SSE gateway listening on ${host}:${port}\n`);
});
let closing = false;
const stop = async () => {
  if (closing) return;
  closing = true;
  await gateway.close();
};
process.on('SIGINT', () => { void stop(); });
process.on('SIGTERM', () => { void stop(); });
