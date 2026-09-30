# Events

Versioned JSON, one row per event in the outbox table, published to the
broker by the relay.

| Field | Type | Note |
|---|---|---|
| `event_id` | UUID | unique; the reconciliation key |
| `type` | string | `bid.placed`, `auction.closed`, `notification.sent` |
| `version` | int | schema version of `payload` |
| `aggregate_id` | UUID | the auction |
| `occurred_at` | timestamp | from the service clock |
| `watermark` | bigint | monotonic per source; reconciliation happens at a common watermark |
| `payload` | object | per type and version |

Consumers are idempotent by `event_id`. Delivery is at-least-once; the sink
records the effect and the dedup mark in one transaction.
