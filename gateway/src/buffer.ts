export interface WritableSink {
  readonly writableLength: number;
  write(chunk: string): boolean;
  destroy(): unknown;
  on(event: 'drain', callback: () => void): unknown;
  off(event: 'drain', callback: () => void): unknown;
}

/** Bounds application backlog plus the writable's own byte buffer. */
export class ClientBuffer {
  private queue: { value: string; bytes: number }[] = [];
  private bytes = 0;
  private blocked = false;
  private closed = false;
  private draining = () => { this.blocked = false; this.flush(); };

  constructor(private sink: WritableSink, private maxEvents: number,
    private maxBytes: number, private onOverflow: () => void) {
    sink.on('drain', this.draining);
  }

  send(value: string): boolean {
    if (this.closed) return false;
    const bytes = Buffer.byteLength(value);
    if (this.queue.length >= this.maxEvents || bytes + this.bytes + this.sink.writableLength > this.maxBytes) {
      this.close(); this.onOverflow(); return false;
    }
    this.queue.push({ value, bytes }); this.bytes += bytes; this.flush();
    return !this.closed;
  }

  private flush() {
    while (!this.closed && !this.blocked && this.queue.length) {
      const next = this.queue.shift()!;
      this.bytes -= next.bytes;
      this.blocked = !this.sink.write(next.value);
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true; this.queue = []; this.bytes = 0;
    this.sink.off('drain', this.draining); this.sink.destroy();
  }

  get pendingEvents() { return this.queue.length; }
  get pendingBytes() { return this.bytes; }
  get isClosed() { return this.closed; }
}
