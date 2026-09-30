export interface Frame { event: string; data: string; id: string }
export interface Cursor { generation: string; auctionId: string; version: number }

export function cursor(value: string): Cursor | null {
  const parts = value.split(':');
  if (parts.length !== 3 || !/^v[1-9][0-9]*$/.test(parts[0]) || !parts[1]
    || !/^[0-9]+$/.test(parts[2])) return null;
  const version = Number(parts[2]);
  return Number.isSafeInteger(version) ? { generation: parts[0], auctionId: parts[1], version } : null;
}

export function encode(frame: Frame): string {
  const id = frame.id.replace(/[\r\n]/g, '');
  const event = frame.event.replace(/[\r\n]/g, '');
  return `${id ? 'id: ' + id + '\n' : ''}event: ${event}\n${frame.data.split('\n').map(line => 'data: ' + line).join('\n')}\n\n`;
}

/** Incremental UTF-8 decoder: frame boundaries need not match network chunks. */
export class Parser {
  private decoder = new TextDecoder('utf-8', { fatal: true });
  private buffer = '';
  private data: string[] = [];
  private event = '';
  private id = '';
  private frameBytes = 0;

  constructor(private maxFrameBytes = 262_144) {}

  feed(chunk: Uint8Array): Frame[] {
    this.buffer += this.decoder.decode(chunk, { stream: true });
    const frames: Frame[] = [];
    for (;;) {
      const index = this.buffer.search(/[\r\n]/);
      if (index < 0 || (this.buffer[index] === '\r' && index === this.buffer.length - 1)) break;
      const size = this.buffer[index] === '\r' && this.buffer[index + 1] === '\n' ? 2 : 1;
      const line = this.buffer.slice(0, index);
      this.buffer = this.buffer.slice(index + size);
      if (!line) {
        if (this.data.length) frames.push({ event: this.event || 'message', data: this.data.join('\n'), id: this.id });
        this.data = []; this.event = ''; this.frameBytes = 0;
        continue;
      }
      this.frameBytes += Buffer.byteLength(line) + size;
      if (this.frameBytes > this.maxFrameBytes) throw new Error('SSE_FRAME_LIMIT');
      if (line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'data') this.data.push(value);
      else if (field === 'event') this.event = value;
      else if (field === 'id' && !value.includes('\0')) this.id = value;
    }
    if (Buffer.byteLength(this.buffer) + this.frameBytes > this.maxFrameBytes) throw new Error('SSE_FRAME_LIMIT');
    return frames;
  }
}

export interface StoredEvent { frame: Frame; wire: string; cursor: Cursor; bytes: number }

export class EventRing {
  private entries: StoredEvent[] = [];
  private bytes = 0;
  head: Cursor;

  constructor(seed: string, readonly maxEvents: number, readonly maxBytes: number) {
    const parsed = cursor(seed);
    if (!parsed) throw new Error('INVALID_SEED_CURSOR');
    this.head = parsed;
  }

  get lastCursor() { return `${this.head.generation}:${this.head.auctionId}:${this.head.version}`; }

  reset(value: string) {
    const parsed = cursor(value);
    if (!parsed || parsed.auctionId !== this.head.auctionId) throw new Error('INVALID_SNAPSHOT_CURSOR');
    this.head = parsed; this.entries = []; this.bytes = 0;
  }

  accept(frame: Frame): { kind: 'accepted'; value: StoredEvent } | { kind: 'duplicate' } | { kind: 'gap' } {
    const position = cursor(frame.id);
    if (!position || position.auctionId !== this.head.auctionId || position.generation !== this.head.generation) return { kind: 'gap' };
    let data: Record<string, unknown>;
    try { data = JSON.parse(frame.data); } catch { return { kind: 'gap' }; }
    if (!data || typeof data !== 'object' || Array.isArray(data)) return { kind: 'gap' };
    const payload = data.payload as Record<string, unknown> | null;
    if (!payload || data.schemaVersion !== 1 || data.aggregateId !== position.auctionId
      || data.aggregateVersion !== position.version || payload.id !== position.auctionId
      || payload.version !== position.version || typeof data.eventId !== 'string') return { kind: 'gap' };
    if (position.version <= this.head.version) {
      const known = this.entries.find(entry => entry.cursor.version === position.version);
      return known && known.frame.data !== frame.data ? { kind: 'gap' } : { kind: 'duplicate' };
    }
    if (position.version !== this.head.version + 1) return { kind: 'gap' };
    const wire = encode(frame);
    const bytes = Buffer.byteLength(wire);
    if (bytes > this.maxBytes) return { kind: 'gap' };
    const value = { frame, wire, cursor: position, bytes };
    this.entries.push(value); this.bytes += bytes; this.head = position;
    while (this.entries.length > this.maxEvents || this.bytes > this.maxBytes) this.bytes -= this.entries.shift()!.bytes;
    return { kind: 'accepted', value };
  }

  replay(value: string, requiredVersion: number): StoredEvent[] | null {
    const from = cursor(value);
    if (!from || from.generation !== this.head.generation || from.auctionId !== this.head.auctionId
      || from.version > this.head.version || this.head.version < requiredVersion) return null;
    const events = this.entries.filter(entry => entry.cursor.version > from.version);
    if (from.version < this.head.version && (!events.length || events[0].cursor.version !== from.version + 1)) return null;
    return events;
  }
}
