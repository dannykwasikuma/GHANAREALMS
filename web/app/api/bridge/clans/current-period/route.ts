import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';
import { getOrCreateOpenPeriod, PeriodType } from '@/lib/clan-periods';

/**
 * GET /api/bridge/clans/current-period?type=WEEKLY
 *
 * Lets the plugin show players "voting closes in X" and know which
 * period a vote will land in before submitting one.
 */
export async function GET(req: NextRequest) {
  const secret = req.headers.get('x-bridge-secret');
  if (!verifyBridgeSecret(secret)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const type = req.nextUrl.searchParams.get('type');
  if (type !== 'WEEKLY' && type !== 'MONTHLY') {
    return NextResponse.json({ error: 'type must be WEEKLY or MONTHLY' }, { status: 400 });
  }

  const period = await getOrCreateOpenPeriod(pool, type as PeriodType);
  return NextResponse.json({ period });
}
