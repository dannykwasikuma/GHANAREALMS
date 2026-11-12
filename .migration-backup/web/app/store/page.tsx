import Link from "next/link";
import { query } from "@/lib/db";
import { RARITY_ORDER, RARITY_NAME, RARITY_STYLE, CATEGORY_ORDER, CATEGORY_TITLE, fmtCedi } from "@/lib/rarity";
import { effectivePrice } from "@/lib/catalogue";

type Product = {
  slug: string; name: string; description: string; price_pesewas: number; category: string; rarity: string | null;
  featured: boolean; stock: number | null; discount_percent: number; discount_ends_at: string | null; season: string | null;
  purchase_limit_per_player: number | null;
};

// Only products sold for real money, active, in their availability window, not gameplay-only.
async function getProducts() {
  try {
    return await query<Product>(
      `SELECT slug, name, description, price_pesewas, category, rarity, featured, stock, discount_percent,
              discount_ends_at, season, purchase_limit_per_player
       FROM products
       WHERE active = true AND gameplay_only = false AND price_pesewas IS NOT NULL
         AND (available_from IS NULL OR available_from <= now())
         AND (available_until IS NULL OR available_until >= now())
       ORDER BY sort_order ASC, name ASC`
    );
  } catch {
    return null;
  }
}

const rarityRank = (r: string | null) => (r ? (RARITY_ORDER as readonly string[]).indexOf(r) : 99);

export default async function StorePage() {
  const products = await getProducts();
  const groups = new Map<string, Product[]>();
  for (const p of products ?? []) {
    if (!groups.has(p.category)) groups.set(p.category, []);
    groups.get(p.category)!.push(p);
  }
  for (const list of groups.values()) list.sort((a, b) => rarityRank(a.rarity) - rarityRank(b.rarity));
  const cats = [...groups.keys()].sort((a, b) => {
    const ia = CATEGORY_ORDER.indexOf(a), ib = CATEGORY_ORDER.indexOf(b);
    return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib) || a.localeCompare(b);
  });
  const deal = (products ?? []).find((p) => p.featured);

  return (
    <div className="max-w-6xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">GhanaRealms Store</h1>
      <p className="text-foreground/60 mb-2">Akwaaba! Real-money items, paid in GH₵ via MoMo or card.</p>
      <p className="text-xs text-foreground/50 mb-10">
        Real money buys cosmetics, food and starter bundles only. Gear, rare items and enchantments are earned or
        bought in-game with coins - see the <Link href="/catalogue" className="underline">full catalogue</Link>.
      </p>

      {products === null && <p className="text-foreground/60">Store is temporarily unavailable - please check back shortly.</p>}
      {products !== null && products.length === 0 && <p className="text-foreground/60">No products are available right now.</p>}

      {deal && (
        <Link href={`/store/${deal.slug}`} className="card p-6 mb-12 border-gr-gold flex flex-col md:flex-row md:items-center md:justify-between gap-2 hover:opacity-90">
          <div>
            <span className="text-xs uppercase tracking-wide text-gr-gold">{deal.season ?? "Deal of the Week"}</span>
            <h2 className="text-xl font-bold">{deal.name}</h2>
            <p className="text-sm text-foreground/60">{deal.description}</p>
          </div>
          <PriceTag p={deal} big />
        </Link>
      )}

      {cats.map((cat) => (
        <section key={cat} className="mb-12">
          <h2 className="text-xl font-semibold mb-4">{CATEGORY_TITLE[cat] ?? cat}</h2>
          <div className="grid sm:grid-cols-2 md:grid-cols-3 gap-6">
            {groups.get(cat)!.map((p) => (
              <Link key={p.slug} href={`/store/${p.slug}`} className="card p-6 hover:border-gr-gold transition-colors flex flex-col">
                <span className={`text-xs uppercase tracking-wide ${p.rarity ? RARITY_STYLE[p.rarity] : "text-gr-green"}`}>
                  {p.season ?? (p.rarity ? RARITY_NAME[p.rarity] : p.category)}
                </span>
                <h3 className="text-lg font-semibold mt-1">{p.name}</h3>
                <p className="text-sm text-foreground/60 mt-2 flex-1 line-clamp-3">{p.description}</p>
                <div className="mt-4 flex items-center justify-between">
                  <PriceTag p={p} />
                  {p.stock !== null && <span className="text-xs text-foreground/50">{p.stock === 0 ? "Sold out" : `${p.stock} left`}</span>}
                </div>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function PriceTag({ p, big }: { p: Product; big?: boolean }) {
  const now = effectivePrice(p.price_pesewas, p.discount_percent, p.discount_ends_at);
  const discounted = now !== p.price_pesewas;
  return (
    <p className={`text-gr-gold font-bold ${big ? "text-2xl" : ""}`}>
      {discounted && <span className="text-foreground/40 line-through mr-2 font-normal">{fmtCedi(p.price_pesewas)}</span>}
      {fmtCedi(now!)}
    </p>
  );
}
