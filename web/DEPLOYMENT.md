# GhanaRealms Store — Cloudflare Deployment (OpenNext)

This document was rewritten as part of the Cloudflare/OpenNext compatibility
fix. It describes the setup actually present in this repo now: `wrangler.jsonc`
and `open-next.config.ts` were both added from scratch (neither existed
before), and `lib/db.ts` was updated to route through Cloudflare Hyperdrive.
**None of this has been run/verified in the environment that produced it** —
no internet access was available to install `wrangler`/`@opennextjs/cloudflare`
or execute a real build. Verify locally with the steps below before trusting
a Cloudflare deploy.

## What changed, in one paragraph

`pg`'s `lib/stream.js` does a runtime `require('pg-cloudflare')`, but that
package was only ever installed as `pg`'s own *optional* dependency — never a
direct dependency of this project — so OpenNext's esbuild bundler failed to
resolve it in a build environment that prunes optional dependencies (this is
common in CI/hosted build images). The fix: `pg-cloudflare` is now a direct
dependency (guarantees it's installed), `open-next.config.ts` marks it
`external` (so esbuild doesn't try to inline it), and the actual database
connection on Cloudflare now goes through **Hyperdrive** rather than a raw
`pg` TCP socket — which is Cloudflare's own documented, supported way to run
`pg` on Workers at all.

## One-time setup

```bash
# 1. Install dependencies (this will also finish resolving wrangler/
#    @opennextjs/cloudflare's own dependency trees into package-lock.json —
#    those two packages were added to package.json here, but this sandbox
#    had no internet access to run npm install and complete the lockfile)
npm install

# 2. Create a Hyperdrive config pointing at your real Postgres instance
npx wrangler hyperdrive create ghanarealms-store-db \
  --connection-string="postgresql://user:password@your-db-host:5432/ghanarealms_store"
# This prints an "id" — put it in wrangler.jsonc under hyperdrive[0].id,
# replacing REPLACE_WITH_REAL_HYPERDRIVE_ID.

# 3. Log in to Cloudflare (one-time, local only — NOT needed for Git-
#    integration deploys, which authenticate via Cloudflare's own dashboard
#    connection to your repo, not a local `wrangler login`)
npx wrangler login
```

## Required Cloudflare secrets (set via dashboard or `wrangler secret put`)

None of these belong in `wrangler.jsonc` (it's typically committed to git).
Set each with `npx wrangler secret put NAME`, or in the Cloudflare dashboard
under Workers & Pages → your worker → Settings → Variables and Secrets:

- `DATABASE_URL` — only needed if you ever run outside Hyperdrive (e.g. a
  migration script); the deployed Worker itself uses the Hyperdrive binding.
- `PAYSTACK_PUBLIC_KEY`
- `PAYSTACK_SECRET_KEY` — **server-side only, never in `vars`, never in the
  frontend**
- `PAYSTACK_CALLBACK_URL`
- `PAYSTACK_WEBHOOK_URL`
- `MINECRAFT_BRIDGE_SECRET`
- `ADMIN_JWT_SECRET`

`STORE_BASE_URL` is set in `wrangler.jsonc`'s `vars` block (not a secret) —
update the placeholder to your real domain before deploying.

## Local verification (do this before deploying)

```bash
npm install
npm run build                     # plain Next.js build — should still pass as before
npx opennextjs-cloudflare build   # the step that was previously failing
npx opennextjs-cloudflare preview # serves the Worker bundle locally via Miniflare
```

Confirm the second command completes without the
`ERROR Could not resolve "pg-cloudflare"` message. Then exercise the routes
listed in TROUBLESHOOTING.md against the local preview.

## Cloudflare Git-integration deployment (non-interactive)

In the Cloudflare dashboard, when connecting this repo:
- **Build command:** `npm run build && npx opennextjs-cloudflare build`
- **Deploy command:** `npx opennextjs-cloudflare deploy`
- **Build output:** left as the OpenNext default (`.open-next/`)

This does not require an interactive `wrangler login` during the build —
Cloudflare's Git integration authenticates the deploy itself once the repo is
connected in the dashboard; only step 2/3 above (Hyperdrive creation, secret
values) need a human to run once, ahead of time, from a machine with real
`wrangler` access.

## Known unverified items

- The exact `wrangler`/`@opennextjs/cloudflare` versions in `package.json`
  (`^4.42.1` / `^1.9.1`) were the latest known-compatible pairing at the time
  of writing, not confirmed against this specific project — check for newer
  compatible versions before deploying.
- `package-lock.json` has `pg-cloudflare` correctly promoted to a direct
  dependency (using its already-locked, valid entry), but does **not** yet
  contain full dependency-tree entries for `wrangler`/`@opennextjs/cloudflare`
  themselves — `npm install` needs to run once, with real internet access, to
  complete the lockfile. Fabricating those entries here would have meant
  guessing integrity hashes, which is worse than leaving them for a real
  install to fill in.
