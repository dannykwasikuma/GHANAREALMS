import pg, { type QueryResultRow } from "pg";

const { Pool } = pg;

let sharedPool: pg.Pool | undefined;

export function getGhanaRealmsPool(): pg.Pool {
  const connectionString =
    process.env.DATABASE_URL ?? process.env.EXTERNAL_DATABASE_URL;
  if (!connectionString) {
    throw new Error("DATABASE_URL is not configured for GhanaRealms.");
  }

  sharedPool ??= new Pool({ connectionString, max: 10 });
  return sharedPool;
}

export async function grQuery<T extends QueryResultRow = QueryResultRow>(
  text: string,
  values: readonly unknown[] = [],
): Promise<T[]> {
  const result = await getGhanaRealmsPool().query<T>(text, [...values]);
  return result.rows;
}

export async function grQueryOne<T extends QueryResultRow = QueryResultRow>(
  text: string,
  values: readonly unknown[] = [],
): Promise<T | null> {
  const rows = await grQuery<T>(text, values);
  return rows[0] ?? null;
}
