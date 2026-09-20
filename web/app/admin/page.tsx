import { redirect } from "next/navigation";
import { getAdminSession } from "@/lib/admin-session";
import { query } from "@/lib/db";

async function getStats() {
  const [orders, payments, deliveries, events] = await Promise.all([
    query<{ status: string; count: string }>('SELECT status, count(*) FROM orders GROUP BY status'),
    query<{ status: string; total: string }>(
      "SELECT status, coalesce(sum(amount_pesewas),0) AS total FROM payments GROUP BY status"
    ),
    query<{ status: string; count: string }>('SELECT status, count(*) FROM delivery_queue GROUP BY status'),
    query<{ id: string; paystack_reference: string; event_type: string; processed: boolean; received_at: string }>(
      'SELECT id, paystack_reference, event_type, processed, received_at FROM payment_events ORDER BY received_at DESC LIMIT 10'
    ),
  ]);
  return { orders, payments, deliveries, events };
}

export default async function AdminDashboard() {
  const session = await getAdminSession();
  if (!session) redirect("/admin/login");

  const { orders, payments, deliveries, events } = await getStats();
  const paidTotal = payments.find((p) => p.status === "PAID")?.total ?? "0";

  return (
    <div className="max-w-5xl mx-auto px-4 py-12">
      <div className="flex items-center justify-between mb-8">
        <h1 className="text-2xl font-bold">Admin Dashboard</h1>
        <p className="text-sm text-foreground/60">Signed in as {session.username} ({session.role})</p>
      </div>

      <div className="grid md:grid-cols-3 gap-6 mb-10">
        <Card title="Total Paid (all time)" value={`GH₵${(Number(paidTotal) / 100).toFixed(2)}`} />
        <Card title="Orders" value={orders.reduce((a, o) => a + Number(o.count), 0).toString()}
              sub={orders.map((o) => `${o.status}: ${o.count}`).join(" · ")} />
        <Card title="Deliveries" value={deliveries.reduce((a, d) => a + Number(d.count), 0).toString()}
              sub={deliveries.map((d) => `${d.status}: ${d.count}`).join(" · ")} />
      </div>

      <h2 className="text-lg font-semibold mb-3">Recent Webhook Events</h2>
      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-foreground/50 border-b border-card-border">
              <th className="p-3">Reference</th>
              <th className="p-3">Event</th>
              <th className="p-3">Processed</th>
              <th className="p-3">Received</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id} className="border-b border-card-border last:border-0">
                <td className="p-3 font-mono">{e.paystack_reference}</td>
                <td className="p-3">{e.event_type}</td>
                <td className="p-3">{e.processed ? "✓" : "…"}</td>
                <td className="p-3 text-foreground/60">{new Date(e.received_at).toLocaleString()}</td>
              </tr>
            ))}
            {events.length === 0 && (
              <tr><td colSpan={4} className="p-3 text-foreground/50">No webhook events yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <p className="text-xs text-foreground/40 mt-8">
        This is a scoped MVP admin view (dashboard + webhook log) - product/order
        CRUD editing, refund actions, and audit-log browsing from section 25/26
        are not built yet. See STATUS.md.
      </p>
    </div>
  );
}

function Card({ title, value, sub }: { title: string; value: string; sub?: string }) {
  return (
    <div className="card p-6">
      <p className="text-sm text-foreground/50">{title}</p>
      <p className="text-2xl font-bold mt-1">{value}</p>
      {sub && <p className="text-xs text-foreground/40 mt-2">{sub}</p>}
    </div>
  );
}
