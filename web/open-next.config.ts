// open-next.config.ts
//
// GhanaRealms Store — OpenNext Cloudflare adapter config.
//
// A previous version of this file set an `edgeExternals` key here to try to
// exclude `pg-cloudflare` from the server bundle. That key was a guess on my
// part, never verified against @opennextjs/cloudflare's actual config
// schema (no internet access in the environment that wrote it) — and a real
// Cloudflare build log came back showing it had no effect at all: identical
// "Could not resolve pg-cloudflare" error, same file, same line. I'm not
// confident enough in the exact current config surface for this package to
// guess a corrected key here a second time.
//
// The actual fix now lives in next.config.ts's `serverExternalPackages`
// instead — that's stable, documented, framework-level Next.js
// configuration (not something specific to this package's own API that I'd
// have to guess at), and OpenNext for Cloudflare is built to read and
// respect it during its own bundling pass. See next.config.ts for the full
// explanation.
//
// This file is left at the documented minimal default. If this specific
// package version needs additional Cloudflare-specific tuning beyond what
// next.config.ts covers, that should come from your own installed copy's
// README/type definitions (`node_modules/@opennextjs/cloudflare`) rather
// than a further guess from me — I don't have it installed to check.

import { defineCloudflareConfig } from "@opennextjs/cloudflare";

export default defineCloudflareConfig();
