# Deployment Guide (Windows cmd)
Prereqs: Node LTS, Git, PostgreSQL client, Cloudflare + Paystack accounts.
1. `cd web` then `npm install`
2. Create a Postgres DB (Supabase works), apply migrations (see DATABASE_MIGRATIONS.md).
3. `npx wrangler login`; `npx wrangler hyperdrive create ghanarealms-store-db --connection-string="postgres://..."`; put the returned id in `wrangler.jsonc`.
4. Set secrets (ENVIRONMENT_VARIABLES.md) and `STORE_BASE_URL`.
5. `npm run build`, `npm run preview` (local check), `npm run deploy`.
6. Paystack dashboard: webhook URL = `<site>/api/payments/paystack/webhook`.
7. Create an admin user; run a small test purchase before launch.
Unverified: production build and Cloudflare pg-cloudflare fix (need your build log).
