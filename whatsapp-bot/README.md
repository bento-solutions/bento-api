# Bento WhatsApp bot

Multi-session Baileys bridge: each organization's `WaAccount` is one linked WhatsApp device
(session id = the account's UUID). The CRM backend drives it over HTTP on the internal Docker
network; the bot reports everything back through a signed webhook.

- **Inbound**: 1:1 messages (groups, broadcasts, status and newsletters are never decrypted),
  messages the owner types on the phone, receipts and session changes are written to a SQLite
  spool (`/data/bot.sqlite`) and delivered to `WEBHOOK_URL` until the CRM acknowledges them, so a
  backend deploy or outage loses nothing.
- **Outbound**: `POST /sessions/:id/messages {messageId, to, text}` sends under the CRM-assigned
  id. Repeating a messageId returns the stored result instead of sending twice. Pacing is the
  CRM's job; the bot only enforces a hard safety cap (`GUARD_PER_MINUTE`, `GUARD_PER_HOUR`).
- **Linking**: `POST /sessions/:id/start {phoneNumber}` requests a pairing code (WhatsApp >
  Linked devices > Link with phone number instead); it is reported in `session.status` events.

| Variable | Meaning |
|---|---|
| `API_KEY` | Bearer key the CRM uses (≥ 32 chars) |
| `WEBHOOK_URL` | e.g. `http://app:8080/api/v1/webhooks/baileys` |
| `WEBHOOK_SECRET` | HMAC key for `X-Bento-Signature` (≥ 32 chars) |
| `DATA_DIR` | `/data`: `sessions/<id>/` (Signal keys, mode 700) and `bot.sqlite` |

`/data/sessions/<id>/` holds long-lived private keys: it never leaves the volume and is never
committed. Tests: `npm test`.
