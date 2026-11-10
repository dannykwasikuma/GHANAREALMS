# Deployment

## Option A: Cloudflare Workers (via OpenNext) - Git-integrated, non-interactive

See [CLOUDFLARE_FIX.md](./CLOUDFLARE_FIX.md) for the `pg-cloudflare` bundling
issue that was fixed to make this work, and exactly what was verified.

1. In the Cloudflare dashboard: Workers & Pages -> Create -> connect this
   GitHub repo (Git integration, not manual upload - satisfies requirement
   #10's "suitable for Cloudflare's Git-connected deployment environment").
2. Build command: `npm run cf:build` (runs `opennextjs-cloudflare build`)
3. Deploy command: leave Cloudflare's default (it picks up `.open-next/`
   and `wrangler.jsonc` automatically), or `npm run cf:deploy` if driving
   it from a CI script instead of Cloudflare's own Git integration.
4. Set every variable listed in CLOUDFLARE_FIX.md's "Cloudflare environment
   variables/secrets" section as an **encrypted secret** in the Workers
   project's Settings -> Variables - not a plain environment variable.
5. Database: this deployment still needs a real PostgreSQL instance
   reachable from Cloudflare's network. Options: a VPS-hosted Postgres
   with a public (firewalled to Cloudflare IPs) endpoint, or a managed
   Postgres provider. **Cloudflare Hyperdrive** is worth evaluating as a
   connection-pooling proxy in front of it, though this fix did not
   require Hyperdrive to work - straight `DATABASE_URL` already succeeded
   in local Workers-runtime testing (see CLOUDFLARE_FIX.md's verification
   table).
6. First deploy: watch Cloudflare's build log for the exact same
   `pg-cloudflare` error if you see it - if so, confirm `next.config.ts`
   and `package.json`'s `pg-cloudflare` dependency actually made it into
   the deployed commit.

## Option B: Traditional VPS (Node.js, not Cloudflare Workers)

## Architecture (per the brief's section 36)
```
Reverse Proxy (nginx/Caddy, terminates TLS)
     |
Store Frontend + Backend (this Next.js app, one process)
     |
PostgreSQL
```
The Minecraft server does NOT need to be on the same machine.

## Steps
1. Provision a VPS (any provider). Install Node.js 20+, PostgreSQL 16+.
2. Clone this repo, `cd web`, `npm install`, `npm run build`.
3. Create the database: `createdb ghanarealms_store`, then
   `psql $DATABASE_URL -f migrations/001_init.sql`.
4. Copy `.env.example` to `.env` (or set real environment variables),
   fill in every value - see comments in that file for what each does.
5. Create your first admin: `node scripts/create-admin.js <username> <password>`
   (password 12+ characters - there is no default admin, per section 38).
6. `npm run start` (or run it under systemd/pm2 for persistence).
7. Point your reverse proxy at the app's port (3000 by default) and
   terminate TLS there - this app does not implement TLS itself.
8. Set `STORE_BASE_URL` to your real public URL (used to build Paystack's
   callback_url server-side).
9. Configure Paystack's webhook URL - see PAYSTACK_SETUP.md.
10. If you also want the Minecraft bridge, set `MINECRAFT_BRIDGE_SECRET`
    here and the matching value in the plugin's config.yml - see
    MINECRAFT_BRIDGE.md.

## What has NOT been verified
This has only been run in a local sandbox against a local PostgreSQL
instance - see STATUS.md's NOT TESTED table. Treat first deployment as a
real test, the same way first server boot was for the Minecraft side.
