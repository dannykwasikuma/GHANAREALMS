import { query } from "@/lib/db";

interface StandingRow {
  clan_id: string;
  tag: string;
  name: string;
  votes: string;
}

interface WinnerRow {
  rank: number;
  tag: string;
  name: string;
  vote_count: number;
  label: string | null;
}

async function getOpenPeriod(type: "WEEKLY" | "MONTHLY") {
  try {
    const rows = await query<{ id: string; period_start: string; period_end: string }>(
      `SELECT id, period_start, period_end FROM clan_vote_periods
       WHERE period_type = $1 AND status = 'OPEN' LIMIT 1`,
      [type]
    );
    return rows[0] ?? null;
  } catch {
    return null;
  }
}

async function getStandings(periodId: string): Promise<StandingRow[]> {
  return query<StandingRow>(
    `SELECT c.id AS clan_id, c.tag, c.name, count(v.id) AS votes
     FROM clans c
     LEFT JOIN clan_votes v ON v.clan_id = c.id AND v.period_id = $1
     WHERE c.active = true
     GROUP BY c.id, c.tag, c.name
     ORDER BY votes DESC, c.tag ASC
     LIMIT 20`,
    [periodId]
  );
}

async function getLastWinners(type: "WEEKLY" | "MONTHLY"): Promise<{ periodEnd: string; winners: WinnerRow[] } | null> {
  const period = await query<{ id: string; period_end: string }>(
    `SELECT id, period_end FROM clan_vote_periods
     WHERE period_type = $1 AND status = 'SETTLED'
     ORDER BY period_end DESC LIMIT 1`,
    [type]
  );
  if (!period[0]) return null;

  const winners = await query<WinnerRow>(
    `SELECT DISTINCT r.rank, c.tag, c.name, r.vote_count, t.label
     FROM clan_vote_rewards r
     JOIN clans c ON c.id = r.clan_id
     LEFT JOIN clan_reward_tiers t ON t.id = r.tier_id
     WHERE r.period_id = $1
     ORDER BY r.rank ASC`,
    [period[0].id]
  );
  return { periodEnd: period[0].period_end, winners };
}

function Standings({ title, periodEnd, standings }: { title: string; periodEnd: string | null; standings: StandingRow[] }) {
  return (
    <div className="card p-6">
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-lg font-semibold">{title}</h2>
        {periodEnd && (
          <span className="text-xs text-foreground/50">Voting closes {new Date(periodEnd).toLocaleString()}</span>
        )}
      </div>
      {standings.length === 0 && <p className="text-sm text-foreground/60 mt-4">No votes yet this period.</p>}
      {standings.length > 0 && (
        <ol className="mt-4 flex flex-col gap-2">
          {standings.map((s, i) => (
            <li key={s.clan_id} className="flex items-center justify-between border-b border-card-border last:border-0 pb-2">
              <span className="flex items-center gap-3">
                <span className="text-foreground/40 text-sm w-5">{i + 1}</span>
                <span className="font-semibold">[{s.tag}]</span>
                <span className="text-foreground/80">{s.name}</span>
              </span>
              <span className="pill pill-paid">{s.votes} vote{s.votes === "1" ? "" : "s"}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function LastWinners({ title, data }: { title: string; data: { periodEnd: string; winners: WinnerRow[] } | null }) {
  return (
    <div className="card p-6">
      <h2 className="text-lg font-semibold mb-1">{title}</h2>
      {!data && <p className="text-sm text-foreground/60 mt-4">No period has been settled yet.</p>}
      {data && (
        <>
          <p className="text-xs text-foreground/50 mb-4">Settled {new Date(data.periodEnd).toLocaleDateString()}</p>
          <ol className="flex flex-col gap-2">
            {data.winners.map((w) => (
              <li key={w.rank} className="flex items-center justify-between border-b border-card-border last:border-0 pb-2">
                <span className="flex items-center gap-3">
                  <span className="text-foreground/40 text-sm w-5">#{w.rank}</span>
                  <span className="font-semibold">[{w.tag}]</span>
                  <span className="text-foreground/80">{w.name}</span>
                </span>
                <span className="text-xs text-foreground/60">{w.label ?? "Ranked, no tier configured"} · {w.vote_count} votes</span>
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}

export default async function ClansPage() {
  const [weeklyPeriod, monthlyPeriod] = await Promise.all([getOpenPeriod("WEEKLY"), getOpenPeriod("MONTHLY")]);
  const [weeklyStandings, monthlyStandings, weeklyWinners, monthlyWinners] = await Promise.all([
    weeklyPeriod ? getStandings(weeklyPeriod.id) : Promise.resolve([]),
    monthlyPeriod ? getStandings(monthlyPeriod.id) : Promise.resolve([]),
    getLastWinners("WEEKLY"),
    getLastWinners("MONTHLY"),
  ]);

  return (
    <div className="max-w-6xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">Clan Leaderboard</h1>
      <p className="text-foreground/60 mb-10">
        Vote for your favorite clan in-game with <code>/clanvote</code>. One vote per player per period — you
        can&apos;t vote for your own clan. Standings and gifts are decided by vote count alone, applied
        automatically once voting closes.
      </p>

      <div className="grid md:grid-cols-2 gap-6 mb-10">
        <Standings title="Weekly — current standings" periodEnd={weeklyPeriod?.period_end ?? null} standings={weeklyStandings} />
        <Standings title="Monthly — current standings" periodEnd={monthlyPeriod?.period_end ?? null} standings={monthlyStandings} />
      </div>

      <div className="grid md:grid-cols-2 gap-6">
        <LastWinners title="Last week's winners" data={weeklyWinners} />
        <LastWinners title="Last month's winners" data={monthlyWinners} />
      </div>
    </div>
  );
}
