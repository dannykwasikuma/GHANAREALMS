# Cloudflare Workers Deployment Fix: pg-cloudflare Resolution Error

## Root cause (confirmed by direct inspection, not assumed)

`pg-cloudflare`'s `package.json` declares a **conditional exports map**:
```json
"exports": {
  ".": {
    "workerd": { "import": "./esm/index.mjs", "require": "./dist/index.js" },
    "default": "./dist/empty.js"
  }
}
```
`pg/lib/stream.js` reaches this package through a **dynamic** `require('pg-cloudflare')` call, buried inside a function only invoked when Workers is detected at runtime - not a static top-level import.

OpenNext's build step bundles the server function with esbuild. esbuild tried to statically resolve that dynamic require at bundle time (correctly following the `workerd` condition to `./dist/index.js`), but that file had not been copied into OpenNext's trimmed server-function `node_modules` output - only `pg-cloudflare`'s `package.json` had been. This is a known category of issue: static bundlers/tracers commonly miss files only reachable through dynamic `require()` calls inside conditional logic.

**In short: this was never a "missing dependency" - `pg-cloudflare` was genuinely installed the whole time. It was a bundler resolution/tracing problem.**

## The fix

Two changes, both minimal and targeted:

1. **`next.config.ts`**: added `serverExternalPackages: ["pg", "pg-cloudflare"]`. This tells Next.js/OpenNext not to statically bundle these packages at all - they stay as real `require()` calls resolved against actual `node_modules` at runtime, which lets workerd's own module resolution correctly follow the `workerd` export condition to the real file.
2. **`package.json`**: added `pg-cloudflare` as an explicit direct dependency (previously only present as `pg`'s *optional* dependency) - per requirement #11, so it's never skipped by an install that omits optional dependencies.

No database code changed. No API route changed. No architecture changed.

## Verification actually performed (not claimed without running it)

| Step | Result |
|---|---|
| Clean `npm install` from scratch | PASS |
| `npm run build` (normal Next.js production build) | PASS - 20/20 static pages, all API routes compile |
| `npx opennextjs-cloudflare build` | **PASS - the exact `pg-cloudflare` error is gone.** Output: `Worker saved in .open-next/worker.js` |
| Confirmed `pg-cloudflare/dist/index.js` actually present in the bundled output | PASS - verified by listing the file directly |
| Confirmed `pg` is NOT inlined into `worker.js` (stays external) | PASS - zero occurrences of bundled `require("pg")` source in worker.js |
| `wrangler dev` (real Workers runtime, workerd via miniflare) - homepage | PASS - real `200 OK` logged by wrangler itself |
| `wrangler dev` - `/store` page with real PostgreSQL query through workerd | **PASS - real product data (Adinkra Pack, Kente Rank, Golden Stool Rank) rendered**, proving `pg` genuinely opens a TCP connection to Postgres from inside the Workers runtime, not just that bundling succeeded |
| `wrangler dev` - `/api/lookup` (nonexistent order) | PASS - clean 404 |
| `wrangler dev` - webhook signature check (invalid signature) | PASS - 401 rejected, `crypto.timingSafeEqual` works in workerd |
| `wrangler dev` - `/api/admin/login` | **FAILED** - request hangs, Workers runtime kills it ("detected that your Worker's code had hung"). See Known Issue below. |

## Known issue found during verification (disclosed, not hidden, not fixed here)

The admin login route (`/api/admin/login`) hangs specifically when it runs in
the real Workers runtime. Isolated testing showed **neither `bcryptjs` nor
`jsonwebtoken` hangs on its own** - each was tested independently in a bare
route and both completed normally (bcrypt: real cost-12 hash computed in
422ms; jsonwebtoken: signed in 4ms). The hang only occurs in the actual
login route, which combines a Postgres query (via `pg`) with a subsequent
`bcrypt.compare()` call. This is a **separate, narrower issue** from the
one this fix addresses - not the `pg-cloudflare` bundling error, which is
conclusively fixed per the table above. I have not root-caused or fixed
this second issue: doing so would mean modifying files beyond what this
specific fix required, which the task explicitly asked me not to do.
Recommend investigating as a follow-up - likely candidates are `pg`'s
connection handling interacting with workerd's I/O scheduling when a
long CPU-bound synchronous-style operation (bcrypt) runs immediately
after an async DB call in the same request.

## Cloudflare environment variables/secrets required (names only, no values)

Set these as Cloudflare Workers **secrets** (`wrangler secret put <NAME>`,
or via the Cloudflare dashboard's Git-integration environment variables
with "Encrypt" checked) - never as plain vars, since these are exactly
the credentials that must never be exposed:

- `DATABASE_URL`
- `PAYSTACK_PUBLIC_KEY`
- `PAYSTACK_SECRET_KEY`
- `PAYSTACK_CALLBACK_URL`
- `STORE_BASE_URL`
- `MINECRAFT_BRIDGE_SECRET`
- `ADMIN_JWT_SECRET`

Same set as `.env.example` documents for a non-Cloudflare deployment -
nothing new was introduced by this fix.
