import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // pg is auto-externalized by Next, so it gets copied into the traced
  // output (.open-next/server-functions/default/node_modules/pg). Its
  // lib/stream.js does require('pg-cloudflare'), and on Cloudflare Workers
  // pg really uses that module at runtime for sockets (Hyperdrive included),
  // so it must be present - not stubbed out.
  //
  // Real build logs showed pg-cloudflare/package.json copied into the output
  // but its dist/index.js missing, i.e. file tracing skipped the dist files.
  // outputFileTracingIncludes forces them into the traced output.
  //
  // UNVERIFIED: this is the third approach. Two earlier guesses (an
  // edgeExternals key; a postinstall try/catch patch) were wrong or unsafe.
  // This one is reasoned from the log, not yet confirmed by a build.
  serverExternalPackages: ["pg", "pg-cloudflare"],
  outputFileTracingIncludes: {
    "/*": ["./node_modules/pg-cloudflare/dist/**/*"],
  },
};

export default nextConfig;
