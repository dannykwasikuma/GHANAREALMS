# GhanaRealms repo update - player accounts + shared account

Extract this zip at the REPO ROOT (it contains `web/` and `minecraft/` paths).
Merged around your clan/loot/backpack changes: `web/app/layout.tsx` keeps your
Clans link; your `page.tsx` and `store/page.tsx` were NOT touched.

## Run these migrations, in order, after your existing 003-007
- `web/migrations/008_player_accounts.sql`
- `web/migrations/009_claim_tokens.sql`

## Then
1. `PLAYER_JWT_SECRET` env var (see `web/.env.example`) - must differ from `ADMIN_JWT_SECRET`.
2. `cd web && npm install` and commit the regenerated `package-lock.json`
   (package.json gained wrangler, @opennextjs/cloudflare, tsx; I did not ship a lockfile).
3. Build `minecraft/GhanaRealmsLinkPlugin` (`mvn package`), drop the jar in plugins/, set
   `BRIDGE.STORE-BASE-URL` / `BRIDGE.BRIDGE-SECRET` (same as your other plugins).

## Behaviour changes to know about
- `/api/checkout/init` now needs a signed-in player with a verified linked Minecraft
  account; it no longer accepts a username from the browser.
- `/myaccount` in-game (renamed from `/store`, which clashed with UltimateDonutSMP's `/store`) creates/claims the website account (online-mode servers only; refused otherwise).
- `/link <CODE>` links a website-first account.

## Not verified
Nothing here has been built, run against Postgres, or compiled (no network in my
sandbox). Parse-checked all 57 TS files in the merged tree; all imports resolve.
