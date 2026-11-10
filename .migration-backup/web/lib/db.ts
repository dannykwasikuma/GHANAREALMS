import { Pool } from 'pg';

// A single shared pool, per Next.js's standard serverless-safe pattern
// (reused across hot-reloads in dev, one pool per server process in prod).
declare global {
  // eslint-disable-next-line no-var
  var _ghanarealmsPool: Pool | undefined;
}

export const pool =
  global._ghanarealmsPool ??
  new Pool({
    connectionString: process.env.DATABASE_URL,
    max: 10,
  });

if (process.env.NODE_ENV !== 'production') {
  global._ghanarealmsPool = pool;
}

export async function query<T = any>(text: string, params?: any[]) {
  const result = await pool.query(text, params);
  return result.rows as T[];
}

export async function queryOne<T = any>(text: string, params?: any[]) {
  const rows = await query<T>(text, params);
  return rows[0] ?? null;
}
