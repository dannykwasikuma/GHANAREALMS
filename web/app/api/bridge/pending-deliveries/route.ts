import { NextRequest, NextResponse } from 'next/server';
import { query } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';

/**
 * The Minecraft plugin polls this (on an interval, and on player join for
 * the offline-purchase case per section 24) rather than the web backend
 * pushing to the Minecraft server - this avoids needing inbound
 * connectivity to the Minecraft server from the web backend, which may be
 * on a different VPS entirely (section 36).
 *
 * Extended for clan vote gifts (see migrations/003_clan_voting.sql):
 * delivery_queue now has two possible sources (a purchased order_item, or
 * a settled clan_vote_reward) — this UNION covers both so the plugin has
 * one poll loop and one ack endpoint for everything it delivers, rather
 * than a second parallel delivery system.
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
    source: 'purchase' | 'clan_reward';
    label: string;
    rank_permission: string | null;
    duration_days: number | null;
    delivery_commands: string[];
    give_money_minor: number;
  }>(
    `SELECT
       dq.id AS delivery_id,
       mp.username AS minecraft_username,
       mp.minecraft_uuid,
       'purchase' AS source,
       p.name AS label,
       p.rank_permission,
       p.duration_days,
       p.delivery_commands,
       p.give_money_minor
     FROM delivery_queue dq
     JOIN minecraft_players mp ON mp.id = dq.minecraft_player_id
     JOIN order_items oi ON oi.id = dq.order_item_id
     JOIN products p ON p.id = oi.product_id
     WHERE dq.status IN ('PENDING', 'RETRYING') AND dq.order_item_id IS NOT NULL

     UNION ALL

     SELECT
       dq.id AS delivery_id,
       mp.username AS minecraft_username,
       mp.minecraft_uuid,
       'clan_reward' AS source,
       ('Clan vote reward: ' || t.label) AS label,
       t.rank_permission,
       t.duration_days,
       t.delivery_commands,
       t.give_money_minor
     FROM delivery_queue dq
     JOIN minecraft_players mp ON mp.id = dq.minecraft_player_id
     JOIN clan_vote_rewards r ON r.id = dq.clan_vote_reward_id
     JOIN clan_reward_tiers t ON t.id = r.tier_id
     WHERE dq.status IN ('PENDING', 'RETRYING') AND dq.clan_vote_reward_id IS NOT NULL

     ORDER BY delivery_id ASC
     LIMIT 100`
  );

  return NextResponse.json({ deliveries: rows });
}
