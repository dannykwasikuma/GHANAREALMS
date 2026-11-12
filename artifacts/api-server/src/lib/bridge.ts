import crypto from "node:crypto";
import type { PoolClient } from "pg";

export function verifyBridgeSecret(provided: string | undefined): boolean {
  const expected = process.env.MINECRAFT_BRIDGE_SECRET;
  if (!expected || !provided) return false;
  const providedBytes = Buffer.from(provided);
  const expectedBytes = Buffer.from(expected);
  return (
    providedBytes.length === expectedBytes.length &&
    crypto.timingSafeEqual(providedBytes, expectedBytes)
  );
}

export function effectivePrice(
  price: number | null,
  discountPercent: number,
  discountEndsAt: Date | string | null,
  now = new Date(),
): number | null {
  if (price === null) return null;
  const active =
    discountPercent > 0 &&
    (!discountEndsAt || new Date(discountEndsAt) > now);
  return active ? Math.floor((price * (100 - discountPercent)) / 100) : price;
}

export function isInWindow(
  from: Date | string | null,
  until: Date | string | null,
  now = new Date(),
): boolean {
  return (
    (!from || new Date(from) <= now) &&
    (!until || new Date(until) >= now)
  );
}

export async function closePendingOrder(
  client: PoolClient,
  orderId: string,
  status: "FAILED" | "CANCELLED",
): Promise<boolean> {
  const result = await client.query(
    `UPDATE orders SET status = $2, updated_at = now()
     WHERE id = $1 AND status = 'PENDING' RETURNING id`,
    [orderId, status],
  );
  if (result.rowCount === 0) return false;
  await client.query(
    `UPDATE products p SET stock = p.stock + oi.quantity, updated_at = now()
     FROM order_items oi
     WHERE oi.order_id = $1 AND oi.product_id = p.id AND p.stock IS NOT NULL`,
    [orderId],
  );
  return true;
}

export async function purchasedCount(
  client: PoolClient,
  playerId: string,
  productId: string,
  windowDays: number | null,
): Promise<number> {
  const web = await client.query<{ n: number | string }>(
    `SELECT coalesce(sum(oi.quantity), 0)::int AS n
     FROM order_items oi JOIN orders o ON o.id = oi.order_id
     WHERE o.minecraft_player_id = $1 AND oi.product_id = $2
       AND o.status IN ('PAID', 'PENDING')
       AND ($3::int IS NULL OR o.created_at > now() - make_interval(days => $3::int))`,
    [playerId, productId, windowDays],
  );
  const inGame = await client.query<{ n: number | string }>(
    `SELECT coalesce(sum(quantity), 0)::int AS n
     FROM ingame_purchases
     WHERE minecraft_player_id = $1 AND product_id = $2
       AND ($3::int IS NULL OR created_at > now() - make_interval(days => $3::int))`,
    [playerId, productId, windowDays],
  );
  return Number(web.rows[0]?.n ?? 0) + Number(inGame.rows[0]?.n ?? 0);
}
