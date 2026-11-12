import { NextRequest, NextResponse } from 'next/server';
import { query } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';

/**
 * GET /api/bridge/catalogue - the in-game shop (/gshop) polls this, so admin edits sync to
 * the server. Only in-game-sellable, active, in-window, non-gameplay-only products with items.
 */
export async function GET(req: NextRequest) {
  if (!verifyBridgeSecret(req.headers.get('x-bridge-secret'))) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  const rows = await query(
    `SELECT slug, name, description, category, rarity, ingame_price_minor, item_specs, icon_material, stock,
            purchase_limit_per_player, limit_window_days,
            CASE WHEN discount_percent > 0 AND (discount_ends_at IS NULL OR discount_ends_at > now())
                 THEN floor(ingame_price_minor * (100 - discount_percent) / 100.0)::bigint ELSE ingame_price_minor END AS price
     FROM products
     WHERE active = true AND gameplay_only = false AND ingame_price_minor IS NOT NULL AND cardinality(item_specs) > 0
       AND (available_from IS NULL OR available_from <= now()) AND (available_until IS NULL OR available_until >= now())
     ORDER BY sort_order ASC, name ASC`
  );
  return NextResponse.json({ products: rows });
}
