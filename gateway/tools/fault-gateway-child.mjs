// Runs the production gateway with observational socket/write counters only.
import { createGateway } from '../dist/src/server.js';
let gateway, timer;
const sockets = new Set();
const counters = { acceptedSockets: 0, peakSockets: 0, writeFalse: 0, maxWritableBytes: 0, writes: 0 };
process.once('message', options => {
  gateway = createGateway(options);
  gateway.server.on('connection', socket => {
    sockets.add(socket); counters.acceptedSockets++; counters.peakSockets = Math.max(counters.peakSockets, sockets.size);
    socket.on('close', () => sockets.delete(socket));
  });
  gateway.server.prependListener('request', (_request, response) => {
    const original = response.write;
    response.write = function (...args) {
      const result = original.apply(this, args);
      counters.writes++; if (!result) counters.writeFalse++;
      counters.maxWritableBytes = Math.max(counters.maxWritableBytes, this.writableLength);
      return result;
    };
  });
  gateway.server.listen(options.port, '127.0.0.1', () => process.send({ ready: true, pid: process.pid }));
  timer = setInterval(() => process.send?.({ observation: { ...counters, sockets: sockets.size,
    rssBytes: process.memoryUsage().rss, cpu: process.cpuUsage(), metrics: { ...gateway.metrics } } }), 100);
});
process.on('message', async message => {
  if (message === 'stop' && gateway) { clearInterval(timer); await gateway.close(); process.disconnect(); }
});
