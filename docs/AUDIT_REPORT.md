# GhanaRealms Audit Report
Scope: static audit of `web/` and `minecraft/` in this repo. Nothing could be compiled or type-checked in the audit sandbox (no network for npm/Maven); only SQL was executed (Postgres 16).

## 1. Critical
- **Webhook idempotency (FIXED)**: the event row was committed before processing, so a failed attempt made every Paystack retry look like a duplicate. Now an event is skipped only when `processed = true`.
- **Concurrent delivery pollers (FIXED)**: `/api/bridge/pending-deliveries` returned the same rows to every poller. Now rows are claimed with `FOR UPDATE SKIP LOCKED`, set to `PROCESSING` with a 5-minute lease (migration 012).
- **Checkout/username mismatch (FIXED earlier)**: checkout now uses the signed-in, verified Minecraft account only.
## 2. High
- Upstream seed 010 sold powerful items for cash (pay-to-win). Fixed by migration 011.
- `/store` command clashed with UltimateDonutSMP; Link plugin command is now `/myaccount`. NOTE: the master prompt's flow names `/store` for claiming; this is a deliberate deviation, documented.
- **Hubtel: not implemented.** No Hubtel code exists in the repo. No credentials or official API details were available, so none was invented.
- No automated email delivery found for verification/reset: unverified (check `lib/player-auth.ts` and provider config).
- Paystack plugin delivery of `delivery_commands` as console commands is assumed, not verified.
## 3. Medium
- No rate limiting confirmed on bridge or login endpoints beyond what upstream added (unverified).
- Duplicate migration numbers (two 008, two 009): order is fixed and documented in `DATABASE_MIGRATIONS.md`.
- Existing Java plugins (Quests, Casino, Ascension, Parkour, SMP-Core, UltimateDonutSMP) were NOT audited in depth in this pass; UltimateDonutSMP is decompiled output.
- World folders were not found in the repo; not verified.
## 4. Improvements
Admin refund screen, sales page, order history on `/account`, scheduled auto-settle of clan votes, image upload.
