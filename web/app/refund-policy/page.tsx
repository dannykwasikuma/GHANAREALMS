export default function RefundPolicyPage() {
  return (
    <div className="max-w-2xl mx-auto px-4 py-16">
      <h1 className="text-3xl font-bold mb-2">Refund Policy</h1>
      <p className="text-foreground/50 text-sm mb-8">Version: v1-2026 &middot; Last updated: [SET DATE BEFORE LAUNCH]</p>

      <div className="space-y-6 text-foreground/80 leading-relaxed">
        <p className="card p-4 text-sm text-gr-gold">
          PLACEHOLDER DOCUMENT: refund windows/eligibility below are
          reasonable defaults, not finalized business decisions - review
          and adjust before launch.
        </p>

        <section>
          <h2 className="text-xl font-semibold text-foreground">Digital goods, generally final</h2>
          <p>Because purchases are delivered near-instantly as digital in-game benefits, all sales are generally final once delivered successfully.</p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">When we will refund</h2>
          <ul className="list-disc pl-6 space-y-1">
            <li>Payment was taken but delivery permanently failed (see our delivery-retry system) and could not be resolved by support</li>
            <li>You were charged more than once for the same order (a duplicate-charge error, not two separate intentional purchases)</li>
            <li>You entered the wrong Minecraft username and contact support before the item is delivered</li>
          </ul>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">When we generally will not refund</h2>
          <ul className="list-disc pl-6 space-y-1">
            <li>You changed your mind after successful delivery</li>
            <li>Your account was later banned or punished for breaking server rules</li>
            <li>You entered the wrong username and the item was already delivered to that account before you contacted support</li>
          </ul>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-foreground">How to request one</h2>
          <p>Contact support@[YOUR-DOMAIN - PLACEHOLDER] with your order number from <a href="/lookup" className="text-gr-gold underline">/lookup</a>. Refunds, where approved, are processed back through Paystack to your original payment method.</p>
        </section>
      </div>
    </div>
  );
}
