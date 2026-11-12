import type { PoolClient } from 'pg';

/** Pure: price actually charged after an active percentage discount (integer, floored). */
export function effectivePrice(
  price: number | null,
  discountPercent: number,
  discountEndsAt: Date | string | null,
  now: Date = new Date()
): number | null {
  if (price == null) return null;
  const active = discountPercent > 0 && (!discountEndsAt || new Date(discountEndsAt) > now);
  return active ? Math.floor((price * (100 - discountPercent)) / 100) : price;
}

/** Pure: is the product inside its availability window? */
export function inWindow(from: Date | string | null, until: Date | string | null, now: Date = new Date()): boolean {
  return (!from || new Date(from) <= now) && (!until || new Date(until) >= now);
}

/** Give back reserved stock for an order, once. Call only on a PENDING -> FAILED/CANCELLED transition. */
export async function releaseStockForOrder(client: PoolClient, orderId: string): Promise<void> {
  await client.query(
    `UPDATE products p SET stock = p.stock + oi.quantity, updated_at = now()
     FROM order_items oi
     WHERE oi.order_id = $1 AND oi.product_id = p.id AND p.stock IS NOT NULL`,
    [orderId]
  );
}

/** Mark a still-PENDING order FAILED/CANCELLED and release its stock. Returns true if it changed. */
export async function closePendingOrder(client: PoolClient, orderId: string, status: 'FAILED' | 'CANCELLED'): Promise<boolean> {
  const r = await client.query(
    `UPDATE orders SET status = $2, updated_at = now() WHERE id = $1 AND status = 'PENDING' RETURNING id`,
    [orderId, status]
  );
  if (r.rowCount === 0) return false;
  await releaseStockForOrder(client, orderId);
  return true;
}

/** Orders that sat PENDING for an hour never completed: cancel them so reserved stock/limits free up. */
export async function expireStalePendingOrders(client: PoolClient): Promise<void> {
  const stale = await client.query(
    `SELECT id FROM orders WHERE status = 'PENDING' AND created_at < now() - interval '1 hour' LIMIT 200`
  );
  for (const row of stale.rows) {
    await closePendingOrder(client, row.id, 'CANCELLED');
    await client.query(`UPDATE payments SET status = 'ABANDONED' WHERE order_id = $1 AND status = 'PENDING'`, [row.id]);
  }
}

/**
 * Units of a product this player has bought in the limit window (web orders PAID, or PENDING
 * in the last hour, plus in-game purchases). windowDays null = lifetime.
 */
export async function purchasedCount(
  client: PoolClient, playerId: string, productId: string, windowDays: number | null
): Promise<number> {
  const since = windowDays ? `AND o.created_at > now() - make_interval(days => ${Number(windowDays)})` : '';
  const sinceIg = windowDays ? `AND created_at > now() - make_interval(days => ${Number(windowDays)})` : '';
  const web = await client.query(
    `SELECT coalesce(sum(oi.quantity),0)::int AS n FROM order_items oi JOIN orders o ON o.id = oi.order_id
     WHERE o.minecraft_player_id = $1 AND oi.product_id = $2 AND o.status IN ('PAID','PENDING') ${since}`,
    [playerId, productId]
  );
  const ig = await client.query(
    `SELECT coalesce(sum(quantity),0)::int AS n FROM ingame_purchases
     WHERE minecraft_player_id = $1 AND product_id = $2 ${sinceIg}`,
    [playerId, productId]
  );
  return web.rows[0].n + ig.rows[0].n;
}
