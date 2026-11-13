"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";

function CheckoutFormInner({ minecraftUsername }: { minecraftUsername: string }) {
  const params = useSearchParams();
  const productSlug = params.get("product") ?? "";
  const [agreed, setAgreed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!agreed) {
      setError("You must agree to the Terms of Service and Refund Policy.");
      return;
    }
    setLoading(true);
    try {
      const res = await fetch("/api/checkout/init", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // No minecraftUsername field anymore - the backend uses the
        // signed-in, verified-linked player from the session, never a
        // value typed into this form. See Document 21, section 4.
        body: JSON.stringify({ productSlug, agreedToTerms: true }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Something went wrong.");
        setLoading(false);
        return;
      }
      window.location.href = data.authorizationUrl;
    } catch {
      setError("Network error - please try again.");
      setLoading(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-badge">🛒</div>
      <h1 className="text-2xl font-bold mb-6">Checkout</h1>
      <form onSubmit={handleSubmit} className="card auth-card space-y-4">
        <div>
          <label className="field-label">Product</label>
          <input value={productSlug} disabled className="field-input" />
        </div>
        <div>
          <label className="field-label">Delivering to</label>
          <input value={minecraftUsername} disabled className="field-input" />
        </div>
        <label className="flex items-start gap-2 text-sm text-foreground/70">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-1 accent-gr-gold"
          />
          <span>
            I agree to the GhanaRealms{" "}
            <a href="/terms" target="_blank" className="text-gr-gold underline">Terms of Service</a>{" "}
            and{" "}
            <a href="/refund-policy" target="_blank" className="text-gr-gold underline">Refund Policy</a>.
          </span>
        </label>
        {error && <p className="alert-error">{error}</p>}
        <button type="submit" disabled={loading} className="btn-primary gold">
          {loading ? "Starting checkout..." : "Continue to Paystack"}
        </button>
      </form>
    </div>
  );
}

export default function CheckoutForm({ minecraftUsername }: { minecraftUsername: string }) {
  return (
    <Suspense fallback={<div className="auth-shell">Loading...</div>}>
      <CheckoutFormInner minecraftUsername={minecraftUsername} />
    </Suspense>
  );
}
