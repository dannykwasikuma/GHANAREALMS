import Link from "next/link";
import { query } from "@/lib/db";

async function getProducts() {
  try {
    return await query<{ slug: string; name: string; description: string; price_pesewas: number; category: string }>(
      `SELECT slug, name, description, price_pesewas, category FROM products
       WHERE active = true ORDER BY sort_order ASC, name ASC`
    );
  } catch {
    return null;
  }
}

export default async function StorePage() {
  const products = await getProducts();

  return (
    <div className="max-w-6xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">GhanaRealms Store</h1>
      <p className="text-foreground/60 mb-10">Ranks, cosmetics, and packages for GhanaRealms.</p>

      {products === null && (
        <p className="text-foreground/60">Store is temporarily unavailable - please check back shortly.</p>
      )}
      {products !== null && products.length === 0 && (
        <p className="text-foreground/60">No products are available right now.</p>
      )}
      {products !== null && products.length > 0 && (
        <div className="grid sm:grid-cols-2 md:grid-cols-3 gap-6">
          {products.map((p) => (
            <Link key={p.slug} href={`/store/${p.slug}`} className="card p-6 hover:border-gr-gold transition-colors flex flex-col">
              <span className="text-xs uppercase tracking-wide text-gr-green">{p.category}</span>
              <h3 className="text-lg font-semibold mt-1">{p.name}</h3>
              <p className="text-sm text-foreground/60 mt-2 flex-1 line-clamp-3">{p.description}</p>
              <p className="mt-4 text-gr-gold font-bold">GH₵{(p.price_pesewas / 100).toFixed(2)}</p>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
