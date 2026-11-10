import Link from "next/link";
import { query } from "@/lib/db";

async function getFeaturedProducts() {
  try {
    return await query<{ slug: string; name: string; description: string; price_pesewas: number; currency: string }>(
      `SELECT slug, name, description, price_pesewas, currency FROM products
       WHERE active = true AND featured = true ORDER BY sort_order ASC LIMIT 3`
    );
  } catch {
    return null; // DB unavailable - homepage still renders, per "show an appropriate unavailable state"
  }
}

export default async function Home() {
  const featured = await getFeaturedProducts();

  return (
    <div>
      <section className="max-w-6xl mx-auto px-4 py-20 text-center">
        <h1 className="text-5xl md:text-6xl font-extrabold tracking-tight">
          <span className="text-gr-red">Ghana</span>
          <span className="text-gr-gold">Realms</span>
        </h1>
        <p className="mt-4 text-lg text-foreground/80">A Ghanaian Minecraft Experience.</p>

        <div className="mt-8 card inline-block px-6 py-4 text-left">
          <p className="text-sm text-foreground/60">Server IP (Java Edition)</p>
          <p className="text-xl font-mono">play.ghanarealms.example</p>
          <p className="text-xs text-foreground/40 mt-1">Placeholder - set your real server address before launch.</p>
        </div>

        <div className="mt-8 flex flex-wrap gap-4 justify-center">
          <Link href="/store" className="px-6 py-3 rounded-lg bg-gr-gold text-gr-black font-semibold hover:opacity-90">
            Visit Store
          </Link>
          <a href="https://discord.gg/z5uwKstfp4" className="px-6 py-3 rounded-lg border border-card-border hover:border-gr-gold">
            Join Discord
          </a>
        </div>
      </section>

      <div className="kente-stripe" />

      <section className="max-w-6xl mx-auto px-4 py-16">
        <h2 className="text-2xl font-bold mb-6">Featured</h2>
        {featured === null && (
          <p className="text-foreground/60">Store is temporarily unavailable - please check back shortly.</p>
        )}
        {featured !== null && featured.length === 0 && (
          <p className="text-foreground/60">No featured products yet - check the full store.</p>
        )}
        {featured !== null && featured.length > 0 && (
          <div className="grid md:grid-cols-3 gap-6">
            {featured.map((p) => (
              <Link key={p.slug} href={`/store/${p.slug}`} className="card p-6 hover:border-gr-gold transition-colors">
                <h3 className="text-lg font-semibold">{p.name}</h3>
                <p className="text-sm text-foreground/60 mt-2 line-clamp-2">{p.description}</p>
                <p className="mt-4 text-gr-gold font-bold">
                  GH₵{(p.price_pesewas / 100).toFixed(2)}
                </p>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="max-w-6xl mx-auto px-4 pb-20">
        <h2 className="text-2xl font-bold mb-6">Server benefits</h2>
        <div className="grid md:grid-cols-3 gap-6 text-sm text-foreground/70">
          <div className="card p-6">
            <h3 className="font-semibold text-foreground mb-2">Strictly Ghanaian</h3>
            <p>Ghanaian-inspired worlds, architecture, and culture at the core of the server, not decoration on top of it.</p>
          </div>
          <div className="card p-6">
            <h3 className="font-semibold text-foreground mb-2">Secure payments</h3>
            <p>Checkout through Paystack - Mobile Money, cards, and other Ghanaian payment channels.</p>
          </div>
          <div className="card p-6">
            <h3 className="font-semibold text-foreground mb-2">Fair delivery</h3>
            <p>Purchases are queued and delivered automatically, even if you're offline when you buy.</p>
          </div>
        </div>
      </section>
    </div>
  );
}
