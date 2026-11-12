import { redirect } from "next/navigation";
import { getAdminSession } from "@/lib/admin-session";
import { query } from "@/lib/db";
import { SettleButton } from "./SettleButton";

interface PeriodRow {
  id: string;
  period_type: "WEEKLY" | "MONTHLY";
  period_start: string;
  period_end: string;
  status: "OPEN" | "SETTLED";
  reward_tier_count: number;
  vote_count: string;
}

async function getPeriods() {
  return query<PeriodRow>(
    `SELECT p.id, p.period_type, p.period_start, p.period_end, p.status, p.reward_tier_count,
            count(v.id) AS vote_count
     FROM clan_vote_periods p
     LEFT JOIN clan_votes v ON v.period_id = p.id
     GROUP BY p.id
     ORDER BY p.period_start DESC
     LIMIT 20`
  );
}

async function getTierCounts() {
  return query<{ period_type: string; n: string }>(
    `SELECT period_type, count(*) AS n FROM clan_reward_tiers GROUP BY period_type`
  );
}

export default async function AdminClansPage() {
  const session = await getAdminSession();
  if (!session) redirect("/admin/login");

  const [periods, tierCounts] = await Promise.all([getPeriods(), getTierCounts()]);
  const weeklyTiers = Number(tierCounts.find((t) => t.period_type === "WEEKLY")?.n ?? 0);
  const monthlyTiers = Number(tierCounts.find((t) => t.period_type === "MONTHLY")?.n ?? 0);

  return (
    <div className="max-w-5xl mx-auto px-4 py-12">
      <div className="flex items-center justify-between mb-8">
        <h1 className="text-2xl font-bold">Clan Voting</h1>
        <p className="text-sm text-foreground/60">Signed in as {session.username} ({session.role})</p>
      </div>

      {(weeklyTiers === 0 || monthlyTiers === 0) && (
        <div className="card p-4 mb-6 border-amber-500/50 text-amber-600 text-sm">
          {weeklyTiers === 0 && <p>No WEEKLY reward tiers are configured — settled weekly periods will rank clans but gift no one until clan_reward_tiers has WEEKLY rows.</p>}
          {monthlyTiers === 0 && <p>No MONTHLY reward tiers are configured — same issue for monthly periods.</p>}
          <p className="mt-1 text-foreground/60">Insert rows into <code>clan_reward_tiers</code> (see migrations/003_clan_voting.sql for the shape) — there's no admin form for this yet, same as products.</p>
        </div>
      )}

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-foreground/50 border-b border-card-border">
              <th className="p-3">Type</th>
              <th className="p-3">Window</th>
              <th className="p-3">Status</th>
              <th className="p-3">Votes cast</th>
              <th className="p-3">Rewards (top N)</th>
              <th className="p-3"></th>
            </tr>
          </thead>
          <tbody>
            {periods.map((p) => {
              const ended = new Date(p.period_end) <= new Date();
              return (
                <tr key={p.id} className="border-b border-card-border last:border-0">
                  <td className="p-3">{p.period_type}</td>
                  <td className="p-3 text-foreground/60">
                    {new Date(p.period_start).toLocaleDateString()} – {new Date(p.period_end).toLocaleDateString()}
                  </td>
                  <td className="p-3">
                    <span className={`pill ${p.status === "SETTLED" ? "pill-paid" : ""}`}>{p.status}</span>
                  </td>
                  <td className="p-3">{p.vote_count}</td>
                  <td className="p-3">{p.reward_tier_count}</td>
                  <td className="p-3 text-right">
                    {p.status === "OPEN" && (
                      <SettleButton periodId={p.id} disabled={!ended} />
                    )}
                    {p.status === "OPEN" && !ended && (
                      <p className="text-xs text-foreground/40 mt-1">Closes {new Date(p.period_end).toLocaleString()}</p>
                    )}
                  </td>
                </tr>
              );
            })}
            {periods.length === 0 && (
              <tr><td colSpan={6} className="p-3 text-foreground/50">No voting periods yet — one is created automatically the first time a vote is cast.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <p className="text-xs text-foreground/40 mt-6">
        Settlement is fully mechanical — ranking is vote count only, gifts come from pre-configured
        tiers by rank band, and a period can only be settled once its window has actually closed.
        See docs/CLAN_VOTING.md.
      </p>
    </div>
  );
}
