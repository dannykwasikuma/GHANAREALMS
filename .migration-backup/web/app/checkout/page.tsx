"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";

function CheckoutForm() {
  const params = useSearchParams();
  const productSlug = params.get("product") ?? "";
  const [username, setUsername] = useState("");
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
        body: JSON.stringify({
          productSlug,
          minecraftUsername: username,
          agreedToTerms: true,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Something went wrong.");
        setLoading(false);
        return;
      }
      // The backend has already created the order/payment rows and
      // initialized a real Paystack transaction - this redirect is the
      // ONLY thing the browser does; it never marks anything paid itself.
      window.location.href = data.authorizationUrl;
    } catch {
      setError("Network error - please try again.");
      setLoading(false);
    }
  }

  return (
    <div className="max-w-md mx-auto px-4 py-16">
      <h1 className="text-2xl font-bold mb-6">Checkout</h1>
      <form onSubmit={handleSubmit} className="card p-6 space-y-4">
        <div>
          <label className="block text-sm mb-1">Product</label>
          <input value={productSlug} disabled className="w-full bg-transparent border border-card-border rounded-lg px-3 py-2 text-foreground/60" />
        </div>
        <div>
          <label className="block text-sm mb-1">Minecraft Username</label>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            minLength={3}
            maxLength={16}
            placeholder="Your exact Java Edition username"
            className="w-full bg-transparent border border-card-border rounded-lg px-3 py-2"
          />
        </div>
        <label className="flex items-start gap-2 text-sm text-foreground/70">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} className="mt-1" />
          <span>
            I agree to the GhanaRealms{" "}
            <a href="/terms" target="_blank" className="text-gr-gold underline">Terms of Service</a>{" "}
            and{" "}
            <a href="/refund-policy" target="_blank" className="text-gr-gold underline">Refund Policy</a>.
          </span>
        </label>
        {error && <p className="text-gr-red text-sm">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full py-3 rounded-lg bg-gr-gold text-gr-black font-semibold hover:opacity-90 disabled:opacity-50"
        >
          {loading ? "Starting checkout..." : "Continue to Paystack"}
        </button>
      </form>
    </div>
  );
}

export default function CheckoutPage() {
  return (
    <Suspense fallback={<div className="max-w-md mx-auto px-4 py-16">Loading...</div>}>
      <CheckoutForm />
    </Suspense>
  );
}
