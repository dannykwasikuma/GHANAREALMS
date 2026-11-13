import { NextRequest, NextResponse } from 'next/server';
import { queryOne } from '@/lib/db';

export async function GET(req: NextRequest) {
  const orderNumber = req.nextUrl.searchParams.get('order');
  if (!orderNumber) {
    return NextResponse.json({ error: 'Missing order number' }, { status: 400 });
  }

  const order = await queryOne<{
    order_number: string;
    status: string;
    created_at: string;
    minecraft_username: string;
    product_name: string;
  }>(
    `SELECT
       o.order_number, o.status, o.created_at,
       mp.username AS minecraft_username,
       p.name AS product_name
     FROM orders o
     JOIN minecraft_players mp ON mp.id = o.minecraft_player_id
     JOIN order_items oi ON oi.order_id = o.id
     JOIN products p ON p.id = oi.product_id
     WHERE o.order_number = $1
     LIMIT 1`,
    [orderNumber]
  );

  if (!order) {
    return NextResponse.json({ error: 'Order not found' }, { status: 404 });
  }

  const delivery = await queryOne<{ status: string }>(
    `SELECT dq.status FROM delivery_queue dq
     JOIN orders o ON o.id = dq.order_id
     WHERE o.order_number = $1 LIMIT 1`,
    [orderNumber]
  );

  // Deliberately narrow response - no payment amounts, no internal IDs,
  // no email, per section 27's "do not reveal sensitive information."
  return NextResponse.json({
    orderNumber: order.order_number,
    product: order.product_name,
    minecraftUsername: order.minecraft_username,
    paymentStatus: order.status,
    deliveryStatus: delivery?.status ?? 'N/A',
    purchaseDate: order.created_at,
  });
}
