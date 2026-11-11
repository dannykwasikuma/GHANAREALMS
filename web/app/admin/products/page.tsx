import { redirect } from "next/navigation";
import Link from "next/link";
import { getAdminSession } from "@/lib/admin-session";
import { query } from "@/lib/db";
import { CATEGORY_ORDER, CATEGORY_TITLE } from "@/lib/rarity";
import { ProductRow, type P } from "./ProductRow";

export default async function AdminProducts({ searchParams }: { searchParams: Promise<{ c?: string; q?: string }> }) {
  const session = await getAdminSession();
  if (!session) redirect("/admin/login");
  const { c, q } = await searchParams;

  const cats = await query<{ category: string; n: string }>(`SELECT category, count(*) AS n FROM products GROUP BY category`);
  cats.sort((a, b) => (CATEGORY_ORDER.indexOf(a.category) + 1 || 99) - (CATEGORY_ORDER.indexOf(b.category) + 1 || 99));
  const sel = cats.find((x) => x.category === c)?.category ?? (q ? null : cats[0]?.category);
  const rows = await query<P>(
    `SELECT id, name, slug, category, price_pesewas, ingame_price_minor, stock, purchase_limit_per_player, discount_percent, active, gameplay_only
     FROM products WHERE ($1::text IS NULL OR category = $1) AND ($2::text IS NULL OR name ILIKE '%' || $2 || '%' OR slug ILIKE '%' || $2 || '%')
     ORDER BY sort_order ASC, name ASC LIMIT 300`, [sel, q ?? null]);

  return (
    <div className="max-w-6xl mx-auto px-4 py-12">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Products &amp; Stock</h1>
        <Link href="/admin" className="text-sm text-foreground/60">&larr; Dashboard</Link>
      </div>
      <p className="text-sm text-foreground/60 mb-4">Edit prices (GH₵ and in-game coins), stock, per-player limits, discounts and visibility. Changes go live on the website immediately and reach the Minecraft shop at its next sync (≤ 5 min). Leave a price blank to stop selling in that currency.</p>
      <form className="mb-4"><input name="q" defaultValue={q ?? ""} placeholder="Search name or slug…" className="bg-transparent border border-card-border rounded px-3 py-1 text-sm w-72" /></form>
      <div className="flex flex-wrap gap-2 mb-6">
        {cats.map((x) => (
          <Link key={x.category} href={`/admin/products?c=${x.category}`} className={`pill ${x.category === sel ? "pill-paid" : ""}`}>{CATEGORY_TITLE[x.category] ?? x.category} ({x.n})</Link>
        ))}
      </div>
      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="text-left text-foreground/50 border-b border-card-border">
            <th className="p-2">Product</th><th className="p-2">GH₵</th><th className="p-2">Coins</th><th className="p-2">Stock</th><th className="p-2">Limit/player</th><th className="p-2">Discount</th><th className="p-2">Live</th><th className="p-2"></th>
          </tr></thead>
          <tbody>{rows.map((p) => <ProductRow key={p.id} p={p} />)}</tbody>
        </table>
      </div>
      <p className="text-xs text-foreground/40 mt-4">Add a product via POST /api/admin/products (see docs/CATALOGUE.md). Image upload needs object storage (not set up) - set image_url to a hosted image.</p>
    </div>
  );
}
