import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';

/**
 * POST /api/bridge/clans/sync
 *
 * The Minecraft plugin's declarative source of truth push: "here is the
 * full current clan roster right now." Called on an interval (and on
 * clan create/join/leave/disband) rather than the website trying to track
 * individual membership events — a full-state sync is simpler to keep
 * correct than a stream of deltas, and matches the existing
 * pending-deliveries polling style (plugin-initiated, bridge-secret
 * authenticated) rather than inventing a different integration pattern.
 *
 * A clan/member missing from this payload is treated as having left —
 * this endpoint is authoritative for membership, not additive.
 */

const bodySchema = z.object({
  clans: z.array(
    z.object({
      tag: z.string().min(1).max(16),
      name: z.string().min(1).max(64),
      leaderMinecraftUuid: z.string().uuid(),
      leaderMinecraftUsername: z.string().min(3).max(16),
      members: z.array(
        z.object({
          minecraftUuid: z.string().uuid(),
          minecraftUsername: z.string().min(3).max(16),
        })
      ),
    })
  ),
});

async function upsertPlayer(
  client: { query: (typeof pool)['query'] },
  uuid: string,
  username: string
): Promise<string> {
  const existing = await client.query('SELECT id FROM minecraft_players WHERE minecraft_uuid = $1', [uuid]);
  if (existing.rows[0]) {
    // Username can drift (player renames) — keep it current.
    await client.query('UPDATE minecraft_players SET username = $2 WHERE id = $1', [existing.rows[0].id, username]);
    return existing.rows[0].id;
  }
  const inserted = await client.query(
    'INSERT INTO minecraft_players (username, minecraft_uuid) VALUES ($1, $2) RETURNING id',
    [username, uuid]
  );
  return inserted.rows[0].id;
}

export async function POST(req: NextRequest) {
  const secret = req.headers.get('x-bridge-secret');
  if (!verifyBridgeSecret(secret)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  let body;
  try {
    body = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const seenTags: string[] = [];

    for (const clanInput of body.clans) {
      seenTags.push(clanInput.tag);
      const leaderId = await upsertPlayer(client, clanInput.leaderMinecraftUuid, clanInput.leaderMinecraftUsername);

      const clanRow = await client.query(
        `INSERT INTO clans (tag, name, leader_minecraft_player_id, active)
         VALUES ($1, $2, $3, true)
         ON CONFLICT (tag) DO UPDATE SET
           name = EXCLUDED.name,
           leader_minecraft_player_id = EXCLUDED.leader_minecraft_player_id,
           active = true,
           updated_at = now()
         RETURNING id`,
        [clanInput.tag, clanInput.name, leaderId]
      );
      const clanId = clanRow.rows[0].id;

      const memberIds: string[] = [leaderId];
      for (const m of clanInput.members) {
        memberIds.push(await upsertPlayer(client, m.minecraftUuid, m.minecraftUsername));
      }

      // Mark anyone currently active in this clan but absent from this
      // payload as having left.
      await client.query(
        `UPDATE clan_members SET left_at = now()
         WHERE clan_id = $1 AND left_at IS NULL AND minecraft_player_id != ALL($2::uuid[])`,
        [clanId, memberIds]
      );

      // Add/keep everyone in the payload as active members (leader
      // included, with role 'leader').
      for (const playerId of memberIds) {
        const role = playerId === leaderId ? 'leader' : 'member';
        const current = await client.query(
          `SELECT id, role FROM clan_members WHERE clan_id = $1 AND minecraft_player_id = $2 AND left_at IS NULL`,
          [clanId, playerId]
        );
        if (current.rows[0]) {
          if (current.rows[0].role !== role) {
            await client.query(`UPDATE clan_members SET role = $2 WHERE id = $1`, [current.rows[0].id, role]);
          }
        } else {
          // A player can only be active in one clan (unique index) — if
          // they're shown active elsewhere, close that membership first so
          // this insert doesn't violate the constraint (handles a player
          // switching clans between syncs).
          await client.query(
            `UPDATE clan_members SET left_at = now() WHERE minecraft_player_id = $1 AND left_at IS NULL`,
            [playerId]
          );
          await client.query(
            `INSERT INTO clan_members (clan_id, minecraft_player_id, role) VALUES ($1, $2, $3)`,
            [clanId, playerId, role]
          );
        }
      }
    }

    // A clan not present in this sync at all is deactivated (disbanded),
    // not deleted — preserves its historical votes/rewards.
    if (seenTags.length > 0) {
      await client.query(`UPDATE clans SET active = false, updated_at = now() WHERE tag != ALL($1::text[]) AND active = true`, [seenTags]);
    }

    await client.query('COMMIT');
    return NextResponse.json({ ok: true, clansSynced: body.clans.length });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('clan sync failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
