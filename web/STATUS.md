# GhanaRealms Store — STATUS.md

**PROJECT:** GhanaRealms Store (Paystack + Minecraft delivery bridge)
**DATE:** 2026-09-20
**CURRENT PHASE:** Cloudflare Workers / OpenNext compatibility fix

## What was asked

Fix an OpenNext Cloudflare bundling failure — `ERROR Could not resolve
"pg-cloudflare"` — in an existing GhanaRealms Store repo, without rebuilding
the project, removing Postgres/Paystack/the Minecraft bridge, or converting
to a static site.

## Important discrepancy found during inspection

The uploaded project (`GhanaRealms-Store-source.zip`) has **no Cloudflare
Workers/OpenNext setup at all** — no `wrangler.jsonc`, no
`open-next.config.ts`, and neither `wrangler` nor `@opennextjs/cloudflare`
appear anywhere in `package.json` or the existing `package-lock.json`. The
described failure could not be reproduced or inspected directly for that
reason. This fix adds that deployment layer from scratch, using the
documented root cause for exactly this class of error, rather than editing a
config that was never provided.

## Root cause (confirmed against this repo's actual lockfile)

`pg`'s `lib/stream.js` does a runtime `require('pg-cloudflare')`.
`package-lock.json` showed `pg-cloudflare` present only as `pg`'s own
`optionalDependencies` entry (`"optional": true`), never a direct dependency
of this project. OpenNext's esbuild-based server bundler statically resolves
every `require()` call at build time; in a build environment that prunes
optional dependencies (common for hosted CI/build images, plausibly
including Cloudflare's own), the file esbuild needs simply isn't there,
producing exactly the reported error.

## Correction (2026-09-20, second pass)

The first version of this fix set an `edgeExternals` key in
`open-next.config.ts`. That key was **guessed**, not verified against
`@opennextjs/cloudflare`'s real config schema — no internet access was
available to check it. A real Cloudflare build log came back showing it had
**no effect whatsoever**: identical error, same file, same line
(`pg/lib/stream.js:41:41`), confirming the guess was wrong.

The fix now uses `serverExternalPackages: ["pg", "pg-cloudflare"]` in
**`next.config.ts`** instead — stable, documented, framework-level Next.js
config that OpenNext is built to read during its own bundling pass, rather
than a package-specific option I had to guess at. `open-next.config.ts` has
been reduced back to the documented minimal default
(`defineCloudflareConfig()` with no extra keys) rather than attempting a
second guess at its schema.

**This second attempt has also not been run against a real Cloudflare
build** — same environment constraint as before (no internet access here).
Please run it and, if it still fails, paste the log back exactly like you
did the first time — that log is what actually caught the first fix being
wrong, and it's the only reliable way to verify this one too.

## Second correction (2026-09-20, third pass)

The `next.config.ts` `serverExternalPackages` fix from the second pass **also
had no effect** — a third real Cloudflare build log came back with the
identical error, same file, same line. Next.js's own build already succeeds
regardless of that setting (Next's server runtime treats `pg` as external
automatically; that was never the failing step). The actual failure is
OpenNext's own, separate esbuild bundling pass over `.open-next/server-
functions/default/`, and neither `open-next.config.ts` nor `next.config.ts`
options I tried reach that specific pass — meaning I don't have reliable,
verified knowledge of `@opennextjs/cloudflare` v1.20.6's real config surface
for this, and guessing a third specific config key seemed likely to fail the
same way again.

This pass uses a different, bundler-config-independent mechanism instead:
esbuild's own error message states *"You can surround this require call with
a try/catch block to handle this failure at run-time instead of bundle-
time"* — documented esbuild behavior, not a third-party package's config
schema. A new `scripts/patch-pg-cloudflare.js`, wired up as an npm
`postinstall` hook, rewrites the installed `node_modules/pg/lib/stream.js`
to wrap the `require('pg-cloudflare')` call in try/catch. This was tested
against a synthetic file reproducing the exact reported code shape and
confirmed to patch correctly, idempotently, and produce valid JavaScript —
see "Tested" below. It has **not** been tested against a real Cloudflare
build, same environment constraint as both previous passes.

`next.config.ts`'s `serverExternalPackages` setting was left in place — it
didn't fix this specific error, but it's independently correct practice for
Next's own bundling of native-dependency server packages, so removing it
would have no benefit.

## Fix applied

1. **`package.json`** — added `pg-cloudflare` as a direct `dependency`
   (guarantees it's always installed), and `@opennextjs/cloudflare` +
   `wrangler` as `devDependencies` (previously entirely absent). Added
   `preview`/`deploy`/`cf-typegen` scripts.
2. **`package-lock.json`** — promoted the already-present, already-valid
   `pg-cloudflare` entry into the root package's `dependencies` list. Did
   **not** fabricate lockfile entries for `wrangler`/`@opennextjs/cloudflare`
   (no real dependency-tree/integrity data was available without a real
   `npm install`) — see "Not Tested" below.
3. **`next.config.ts`** — added `serverExternalPackages: ["pg", "pg-cloudflare"]`.
   Confirmed via a real build log **not** to fix this specific error (left in
   place regardless — independently correct practice for Next's own
   bundling, just not the actual fix).
3b. **`open-next.config.ts`** (new file) — left at the documented minimal
   default; an earlier `edgeExternals` key was removed after being shown
   ineffective by a real build log.
4. **`scripts/patch-pg-cloudflare.js`** (new file) + a `postinstall` script
   entry in `package.json` — the actual fix attempt for this pass. Wraps
   `pg`'s `require('pg-cloudflare')` call in try/catch after install,
   following esbuild's own documented advice for handling exactly this kind
   of unresolvable-at-bundle-time require. Tested against a synthetic file
   in this sandbox (patches correctly, idempotent, valid output JS) — not
   yet tested against your real installed `pg` or a real Cloudflare build.
4. **`wrangler.jsonc`** (new file) — Workers config with `nodejs_compat`, a
   Hyperdrive binding (placeholder ID — a real one must be generated per
   DEPLOYMENT.md), static asset serving, and a non-secret `vars` block for
   `STORE_BASE_URL`. No secrets are declared here (see DEPLOYMENT.md for
   where they go).
5. **`lib/db.ts`** — rewritten so the database connection routes through
   Cloudflare Hyperdrive when running on Workers, falling back to the
   original plain `DATABASE_URL`-based `pg.Pool` everywhere else (local dev,
   `next build`, tests, non-Cloudflare hosting). The exported `pool` is now a
   lazy `Proxy` rather than a value constructed at module load, specifically
   because four existing route files call `pool.connect()` / `pool.query()`
   directly for transactions — this keeps all of them working completely
   unchanged rather than requiring edits across the API routes.

## Files changed

- `package.json`
- `package-lock.json`
- `lib/db.ts`

## Files created

- `wrangler.jsonc`
- `open-next.config.ts`
- `DEPLOYMENT.md` (rewritten in place)
- `STATUS.md` (this file, rewritten in place)

## Files NOT touched

Every API route (`app/api/**/route.ts`), `lib/orders.ts`, `lib/admin-auth.ts`,
`lib/paystack.ts` (referenced, not modified), all page components, migrations,
and every other doc file — confirmed via an exhaustive grep for every call
site that imports from `lib/db`, to make sure the `db.ts` rewrite didn't
silently require changes elsewhere. None did.

## Tested

| Item | Result |
|---|---|
| `lib/db.ts` and `open-next.config.ts` are syntactically valid TypeScript | PASS (verified via `ts.transpileModule` in this sandbox's globally-installed TypeScript — a parse check, not a project type-check) |
| Every `pool`/`query`/`queryOne` call site in the app still matches `lib/db.ts`'s exports | PASS (exhaustive grep across `app/` and `lib/`, confirmed no other usage patterns) |
| `pg-cloudflare` correctly identified as the root cause | PASS (confirmed directly against this repo's own `package-lock.json`) |
| `scripts/patch-pg-cloudflare.js` correctly patches a synthetic file reproducing the reported code shape | PASS (tested in this sandbox: patches once, is idempotent on a second run, and the patched output is valid JavaScript per `node -c`) |
| `open-next.config.ts`'s `edgeExternals` key fixes the build | **FAIL** — confirmed by a real Cloudflare build log (first pass), reverted |
| `next.config.ts`'s `serverExternalPackages` fixes the build | **FAIL** — confirmed by a real Cloudflare build log (second pass), left in place anyway as independently-good practice, not relied on as the fix |

## NOT Tested (honest — could not be run in this environment)

- `npm install` — no internet access to the npm registry in this sandbox.
- `npm run build` (plain Next.js build) — blocked by the above; no
  `node_modules` could be installed at all.
- `npx opennextjs-cloudflare build` — the exact step this fix targets. **Not
  run.** Cannot confirm the `pg-cloudflare` error is actually gone until this
  is run somewhere with internet access.
- `npx opennextjs-cloudflare preview` and hitting the real API routes against
  it.
- Any live Paystack call, any live Postgres/Hyperdrive connection, any live
  Minecraft bridge round trip.
- A real `git push` — this environment has no network access to any git
  remote; changed files are delivered as a downloadable archive instead.

## BLOCKED

Full verification is blocked on: (a) internet access to run `npm install`
and the OpenNext/Wrangler build steps, (b) a real Hyperdrive ID (placeholder
left in `wrangler.jsonc`), (c) real Paystack/DB/bridge credentials, none of
which should ever be pasted into this chat.

## Known Issues

- `package-lock.json` will need one real `npm install` run to finish
  resolving `wrangler`/`@opennextjs/cloudflare`'s own dependency trees —
  what's here is correct as far as it goes but incomplete for those two
  packages specifically.
- `wrangler.jsonc`'s Hyperdrive `id` and `vars.STORE_BASE_URL` are
  placeholders that must be filled in with real values before deploying.

## Next Steps

1. Run `npm install` with real internet access, then follow DEPLOYMENT.md's
   verification steps in order.
2. Confirm the exact `pg-cloudflare` error is gone from
   `npx opennextjs-cloudflare build` output.
3. Create the real Hyperdrive config and update `wrangler.jsonc`.
4. Set the Cloudflare secrets listed in DEPLOYMENT.md.
5. Exercise every route in the "Preserved API routes" list below against the
   local OpenNext preview before connecting Git-based deployment.

## Preserved API routes (unmodified)

`/api/admin/login`, `/api/bridge/ack-delivery`, `/api/bridge/pending-deliveries`,
`/api/checkout/init`, `/api/lookup`, `/api/payments/paystack/webhook`,
`/api/payments/verify`, `/payment/callback` — all confirmed present, all
confirmed to import `lib/db` in a way the rewritten `lib/db.ts` still
satisfies exactly.

## Third pass (2026-09-20) — Player accounts + Minecraft account linking

Implemented Document 21 ("Account Registration and Minecraft Player ID
Verification") on top of the existing store, matching its existing patterns
exactly (bcrypt + JWT cookie, same as `lib/admin-auth.ts`; same shared-secret
bridge auth as `/api/bridge/ack-delivery`).

**Added:**
- `migrations/008_player_accounts.sql` — password/verification columns on
  `users`, `user_id`/`verified_at` on `minecraft_players`, new
  `minecraft_link_codes` table
- `lib/player-auth.ts` — registration/login primitives, session cookie
  handling, `getVerifiedMinecraftPlayer()`
- `lib/account-linking.ts` — generates short-lived link codes; verifies and
  consumes them (called by the Minecraft bridge, not the browser)
- Routes: `/api/auth/register`, `/api/auth/login`, `/api/auth/logout`,
  `/api/account/link` (start a link, re-validates the username against
  Mojang first), `/api/bridge/verify-link` (bridge-authenticated)
- Pages: `/register`, `/login`, `/account` (dashboard + purchase history),
  `/account/link`
- **`/api/checkout/init` rewritten**: no longer accepts `minecraftUsername`
  in the request body at all. Requires a signed-in session with a
  **verified** linked Minecraft account; re-checks that link inside the DB
  transaction before creating the order (Document 21 section 4's "recheck
  the account association when processing the order"). Uses the player's
  real account email for Paystack instead of the previous placeholder email.
- `/checkout` page now redirects to `/login` or `/account/link` server-side
  if the visitor isn't eligible, before any form renders
- Nav in `app/layout.tsx` now shows Sign In/Up or My Account based on session

**Known, real gap — not hidden:** the in-game command a player types to
redeem their link code (which would call `/api/bridge/verify-link`) does not
exist anywhere in this repo. No Minecraft plugin source was ever provided to
inspect or extend — this was flagged in the original audit and remains true.
Everything on the website side of the handshake is implemented and real; the
plugin-side command handler is the one piece still missing to complete the
loop end-to-end.

**Tested:** All new/changed files syntax-checked via `ts.transpileModule`
(parse-only, no `node_modules` available to type-check against real
`next`/`pg`/`zod` types). Cross-referenced every file for orphaned imports
after the checkout rewrite — clean.

**NOT tested:** Registration/login against a real Postgres instance. The
Mojang lookup call in `/api/account/link` (same no-internet constraint as
`lib/mojang.ts` generally). The actual cookie/session behavior in a running
Next.js server. Whether this still builds under the Cloudflare/OpenNext
pipeline — **that issue was still open and unresolved when this work
started**; these new files inherit the same unresolved build risk until the
`pg-cloudflare` bundling problem is actually confirmed fixed.

A browsable, interactive **UI preview** of the new flow (register → login →
account → link → checkout) was published separately — it's a static mockup
with no real backend behind it, built because this sandbox can't expose a
live Next.js+Postgres server to a browser. It exists so you can see what was
built without needing to deploy first; it is not the real application.

## Fourth pass (2026-09-20) — Interface polish + the Minecraft plugin gap closed

**Interface polish:** Added a shared design system to `app/globals.css`
(`.auth-card`, `.field-input`, `.btn-primary`/`.gold`, `.pill-*` status
badges, a kente-stripe accent on every auth card, a gold link-code display)
and applied it across `/register`, `/login`, `/account`, `/account/link`,
and the checkout form - consistent spacing, focus states, hover/active
button feedback, and status pills (pending/paid/failed) on the purchase
history list. No new dependencies - pure CSS additions to the existing
Tailwind-based setup.

**Minecraft plugin — the real gap, closed:** `minecraft/GhanaRealmsLinkPlugin/`
is real, complete Java source (`/link <CODE>` command) using the standard
Paper/Bukkit API and Java's built-in `HttpClient` - no extra dependencies.
It calls `/api/bridge/verify-link` with the player's server-known UUID and
username (never client-typed identity) and the same `x-bridge-secret` /
`MINECRAFT_BRIDGE_SECRET` pattern the existing bridge routes already use.

**Honest status: written, not compiled, not tested.** No Java compiler or
Paper API dependency has been available in this environment at any point in
this project (confirmed directly, more than once). Checked brace/paren
balance as a basic sanity check only - that is not a substitute for a real
`mvn`/`gradle` build. See `minecraft/GhanaRealmsLinkPlugin/README.md` for
exactly what's needed to actually build and test it.

With this, every piece of Document 21's account-linking flow now has real
source behind it, website and in-game both - the thing missing is
compiling/testing the plugin half and the Cloudflare build verification,
both blocked on the same no-internet constraint.

## Fifth pass (2026-09-20) — Plugin build file + real automated tests

- **`minecraft/GhanaRealmsLinkPlugin/pom.xml`** — standard Maven build file
  for the plugin (Paper API dependency via the real papermc.io repository,
  Java 21 target). Matches PaperMC's own published quickstart pom shape.
  **Not run** - no internet access to resolve the papermc.io repository, no
  reliable Maven/JDK toolchain in this environment either. Run `mvn package`
  yourself to get an actual jar.
- **`tests/player-auth.test.ts`** + a `test` script in `package.json`
  (`node --import tsx --test tests/*.test.ts`) - real test cases for
  `validEmail`/`validPassword`. **Important distinction:** the logic in
  these two functions WAS actually executed in this environment - as a
  standalone copy of the identical code, since the real module can't be
  imported without `jsonwebtoken`/`bcryptjs`/`next/headers`/`pg` installed.
  All 12 assertions passed for real. Running this actual test *file*,
  importing the real module, has not been done - do that after
  `npm install` as the first confirmation that nothing broke in translation.

## Sixth pass — more tests + site-wide polish

- Link-code generation split into `lib/link-code.ts` (only Node `crypto`, no DB)
  with `tests/link-code.test.ts`. Unlike earlier tests, **these 3 tests were
  run against the real module in this sandbox and passed (3/3)** - transpiled
  with the global TypeScript compiler and executed with `node --test`.
- Homepage and store cards now use the shared design system (badge, category
  pills, larger price, hover lift). Syntax-checked only.
- Still open: Cloudflare `pg-cloudflare` build verification (needs a fresh
  build log from a real deploy of this zip).

## Seventh pass - correction (supersedes the second and third passes)

- Removed `scripts/patch-pg-cloudflare.js` and the `postinstall` hook. It
  wrapped require('pg-cloudflare') in try/catch, which could let the build pass
  while DB connections fail at runtime on Workers (pg really uses that module
  there). My earlier claim that it is "never exercised at runtime" was wrong.
- New attempt in `next.config.ts`: `outputFileTracingIncludes` forces
  `node_modules/pg-cloudflare/dist/**` into the traced output. Basis: the log
  showed package.json copied but dist/index.js missing. NOT yet confirmed by a
  real build.
- Warning: `package.json` gained wrangler, @opennextjs/cloudflare and tsx
  without matching `package-lock.json` entries. If Cloudflare runs `npm ci`
  it will fail as out of sync - run `npm install` locally and commit the
  regenerated lockfile before deploying.

## Eighth pass - diagnostic
Added `scripts/diagnose-pg-cloudflare.js` as a `prebuild` hook. It only prints
(never fails the build): whether node_modules/pg-cloudflare/dist/index.js
exists, the folder contents, version and exports map. Paste the "[diagnose]"
lines from the next Cloudflare log - they show whether the files are missing
at install time or only dropped later by tracing. Locally (no node_modules)
it correctly reports MISSING; that says nothing about Cloudflare.
