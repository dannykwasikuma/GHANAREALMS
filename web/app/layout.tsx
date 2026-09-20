import type { Metadata } from "next";
import "./globals.css";
import Link from "next/link";


export const metadata: Metadata = {
  title: "GhanaRealms Store",
  description: "The official GhanaRealms store - a Ghanaian Minecraft experience.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className={`antialiased`}>
        <div className="kente-stripe" />
        <header className="border-b border-card-border">
          <nav className="max-w-6xl mx-auto px-4 py-4 flex items-center justify-between">
            <Link href="/" className="text-xl font-bold">
              <span className="text-gr-red">Ghana</span>
              <span className="text-gr-gold">Realms</span>
            </Link>
            <div className="flex gap-6 text-sm">
              <Link href="/store" className="hover:text-gr-gold">Store</Link>
              <Link href="/lookup" className="hover:text-gr-gold">Order Lookup</Link>
            </div>
          </nav>
        </header>
        <main>{children}</main>
        <footer className="border-t border-card-border mt-24 py-10">
          <div className="max-w-6xl mx-auto px-4 text-sm text-foreground/60 flex flex-wrap gap-x-8 gap-y-2 justify-between">
            <p>&copy; {new Date().getFullYear()} GhanaRealms. Not affiliated with Mojang or Microsoft.</p>
            <div className="flex gap-6">
              <Link href="/terms" className="hover:text-gr-gold">Terms</Link>
              <Link href="/privacy" className="hover:text-gr-gold">Privacy</Link>
              <Link href="/refund-policy" className="hover:text-gr-gold">Refunds</Link>
            </div>
          </div>
        </footer>
      </body>
    </html>
  );
}
