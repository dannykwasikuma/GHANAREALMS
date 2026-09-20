import { NextRequest, NextResponse } from 'next/server';
import { query } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';

/**
 * The Minecraft plugin polls this (on an interval, and on player join for
 * the offline-purchase case per section 24) rather than the web backend
 * pushing to the Minecraft server - this avoids needing inbound
 * connectivity to the Minecraft server from the web backend, which may be
 * on a different VPS entirely (section 36).
 */
export async function GET(req: NextRequest) {
  const secret = req.headers.get('x-bridge-secret');
  if (!verifyBridgeSecret(secret)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const rows = await query<{
    delivery_id: string;
    minecraft_username: string;
    minecraft_uuid: string | null;
    product_slug: string;
    rank_permission: string | null;
    duration_days: number | null;
    delivery_commands: string[];
    give_money_minor: number;
  }>(
    `SELECT
       dq.id AS delivery_id,
       mp.username AS minecraft_username,
       mp.minecraft_uuid,
       p.slug AS product_slug,
       p.rank_permission,
       p.duration_days,
       p.delivery_commands,
       p.give_money_minor
     FROM delivery_queue dq
     JOIN minecraft_players mp ON mp.id = dq.minecraft_player_id
     JOIN order_items oi ON oi.id = dq.order_item_id
     JOIN products p ON p.id = oi.product_id
     WHERE dq.status IN ('PENDING', 'RETRYING')
     ORDER BY dq.created_at ASC
     LIMIT 100`
  );

  return NextResponse.json({ deliveries: rows });
}
