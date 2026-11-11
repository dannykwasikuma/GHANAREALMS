import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';
import { effectivePrice, inWindow, purchasedCount } from '@/lib/catalogue';

const schema = z.object({
  minecraftUuid: z.string().uuid(),
  minecraftUsername: z.string().min(3).max(16),
  slug: z.string().min(1),
  quantity: z.number().int().min(1).max(64).default(1),
  /** Price the player was shown and the plugin will charge - rejected if it no longer matches. */
  expectedUnitPrice: z.number().int().positive(),
});

/**
 * POST /api/bridge/catalogue/purchase - the plugin RESERVES a purchase here BEFORE taking coins.
 * Atomically checks availability, per-player limits and stock (shared with the website), records
 * it, and returns the authoritative price. If the plugin then can't charge the player it calls
 * /refund with the returned purchaseId.
 */
export async function POST(req: NextRequest) {
  if (!verifyBridgeSecret(req.headers.get('x-bridge-secret'))) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  let b;
  try { b = schema.parse(await req.json()); } catch { return NextResponse.json({ error: 'Invalid request' }, { status: 400 }); }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const pr = await client.query(
      `SELECT id, active, gameplay_only, ingame_price_minor, discount_percent, discount_ends_at, available_from, available_until,
              stock, purchase_limit_per_player AS lim, limit_window_days AS win, item_specs
       FROM products WHERE slug = $1 FOR UPDATE`, [b.slug]);
    const p = pr.rows[0];
    if (!p || !p.active || p.gameplay_only || p.ingame_price_minor == null || !inWindow(p.available_from, p.available_until)) {
      await client.query('ROLLBACK'); return NextResponse.json({ error: 'Not available' }, { status: 404 });
    }
    const unit = effectivePrice(Number(p.ingame_price_minor), p.discount_percent, p.discount_ends_at)!;
    if (unit !== b.expectedUnitPrice) {
      await client.query('ROLLBACK'); return NextResponse.json({ error: 'Price changed', unitPrice: unit }, { status: 409 });
    }
    // find or create the player row
    let player = (await client.query('SELECT id FROM minecraft_players WHERE minecraft_uuid = $1', [b.minecraftUuid])).rows[0];
    if (!player) player = (await client.query('INSERT INTO minecraft_players (username, minecraft_uuid) VALUES ($1,$2) RETURNING id', [b.minecraftUsername, b.minecraftUuid])).rows[0];

    if (p.lim != null) {
      const have = await purchasedCount(client, player.id, p.id, p.win);
      if (have + b.quantity > p.lim) {
        await client.query('ROLLBACK');
        return NextResponse.json({ error: `Limit reached (${p.lim}${p.win ? ` per ${p.win} days` : ''})` }, { status: 409 });
      }
    }
    if (p.stock != null) {
      const u = await client.query('UPDATE products SET stock = stock - $2, updated_at = now() WHERE id = $1 AND stock >= $2', [p.id, b.quantity]);
      if (u.rowCount === 0) { await client.query('ROLLBACK'); return NextResponse.json({ error: 'Sold out' }, { status: 409 }); }
    }
    const rec = await client.query(
      `INSERT INTO ingame_purchases (product_id, minecraft_player_id, quantity, unit_price_minor) VALUES ($1,$2,$3,$4) RETURNING id`,
      [p.id, player.id, b.quantity, unit]);
    await client.query('COMMIT');
    return NextResponse.json({ ok: true, purchaseId: rec.rows[0].id, unitPrice: unit, itemSpecs: p.item_specs });
  } catch (err) {
    await client.query('ROLLBACK'); console.error('ingame purchase failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally { client.release(); }
}
