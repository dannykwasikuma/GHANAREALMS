import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { getAdminSession } from '@/lib/admin-session';
import { productCreateSchema } from '@/lib/admin-products';

/**
 * POST /api/admin/products - add a product. Delivery commands are derived from item_specs
 * (`gritem give {player} <spec>`), so admins only describe the items, never raw console commands.
 */
export async function POST(req: NextRequest) {
  const session = await getAdminSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  let d;
  try { d = productCreateSchema.parse(await req.json()); }
  catch (e) { return NextResponse.json({ error: 'Invalid fields', detail: String(e) }, { status: 400 }); }

  const commands = d.item_specs.map((s) => `gritem give {player} ${s}`);
  const icon = d.item_specs[0]?.split(' ')[0] ?? null;
  try {
    const r = await pool.query(
      `INSERT INTO products (name, slug, description, price_pesewas, ingame_price_minor, category, rarity, delivery_commands,
         item_specs, icon_material, stock, purchase_limit_per_player, limit_window_days, gameplay_only, image_url, active, featured)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17) RETURNING id`,
      [d.name, d.slug, d.description ?? '', d.price_pesewas ?? null, d.ingame_price_minor ?? null, d.category, d.rarity ?? null,
       commands, d.item_specs, icon, d.stock ?? null, d.purchase_limit_per_player ?? null, d.limit_window_days ?? null,
       d.gameplay_only ?? false, d.image_url ?? null, d.active ?? true, d.featured ?? false]
    );
    await pool.query(
      `INSERT INTO audit_logs (admin_user_id, action, target_type, target_id, details) VALUES ($1, 'product_created', 'product', $2, $3)`,
      [session.adminId, r.rows[0].id, JSON.stringify({ slug: d.slug })]
    );
    return NextResponse.json({ ok: true, id: r.rows[0].id });
  } catch (err) {
    console.error('product create failed:', err);
    return NextResponse.json({ error: 'Could not create (duplicate slug or no price?)' }, { status: 400 });
  }
}
