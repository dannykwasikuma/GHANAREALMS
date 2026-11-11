import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { getAdminSession } from '@/lib/admin-session';
import { productPatchSchema, EDITABLE } from '@/lib/admin-products';

/** PATCH /api/admin/products/:id - edit prices, stock, limits, discount, visibility. Audit-logged. */
export async function PATCH(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const session = await getAdminSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  const { id } = await params;

  let patch;
  try { patch = productPatchSchema.parse(await req.json()); }
  catch (e) { return NextResponse.json({ error: 'Invalid fields', detail: String(e) }, { status: 400 }); }

  const keys = EDITABLE.filter((k) => k in patch);
  if (keys.length === 0) return NextResponse.json({ error: 'Nothing to update' }, { status: 400 });

  const sets = keys.map((k, i) => `${k} = $${i + 2}`);
  const vals = keys.map((k) => (patch as Record<string, unknown>)[k]);

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const before = await client.query('SELECT * FROM products WHERE id = $1 FOR UPDATE', [id]);
    if (!before.rows[0]) { await client.query('ROLLBACK'); return NextResponse.json({ error: 'Not found' }, { status: 404 }); }
    const res = await client.query(`UPDATE products SET ${sets.join(', ')}, updated_at = now() WHERE id = $1 RETURNING *`, [id, ...vals]);
    const changed: Record<string, { from: unknown; to: unknown }> = {};
    for (const k of keys) changed[k] = { from: before.rows[0][k], to: res.rows[0][k] };
    await client.query(
      `INSERT INTO audit_logs (admin_user_id, action, target_type, target_id, details) VALUES ($1, 'product_updated', 'product', $2, $3)`,
      [session.adminId, id, JSON.stringify(changed)]
    );
    await client.query('COMMIT');
    return NextResponse.json({ ok: true, product: res.rows[0] });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('product update failed:', err);
    return NextResponse.json({ error: 'Update failed (check price/limit values)' }, { status: 400 });
  } finally { client.release(); }
}
