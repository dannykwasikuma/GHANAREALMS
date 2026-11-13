import { Pool } from 'pg';

// --- Cloudflare Hyperdrive support -----------------------------------------
// On Cloudflare Workers (via OpenNext), `pg` can't open a raw TCP socket the
// way it does under plain Node. Hyperdrive is Cloudflare's supported bridge
// for this (see wrangler.jsonc's `hyperdrive` binding): it terminates the
// real connection to Postgres on Cloudflare's side and hands the Worker a
// connection string that behaves like a normal one to `pg`. Outside
// Workers (local dev, `next build`, tests, any non-Cloudflare deployment)
// this is skipped and the plain DATABASE_URL env var is used, unchanged from
// before.
//
// Several routes (checkout/init, the Paystack webhook, bridge/ack-delivery)
// call `pool.connect()` directly to run multi-statement transactions, and
// admin/login calls `pool.query(...)` directly — not through the query()/
// queryOne() helpers below. Rather than edit each of those route files, the
// exported `pool` itself lazily resolves to the right underlying Pool (via a
// Proxy) the first time anything touches it, so `pool.connect()` and
// `pool.query()` keep working unchanged everywhere they're already used.
// Lazy resolution also matters on Workers specifically: the Hyperdrive
// binding is only reliably available once a request is being handled, not
// at module-load time during a cold start — a Proxy defers that lookup to
// first real use instead of baking in a value too early.
//
// pg-cloudflare is a direct dependency (see package.json) and IS needed at
// runtime on Workers: pg uses it for sockets when it detects the Workers
// runtime. next.config.ts forces its dist files into the traced output.

function getHyperdriveConnectionString(): string | undefined {
  try {
    // Lazy require, not a static import: this must have zero effect (and no
    // import-time error) when @opennextjs/cloudflare isn't the active
    // runtime, e.g. local `next dev`/`next build`/tests.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { getCloudflareContext } = require('@opennextjs/cloudflare');
    const ctx = getCloudflareContext();
    return ctx?.env?.HYPERDRIVE?.connectionString;
  } catch {
    return undefined;
  }
}

declare global {
  // eslint-disable-next-line no-var
  var _ghanarealmsPool: Pool | undefined;
  // eslint-disable-next-line no-var
  var _ghanarealmsHyperdrivePool: Pool | undefined;
}

function resolveActivePool(): Pool {
  const hyperdriveConnectionString = getHyperdriveConnectionString();
  if (hyperdriveConnectionString) {
    if (!global._ghanarealmsHyperdrivePool) {
      global._ghanarealmsHyperdrivePool = new Pool({ connectionString: hyperdriveConnectionString, max: 5 });
    }
    return global._ghanarealmsHyperdrivePool;
  }

  if (!global._ghanarealmsPool) {
    const connectionString = process.env.DATABASE_URL;
    if (!connectionString) {
      throw new Error(
        'No database connection available: neither a Hyperdrive binding nor DATABASE_URL is set.',
      );
    }
    global._ghanarealmsPool = new Pool({ connectionString, max: 10 });
  }
  return global._ghanarealmsPool;
}

// `pool` behaves exactly like a `pg` Pool instance to every existing call
// site (pool.connect(), pool.query(), etc.) but resolves lazily, per the
// explanation above, instead of being constructed once at module load.
export const pool: Pool = new Proxy({} as Pool, {
  get(_target, prop, receiver) {
    const real = resolveActivePool();
    const value = Reflect.get(real, prop, real);
    return typeof value === 'function' ? value.bind(real) : value;
  },
});

export async function query<T = any>(text: string, params?: any[]) {
  const result = await resolveActivePool().query(text, params);
  return result.rows as T[];
}

export async function queryOne<T = any>(text: string, params?: any[]) {
  const rows = await query<T>(text, params);
  return rows[0] ?? null;
}
