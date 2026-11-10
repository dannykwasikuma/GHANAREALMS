import { queryOne } from "@/lib/db";
import { notFound } from "next/navigation";
import Link from "next/link";

async function getProduct(slug: string) {
  return queryOne<{
    slug: string; name: string; description: string; price_pesewas: number;
    category: string; duration_days: number | null;
  }>(
    `SELECT slug, name, description, price_pesewas, category, duration_days
     FROM products WHERE slug = $1 AND active = true`,
    [slug]
  );
}

export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const product = await getProduct(slug);
  if (!product) notFound();

  return (
    <div className="max-w-2xl mx-auto px-4 py-16">
      <Link href="/store" className="text-sm text-foreground/60 hover:text-gr-gold">&larr; Back to store</Link>
      <span className="block text-xs uppercase tracking-wide text-gr-green mt-4">{product.category}</span>
      <h1 className="text-3xl font-bold mt-1">{product.name}</h1>
      <p className="text-foreground/70 mt-4 whitespace-pre-line">{product.description}</p>

      <div className="card p-6 mt-8 flex items-center justify-between">
        <div>
          <p className="text-2xl font-bold text-gr-gold">GH₵{(product.price_pesewas / 100).toFixed(2)}</p>
          <p className="text-xs text-foreground/50">
            {product.duration_days ? `${product.duration_days} day duration` : "Permanent"}
          </p>
        </div>
        <Link
          href={`/checkout?product=${product.slug}`}
          className="px-6 py-3 rounded-lg bg-gr-gold text-gr-black font-semibold hover:opacity-90"
        >
          Buy Now
        </Link>
      </div>
    </div>
  );
}
