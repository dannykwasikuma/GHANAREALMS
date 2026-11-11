import { redirect } from "next/navigation";
import Link from "next/link";
import { getPlayerSession, getVerifiedMinecraftPlayer } from "@/lib/player-auth";
import { query } from "@/lib/db";

function statusPill(status: string) {
  const s = status.toUpperCase();
  const cls = s === "PAID" ? "pill-paid" : s === "FAILED" || s === "CANCELLED" ? "pill-failed" : "pill-pending";
  return <span className={`pill ${cls}`}>{s}</span>;
}

export default async function AccountPage() {
  const session = await getPlayerSession();
  if (!session) redirect("/login");

  const linkedPlayer = await getVerifiedMinecraftPlayer(session.userId);

  const orders = linkedPlayer
    ? await query<{
        order_number: string;
        status: string;
        created_at: string;
        product_name: string;
      }>(
        `SELECT o.order_number, o.status, o.created_at, p.name AS product_name
         FROM orders o
         JOIN order_items oi ON oi.order_id = o.id
         JOIN products p ON p.id = oi.product_id
         WHERE o.minecraft_player_id = $1
         ORDER BY o.created_at DESC`,
        [linkedPlayer.id],
      )
    : [];

  return (
    <div className="max-w-2xl mx-auto px-4 py-16 space-y-6">
      <div className="flex items-center gap-4">
        <div className="auth-badge !mb-0">🇬🇭</div>
        <div>
          <h1 className="text-2xl font-bold">Your Account</h1>
          <p className="text-sm opacity-60">{session.email}</p>
        </div>
      </div>

      <section className="card auth-card">
        <h2 className="font-semibold mb-3 flex items-center gap-2">
          <span>⛏️</span> Minecraft Account
        </h2>
        {linkedPlayer ? (
          <p className="text-sm leading-relaxed">
            Linked to <span className="text-gr-gold font-semibold">{linkedPlayer.username}</span>.
            Rewards from purchases are delivered here automatically.
          </p>
        ) : (
          <div className="space-y-3">
            <p className="text-sm opacity-70 leading-relaxed">
              Link your Minecraft account to GhanaRealms before completing your purchase.
              This ensures your rewards are delivered to the correct player.
            </p>
            <Link href="/account/link" className="btn-primary inline-block w-auto px-5">
              Link Minecraft Account
            </Link>
          </div>
        )}
      </section>

      <section className="card auth-card">
        <h2 className="font-semibold mb-3 flex items-center gap-2">
          <span>🧾</span> Purchase History
        </h2>
        {orders.length === 0 ? (
          <p className="text-sm opacity-60">No purchases yet.</p>
        ) : (
          <ul className="divide-y divide-card-border">
            {orders.map((o) => (
              <li key={o.order_number} className="flex items-center justify-between text-sm py-3 first:pt-0 last:pb-0">
                <span className="font-medium">{o.product_name}</span>
                <span className="opacity-50 font-mono text-xs">{o.order_number}</span>
                {statusPill(o.status)}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
