import { PoolClient } from 'pg';
import { pool } from './db';

export type PeriodType = 'WEEKLY' | 'MONTHLY';

function addPeriod(start: Date, type: PeriodType): Date {
  const end = new Date(start);
  if (type === 'WEEKLY') {
    end.setUTCDate(end.getUTCDate() + 7);
  } else {
    end.setUTCMonth(end.getUTCMonth() + 1);
  }
  return end;
}

/**
 * Returns the currently OPEN period for this type, creating one if none
 * exists (e.g. the very first vote ever cast, or right after a settlement
 * already opened the next one — this is just a safety net). period_start
 * always begins at the top of the current UTC day so windows are
 * predictable rather than drifting off whatever moment the first vote
 * happened to land on.
 */
export async function getOrCreateOpenPeriod(
  client: PoolClient | typeof pool,
  type: PeriodType
): Promise<{ id: string; period_start: string; period_end: string; reward_tier_count: number }> {
  const existing = await client.query(
    `SELECT id, period_start, period_end, reward_tier_count FROM clan_vote_periods
     WHERE period_type = $1 AND status = 'OPEN'`,
    [type]
  );
  if (existing.rows[0]) return existing.rows[0];

  const start = new Date();
  start.setUTCHours(0, 0, 0, 0);
  const end = addPeriod(start, type);

  const inserted = await client.query(
    `INSERT INTO clan_vote_periods (period_type, period_start, period_end, status)
     VALUES ($1, $2, $3, 'OPEN')
     RETURNING id, period_start, period_end, reward_tier_count`,
    [type, start.toISOString(), end.toISOString()]
  );
  return inserted.rows[0];
}

export function nextPeriodWindow(previousEnd: Date, type: PeriodType) {
  const start = previousEnd;
  const end = addPeriod(start, type);
  return { start, end };
}
