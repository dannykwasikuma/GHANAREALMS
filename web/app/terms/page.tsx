export default function TermsPage() {
  return (
    <div className="max-w-2xl mx-auto px-4 py-16 prose-sm">
      <h1 className="text-3xl font-bold mb-2">Terms of Service</h1>
      <p className="text-foreground/50 text-sm mb-8">Version: v1-2026 &middot; Last updated: [SET DATE BEFORE LAUNCH]</p>

      <div className="space-y-6 text-foreground/80 leading-relaxed">
        <p className="card p-4 text-sm text-gr-gold">
          PLACEHOLDER DOCUMENT: The bracketed items below need real information
          before this goes live - operator identity, jurisdiction, and support
          contact. Nothing here should be treated as final legal text until
          those are filled in and (ideally) reviewed by someone qualified to
          advise on Ghanaian consumer/e-commerce law.
        </p>

        <section>
          <h2 className="text-xl font-semibold text-foreground">1. About GhanaRealms</h2>
          <p>GhanaRealms ("we", "us", "the server") is a Minecraft: Java Edition server and associated store operated by [OPERATOR NAME / ENTITY - PLACEHOLDER], based in [LOCATION - PLACEHOLDER]. GhanaRealms is not affiliated with, endorsed by, or connected to Mojang Studios or Microsoft.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">2. What you're buying</h2>
          <p>Purchases through this store are digital in-game benefits (ranks, cosmetics, packages) tied to a specific Minecraft Java Edition account. They have no cash value, cannot be exchanged for real money, and are not transferable between accounts.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">3. Delivery</h2>
          <p>Purchases are queued automatically on payment confirmation and delivered when your account next joins the server (or immediately if already online). See our <a href="/refund-policy" className="text-gr-gold underline">Refund Policy</a> for what happens if delivery fails.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">4. Account standing</h2>
          <p>Purchases do not exempt you from the server's rules. A rank or item purchased before a ban or rule-violation penalty is not automatically refunded or restored - see the Refund Policy for exceptions.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">5. Payment processing</h2>
          <p>Payments are processed by Paystack. We never see or store your full card or Mobile Money details - Paystack handles that directly. See our <a href="/privacy" className="text-gr-gold underline">Privacy Policy</a> for what we do store.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">6. Changes</h2>
          <p>We may update these Terms. Material changes will be reflected in a new version number; your acceptance is recorded per-order against the version current at the time of purchase.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">7. Contact</h2>
          <p>support@[YOUR-DOMAIN - PLACEHOLDER] &middot; Discord: <a href="https://discord.gg/z5uwKstfp4" className="text-gr-gold underline">discord.gg/z5uwKstfp4</a></p>
        </section>
      </div>
    </div>
  );
}
