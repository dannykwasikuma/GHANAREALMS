export default function PrivacyPage() {
  return (
    <div className="max-w-2xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">Privacy Policy</h1>
      <p className="text-foreground/50 text-sm mb-8">Version: v1-2026 &middot; Last updated: [SET DATE BEFORE LAUNCH]</p>

      <div className="space-y-6 text-foreground/80 leading-relaxed">
        <p className="card p-4 text-sm text-gr-gold">
          PLACEHOLDER DOCUMENT: operator identity and jurisdiction fields need
          real values before launch. This describes what the store code
          actually does with data (verified against this build), not
          aspirational policy.
        </p>

        <section>
          <h2 className="text-xl font-semibold text-foreground">What we collect</h2>
          <ul className="list-disc pl-6 space-y-1">
            <li>Your Minecraft username and UUID (looked up from Mojang's public API)</li>
            <li>Order and payment records (amount, status, timestamp, Paystack transaction reference)</li>
            <li>A placeholder email address we generate for Paystack (we do not collect a real email unless a future feature explicitly asks for one)</li>
          </ul>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">What we don&apos;t collect</h2>
          <p>We never receive or store your card number, Mobile Money PIN, or any full payment credential - Paystack handles that directly, and their secret key never leaves our backend server.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">Who we share it with</h2>
          <p>Paystack (to process payment) and, internally, our Minecraft server (to deliver what you purchased). We do not sell data to third parties.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">Data retention</h2>
          <p>Order and payment records are kept indefinitely for support, dispute, and audit purposes, consistent with standard e-commerce recordkeeping practice.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">Contact</h2>
          <p>support@[YOUR-DOMAIN - PLACEHOLDER]</p>
        </section>
      </div>
    </div>
  );
}
