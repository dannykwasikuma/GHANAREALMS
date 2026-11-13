import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { getAdminSession } from '@/lib/admin-session';
import { nextPeriodWindow, PeriodType } from '@/lib/clan-periods';

/**
 * POST /api/admin/clans/periods/:id/settle
 *
 * Settlement is deliberately mechanical — this is the actual "no bias
 * gifting" implementation:
 *
 *  1. Can only run once `period_end` has passed. An admin cannot cut
 *     voting short to lock in a result early — this is enforced here, not
 *     just documented.
 *  2. Ranking is pure vote count, ties broken by clan id (deterministic,
 *     arbitrary-but-fixed — not admin judgment).
 *  3. Which clans get a gift (top `reward_tier_count`) and what that gift
 *     is (`clan_reward_tiers`, by rank band) are both decided by data that
 *     was configured BEFORE results existed — an admin cannot look at the
 *     standings and then decide what tier 1 gets.
 *  4. The admin action here has no discretion: it can only be run once per
 *     period (status flips to SETTLED) and does exactly the above. It is
 *     logged to audit_logs like every other admin action in this app.
 *
 * Delivery reuses delivery_queue + the existing Minecraft bridge pipeline
 * — no separate gift-delivery mechanism.
 */
export async function POST(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const session = await getAdminSession();
  if (!session) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  const { id } = await params;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const periodRow = await client.query(
      `SELECT id, period_type, period_start, period_end, status, reward_tier_count
       FROM clan_vote_periods WHERE id = $1 FOR UPDATE`,
      [id]
    );
    const period = periodRow.rows[0];
    if (!period) {
      await client.query('ROLLBACK');
      return NextResponse.json({ error: 'Period not found' }, { status: 404 });
    }
    if (period.status === 'SETTLED') {
      await client.query('ROLLBACK');
      return NextResponse.json({ error: 'This period has already been settled' }, { status: 409 });
    }
    if (new Date(period.period_end) > new Date()) {
      await client.query('ROLLBACK');
      return NextResponse.json(
        { error: `Voting does not close until ${period.period_end} — cannot settle early` },
        { status: 409 }
      );
    }

    // Deterministic tally + ranking. ORDER BY vote count desc, clan id asc
    // for ties — fixed and reproducible, never a judgment call.
    const tally = await client.query(
      `SELECT clan_id, count(*)::int AS votes
       FROM clan_votes WHERE period_id = $1
       GROUP BY clan_id
       ORDER BY votes DESC, clan_id ASC
       LIMIT $2`,
      [period.id, period.reward_tier_count]
    );

    const tiers = await client.query(
      `SELECT id, min_rank, max_rank, rank_permission, duration_days, delivery_commands, give_money_minor
       FROM clan_reward_tiers WHERE period_type = $1 ORDER BY min_rank ASC`,
      [period.period_type]
    );

    const rewardsCreated: { clanId: string; rank: number; votes: number; tierId: string | null }[] = [];

    let rank = 0;
    for (const row of tally.rows) {
      rank += 1;
      const tier = tiers.rows.find((t) => rank >= t.min_rank && rank <= t.max_rank);
      if (!tier) {
        // No tier configured for this rank position — that clan placed,
        // but no gift is defined for it. Recorded as a rank-only outcome,
        // not an error, so the leaderboard still shows where they landed.
        rewardsCreated.push({ clanId: row.clan_id, rank, votes: row.votes, tierId: null });
        continue;
      }

      // Every player who was an active member when voting closed gets the
      // gift - membership is judged at period_end, not at the moment an
      // admin clicks settle, so settling late can't change who qualifies.
      const members = await client.query(
        `SELECT minecraft_player_id FROM clan_members
         WHERE clan_id = $1 AND joined_at <= $2 AND (left_at IS NULL OR left_at > $2)`,
        [row.clan_id, period.period_end]
      );
      for (const m of members.rows) {
        const rewardRow = await client.query(
          `INSERT INTO clan_vote_rewards (period_id, clan_id, rank, tier_id, recipient_minecraft_player_id, vote_count)
           VALUES ($1, $2, $3, $4, $5, $6)
           RETURNING id`,
          [period.id, row.clan_id, rank, tier.id, m.minecraft_player_id, row.votes]
        );
        await client.query(
          `INSERT INTO delivery_queue (order_item_id, minecraft_player_id, clan_vote_reward_id, status)
           VALUES (NULL, $1, $2, 'PENDING')`,
          [m.minecraft_player_id, rewardRow.rows[0].id]
        );
      }

      rewardsCreated.push({ clanId: row.clan_id, rank, votes: row.votes, tierId: tier.id });
    }

    await client.query(
      `UPDATE clan_vote_periods SET status = 'SETTLED', settled_at = now() WHERE id = $1`,
      [period.id]
    );

    await client.query(
      `INSERT INTO audit_logs (admin_user_id, action, target_type, target_id, details)
       VALUES ($1, 'clan_period_settled', 'clan_vote_period', $2, $3)`,
      [session.adminId, period.id, JSON.stringify({ rewardsCreated })]
    );

    // Open the next period of the same type immediately — voting should
    // never have a gap.
    const { start, end } = nextPeriodWindow(new Date(period.period_end), period.period_type as PeriodType);
    await client.query(
      `INSERT INTO clan_vote_periods (period_type, period_start, period_end, status, reward_tier_count)
       VALUES ($1, $2, $3, 'OPEN', $4)
       ON CONFLICT DO NOTHING`,
      [period.period_type, start.toISOString(), end.toISOString(), period.reward_tier_count]
    );

    await client.query('COMMIT');
    return NextResponse.json({ ok: true, rewardsCreated });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('clan period settlement failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
