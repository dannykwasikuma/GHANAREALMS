import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';

/** POST /api/bridge/catalogue/refund - undo a reservation when the player could not be charged / given the items. */
export async function POST(req: NextRequest) {
  if (!verifyBridgeSecret(req.headers.get('x-bridge-secret'))) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  let b;
  try { b = z.object({ purchaseId: z.string().uuid() }).parse(await req.json()); } catch { return NextResponse.json({ error: 'Invalid request' }, { status: 400 }); }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const del = await client.query('DELETE FROM ingame_purchases WHERE id = $1 RETURNING product_id, quantity', [b.purchaseId]);
    if (del.rows[0]) await client.query('UPDATE products SET stock = stock + $2 WHERE id = $1 AND stock IS NOT NULL', [del.rows[0].product_id, del.rows[0].quantity]);
    await client.query('COMMIT');
    return NextResponse.json({ ok: true, refunded: !!del.rows[0] });
  } catch (err) { await client.query('ROLLBACK'); console.error(err); return NextResponse.json({ error: 'Internal error' }, { status: 500 }); }
  finally { client.release(); }
}
