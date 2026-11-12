import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';
import { getOrCreateOpenPeriod } from '@/lib/clan-periods';

/**
 * POST /api/bridge/clans/vote
 *
 * Casts one clan vote. Called ONLY by the Minecraft plugin (bridge-secret
 * authenticated), never from a web form — the plugin already knows the
 * real, currently-authenticated player on the server, so there is no
 * "submit a vote as someone else" surface at all. This is the primary
 * anti-bias guarantee for voter identity; the rest (one vote per player
 * per period, no self-voting) is enforced here and in the schema.
 */

const bodySchema = z.object({
  voterMinecraftUuid: z.string().uuid(),
  voterMinecraftUsername: z.string().min(3).max(16),
  clanTag: z.string().min(1).max(16),
  periodType: z.enum(['WEEKLY', 'MONTHLY']),
});

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

    const clan = await client.query(
      'SELECT id FROM clans WHERE tag = $1 AND active = true',
      [body.clanTag]
    );
    if (!clan.rows[0]) {
      await client.query('ROLLBACK');
      return NextResponse.json({ error: 'Unknown or inactive clan tag' }, { status: 404 });
    }
    const clanId = clan.rows[0].id;

    // Resolve/create the voter's minecraft_players row — same upsert
    // pattern checkout/init already uses.
    const existingPlayer = await client.query(
      'SELECT id FROM minecraft_players WHERE minecraft_uuid = $1',
      [body.voterMinecraftUuid]
    );
    let voterId: string;
    if (existingPlayer.rows[0]) {
      voterId = existingPlayer.rows[0].id;
    } else {
      const inserted = await client.query(
        'INSERT INTO minecraft_players (username, minecraft_uuid) VALUES ($1, $2) RETURNING id',
        [body.voterMinecraftUsername, body.voterMinecraftUuid]
      );
      voterId = inserted.rows[0].id;
    }

    // Anti-bias: a player cannot vote for their own current clan. Checked
    // here rather than as a DB constraint because membership changes over
    // time and this must reflect the voter's clan right now.
    const ownClan = await client.query(
      `SELECT clan_id FROM clan_members WHERE minecraft_player_id = $1 AND left_at IS NULL`,
      [voterId]
    );
    if (ownClan.rows[0]?.clan_id === clanId) {
      await client.query('ROLLBACK');
      return NextResponse.json(
        { error: 'You cannot vote for your own clan' },
        { status: 400 }
      );
    }

    const period = await getOrCreateOpenPeriod(client, body.periodType);
    if (new Date(period.period_end) <= new Date()) {
      // The open period has reached its end but hasn't been settled yet
      // (settlement runs on its own schedule — see
      // docs/CLAN_VOTING.md "Settlement timing"). Voting closes at the
      // period boundary even if settlement is still pending, so results
      // can never be influenced by a vote cast after the window closed.
      await client.query('ROLLBACK');
      return NextResponse.json(
        { error: 'Voting is closed for this period — results are being tallied' },
        { status: 409 }
      );
    }

    // UNIQUE(period_id, voter) is the real one-vote-per-player guarantee;
    // ON CONFLICT DO NOTHING makes a resubmitted/retried request from the
    // plugin safely idempotent rather than erroring.
    const insert = await client.query(
      `INSERT INTO clan_votes (period_id, voter_minecraft_player_id, clan_id)
       VALUES ($1, $2, $3)
       ON CONFLICT (period_id, voter_minecraft_player_id) DO NOTHING
       RETURNING id`,
      [period.id, voterId, clanId]
    );

    await client.query('COMMIT');

    if (!insert.rows[0]) {
      return NextResponse.json(
        { ok: true, alreadyVoted: true, message: 'You already voted this period' },
        { status: 200 }
      );
    }
    return NextResponse.json({ ok: true, alreadyVoted: false });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('clan vote failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
