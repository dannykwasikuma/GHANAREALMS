import Link from "next/link";
import { query } from "@/lib/db";
import { CATEGORY_ORDER, CATEGORY_TITLE, RARITY_NAME, RARITY_STYLE, RARITY_ORDER, fmtCedi, fmtCoins } from "@/lib/rarity";

type Row = {
  slug: string; name: string; description: string; category: string; rarity: string | null;
  price_pesewas: number | null; ingame_price_minor: number | null; gameplay_only: boolean;
  stock: number | null; purchase_limit_per_player: number | null; limit_window_days: number | null;
};

// The whole catalogue (web + in-game prices). One category per page view keeps the page light.
export default async function CataloguePage({ searchParams }: { searchParams: Promise<{ c?: string }> }) {
  const { c } = await searchParams;
  let cats: { category: string; n: string }[] = [];
  let rows: Row[] = [];
  let down = false;
  try {
    cats = await query(`SELECT category, count(*) AS n FROM products WHERE active = true GROUP BY category`);
    cats.sort((a, b) => (CATEGORY_ORDER.indexOf(a.category) + 1 || 99) - (CATEGORY_ORDER.indexOf(b.category) + 1 || 99));
    const sel = cats.find((x) => x.category === c)?.category ?? cats[0]?.category;
    if (sel) {
      rows = await query<Row>(
        `SELECT slug, name, description, category, rarity, price_pesewas, ingame_price_minor, gameplay_only, stock,
                purchase_limit_per_player, limit_window_days
         FROM products WHERE active = true AND category = $1
           AND (available_from IS NULL OR available_from <= now()) AND (available_until IS NULL OR available_until >= now())
         ORDER BY sort_order ASC, name ASC`, [sel]);
      rows.sort((a, b) => ((RARITY_ORDER as readonly string[]).indexOf(a.rarity ?? "") + 1 || 99) - ((RARITY_ORDER as readonly string[]).indexOf(b.rarity ?? "") + 1 || 99));
    }
  } catch { down = true; }
  const current = cats.find((x) => x.category === c)?.category ?? cats[0]?.category;

  return (
    <div className="max-w-6xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">GhanaRealms Catalogue</h1>
      <p className="text-foreground/60 mb-6">Everything sold on the server. In-game prices are in coins (use <code>/gshop</code>); GH₵ prices are for the web store.</p>
      {down && <p className="text-foreground/60">Catalogue is temporarily unavailable.</p>}
      <div className="flex flex-wrap gap-2 mb-8">
        {cats.map((x) => (
          <Link key={x.category} href={`/catalogue?c=${x.category}`} className={`pill ${x.category === current ? "pill-paid" : ""}`}>
            {CATEGORY_TITLE[x.category] ?? x.category} ({x.n})
          </Link>
        ))}
      </div>
      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead><tr className="text-left text-foreground/50 border-b border-card-border">
            <th className="p-3">Item</th><th className="p-3">Rarity</th><th className="p-3">In-game</th><th className="p-3">Web (GH₵)</th><th className="p-3">Limits</th>
          </tr></thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.slug} className="border-b border-card-border last:border-0 align-top">
                <td className="p-3"><Link href={`/store/${p.slug}`} className="font-semibold hover:text-gr-gold">{p.name}</Link>
                  <p className="text-xs text-foreground/50">{p.description}</p></td>
                <td className={`p-3 ${p.rarity ? RARITY_STYLE[p.rarity] : ""}`}>{p.rarity ? RARITY_NAME[p.rarity] : "-"}</td>
                <td className="p-3">{p.gameplay_only ? "Gameplay only" : p.ingame_price_minor != null ? fmtCoins(p.ingame_price_minor) : "-"}</td>
                <td className="p-3">{p.price_pesewas != null ? fmtCedi(p.price_pesewas) : "-"}</td>
                <td className="p-3 text-xs text-foreground/60">
                  {p.purchase_limit_per_player ? `${p.purchase_limit_per_player} per player${p.limit_window_days ? ` / ${p.limit_window_days}d` : ""}` : ""}
                  {p.stock !== null ? ` ${p.stock} in stock` : ""}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
