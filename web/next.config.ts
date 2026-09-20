import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // pg (and its optional pg-cloudflare transport) must NOT be statically
  // bundled by Next's/OpenNext's build step. pg-cloudflare's package.json
  // exports a "workerd"-conditional path that's only reachable correctly
  // at real Cloudflare Workers runtime resolution - esbuild trying to
  // inline it during the build fails with "Could not resolve pg-cloudflare"
  // because pg/lib/stream.js reaches it via a dynamic require() that static
  // bundling can't safely trace. Marking both as external here keeps them
  // as real node_modules requires, resolved correctly by workerd itself
  // at runtime - which is what actually fixes this, not removing pg.
  serverExternalPackages: ["pg", "pg-cloudflare"],
};

export default nextConfig;
