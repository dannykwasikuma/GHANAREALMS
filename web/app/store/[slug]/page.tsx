import { queryOne } from "@/lib/db";
import { notFound } from "next/navigation";
import Link from "next/link";
import { RARITY_NAME, RARITY_STYLE, fmtCedi, fmtCoins } from "@/lib/rarity";
import { effectivePrice, inWindow } from "@/lib/catalogue";

async function getProduct(slug: string) {
  return queryOne<{
    slug: string; name: string; description: string; price_pesewas: number | null; ingame_price_minor: number | null;
    category: string; rarity: string | null; duration_days: number | null; stock: number | null;
    purchase_limit_per_player: number | null; limit_window_days: number | null; gameplay_only: boolean;
    discount_percent: number; discount_ends_at: string | null; available_from: string | null; available_until: string | null;
    item_specs: string[];
  }>(
    `SELECT slug, name, description, price_pesewas, ingame_price_minor, category, rarity, duration_days, stock,
            purchase_limit_per_player, limit_window_days, gameplay_only, discount_percent, discount_ends_at,
            available_from, available_until, item_specs
     FROM products WHERE slug = $1 AND active = true`,
    [slug]
  );
}

export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const product = await getProduct(slug);
  if (!product) notFound();

  const now = effectivePrice(product.price_pesewas, product.discount_percent, product.discount_ends_at);
  const buyable = !product.gameplay_only && product.price_pesewas != null && inWindow(product.available_from, product.available_until) && product.stock !== 0;
  const items = product.item_specs.map((s) => s.split(" ").slice(0, 2).join(" x").replace(/_/g, " "));

  return (
    <div className="max-w-2xl mx-auto px-4 py-16">
      <Link href="/store" className="text-sm text-foreground/60 hover:text-gr-gold">&larr; Back to store</Link>
      <span className={`block text-xs uppercase tracking-wide mt-4 ${product.rarity ? RARITY_STYLE[product.rarity] : "text-gr-green"}`}>
        {product.rarity ? RARITY_NAME[product.rarity] : product.category}
      </span>
      <h1 className="text-3xl font-bold mt-1">{product.name}</h1>
      <p className="text-foreground/70 mt-4 whitespace-pre-line">{product.description}</p>

      {items.length > 0 && (
        <div className="mt-6 text-sm text-foreground/70">
          <p className="font-semibold mb-1">Includes</p>
          <ul className="list-disc pl-5">{items.map((i, k) => <li key={k}>{i}</li>)}</ul>
        </div>
      )}

      <div className="card p-6 mt-8">
        {product.gameplay_only ? (
          <p className="font-semibold">Earned through gameplay only - not for sale.</p>
        ) : (
          <div className="flex items-center justify-between">
            <div>
              {now != null ? (
                <p className="text-2xl font-bold text-gr-gold">
                  {now !== product.price_pesewas && <span className="text-foreground/40 line-through mr-2 text-base font-normal">{fmtCedi(product.price_pesewas!)}</span>}
                  {fmtCedi(now)}
                </p>
              ) : (
                <p className="font-semibold">Not sold for real money</p>
              )}
              {product.ingame_price_minor != null && (
                <p className="text-sm text-foreground/60">or {fmtCoins(product.ingame_price_minor)} in-game (<code>/gshop</code>)</p>
              )}
              <p className="text-xs text-foreground/50 mt-1">
                {product.duration_days ? `${product.duration_days} day duration` : "Permanent"}
                {product.purchase_limit_per_player ? ` · limit ${product.purchase_limit_per_player} per player${product.limit_window_days ? ` per ${product.limit_window_days} days` : ""}` : ""}
                {product.stock !== null ? ` · ${product.stock === 0 ? "sold out" : `${product.stock} left`}` : ""}
              </p>
            </div>
            {buyable ? (
              <Link href={`/checkout?product=${product.slug}`} className="px-6 py-3 rounded-lg bg-gr-gold text-gr-black font-semibold hover:opacity-90">
                Buy Now
              </Link>
            ) : (
              <span className="text-sm text-foreground/50">{product.stock === 0 ? "Sold out" : "Not available for GH₵"}</span>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
