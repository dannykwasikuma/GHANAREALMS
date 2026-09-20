# GhanaRealms Store - STATUS

**Project:** GhanaRealms (web store + Minecraft server)
**Date:** 2026-09-20
**Current phase:** MVP backend + frontend built and locally tested against a real database. Not deployed. Not tested against real Paystack (sandbox network can't reach api.paystack.co or api.mojang.com).

## Existing systems discovered (audit, before implementation)
- Minecraft: Paper 26.2 (DataVersion 4903), Maven build
- Existing plugins: UltimateDonutSMP-GhanaRealms (economy/ranks/auction/bounty), SMP-Core-GhanaRealms (moderation, MIT), 7 original GhanaRealms plugins already built in prior sessions
- No prior web/store infrastructure of any kind existed
- No prior PostgreSQL database existed
- No prior domain/deployment config existed

## Files created
- `web/` - full Next.js 16 app (TypeScript, Tailwind, App Router): homepage, store, product pages, checkout, payment callback, order lookup, terms/privacy/refund-policy, admin login+dashboard, 7 API routes
- `web/migrations/001_init.sql` - full schema (11 tables: users, minecraft_players, products, orders, order_items, payments, payment_events, delivery_queue, delivery_attempts, admin_users, audit_logs)
- `web/migrations/002_seed_example_products.sql` - 3 example products for testing (NOT final pricing)
- `web/scripts/create-admin.js` - admin user creation
- `minecraft/GhanaRealmsPaystack/.../BridgeClient.java` - new polling bridge so the existing Minecraft plugin can ALSO deliver website purchases

## Files modified
- `minecraft/GhanaRealmsPaystack/.../GhanaRealmsPaystack.java` + `config.yml` - added optional bridge polling (BRIDGE.ENABLED, default false - opt-in, doesn't change existing /buy behavior unless turned on)

## Completed
- Full database schema, with real foreign keys, unique constraints (the actual idempotency mechanism), indexes
- Checkout flow: validates product, validates Minecraft username server-side, creates order/payment rows in a transaction, initializes Paystack transaction
- Webhook handler: signature verification (HMAC-SHA512, constant-time compare), independent server-to-server re-verification, amount/currency sanity check, delivery queue creation
- Admin auth: bcrypt password hashing, JWT session in httpOnly/secure/sameSite cookie
- Public order lookup with narrow field exposure
- Minecraft bridge: polling client + ack endpoint, offline-player-safe (leaves delivery PENDING until the player is next seen online)

## TESTED (actually run, not assumed)
| Item | Result |
|---|---|
| `npm run build` (full production build) | PASS |
| TypeScript compilation | PASS |
| Migration `001_init.sql` against live PostgreSQL 16 | PASS |
| Foreign key / CHECK / UNIQUE constraints (real inserts) | PASS |
| Duplicate `payment_events` insert rejected by DB constraint | PASS |
| Homepage, /store, /store/[slug] render against live DB | PASS |
| `/api/lookup` with nonexistent order | PASS (clean 404) |
| `/api/checkout/init` with real DB writes | PASS (order/payment rows created correctly) |
| `/api/checkout/init` when Paystack API unreachable | PASS (found and fixed a real crash bug - see Known Issues) |
| Webhook: invalid signature | PASS (401 rejected) |
| Webhook: valid signature, unknown reference | PASS (200, no crash) |
| Webhook: exact duplicate delivery (idempotency) | PASS (confirmed exactly 1 row after 2 identical webhooks) |
| Admin login: wrong password | PASS (401) |
| Admin login: correct password + session cookie | PASS |
| Admin dashboard: unauthenticated redirect | PASS |
| Admin dashboard: authenticated, renders real stats | PASS |

## NOT TESTED (honestly - could not be, from this environment)
| Item | Why |
|---|---|
| Real Paystack checkout (test or live keys) | api.paystack.co not reachable from the build sandbox |
| Real Paystack webhook delivery (Paystack calling us) | Same - no public URL from this sandbox either |
| Mojang username lookup | api.mojang.com not reachable from the build sandbox |
| Minecraft plugin build/compile (all 9 plugins) | No Maven Central access from this sandbox - same constraint as every prior session |
| Minecraft bridge polling against a live Paper server | No running Paper server anywhere in this process |
| Mobile responsive layout | No visual browser testing tool available here - built with Tailwind responsive classes but not eyeballed on a device |
| Refund flow | Not built - see Known Issues |
| Email notifications | Not built at all (section 29 says this shouldn't block core payment, and it doesn't - deliberately deferred) |

## BLOCKED
- Real Paystack test-mode verification - needs real test API keys and a deployed public URL, neither of which exist yet
- Deployment - needs a real VPS/hosting target, none specified

## Known issues
1. **BridgeClient double-delivery edge case**: if in-game delivery succeeds but the ack HTTP call to the web backend fails (network blip), the web backend still shows the delivery as PENDING and will hand it out again next poll - which could double-deposit in-game currency or re-run delivery commands. Disclosed in the code comment where this happens (`BridgeClient.java`). Not fixed yet - the fix is either an idempotency key on the Minecraft side or a "delivered, unconfirmed" intermediate state on the web side.
2. **Orphaned PENDING orders**: if Paystack's `/transaction/initialize` call fails after the order/payment rows are already committed, those rows stay PENDING forever (harmless, just clutter - no money was taken).
3. **No refund automation**: refund policy page describes the process as "contact support," there's no admin-triggered refund flow yet, despite `orders.status` supporting a `REFUNDED` value in the schema.
4. **Admin panel is dashboard-only**: no product CRUD, no order editing, no audit-log browsing yet (sections 25/26 not fully built).
5. **Mojang lookup honestly untested against the real API** - written correctly against documented behavior, but unverified.

## Paystack status
Not connected to a real Paystack account. Code is written against documented API behavior (initialize, verify, webhook signature scheme) but has zero real-world verification. **Do not treat this as working until you've personally run a real test-mode transaction through it.**

## Cloudflare Workers deployment status (new)
**Fixed and verified.** The `pg-cloudflare` bundling error reported is
resolved - root cause, fix, and full verification log in
[CLOUDFLARE_FIX.md](./CLOUDFLARE_FIX.md). Summary: `next build` passes
(20/20 static pages), `opennextjs-cloudflare build` passes (previously
failed with `Could not resolve "pg-cloudflare"`), and the real Workers
runtime (via `wrangler dev`/workerd) was confirmed to actually run a real
PostgreSQL query and render real data - not just that the build didn't
error. One **separate, narrower** issue was found during this
verification pass (not the reported bug): `/api/admin/login` hangs in
the Workers runtime specifically when a DB query and `bcrypt.compare()`
run in the same request, though neither library hangs in isolation. Not
fixed - flagged as a known issue in CLOUDFLARE_FIX.md rather than
silently left for someone to discover in production.

## Database status
Real, tested, running locally in the build environment against PostgreSQL 16.15. Schema is solid. Not deployed anywhere permanent.

## Store status
Functionally complete for the MVP flow (browse -> checkout -> pay -> deliver -> lookup). Missing: full admin CRUD, email, refund automation, mobile-device testing.

## Minecraft bridge status
Code complete, wired into the existing plugin as an opt-in addition. Not compiled (same Maven Central constraint as the rest of this project), not tested against a live server.

## Security status
- Secret key isolated to server-only files (`lib/paystack.ts`), never touches client bundles
- Webhook signature verified with constant-time comparison
- Admin passwords bcrypt-hashed (cost 12), sessions are signed JWTs in httpOnly cookies
- Bridge secret uses constant-time comparison
- SQL uses parameterized queries throughout (no string-concatenated SQL anywhere)
- NOT done: rate limiting, CSRF tokens (relying on sameSite=strict cookies + no cookie-based state-changing GETs, which is a reasonable but not complete substitute), security headers (CSP, HSTS, etc.), formal security review

## Deployment status
Not deployed. No domain configured. No VPS specified. `.env.example` documents every required variable.

## Next steps
1. Get real Paystack test-mode keys and a deployed public URL; run one real transaction end to end
2. Compile the Minecraft plugins on a real machine with Maven Central access; test the bridge against a live Paper server
3. Build product/order CRUD in the admin panel
4. Fix the double-delivery edge case noted above
5. Add rate limiting + security headers before any real launch
6. Get a real domain, or confirm the Cloudflare Tunnel approach is the permanent plan
