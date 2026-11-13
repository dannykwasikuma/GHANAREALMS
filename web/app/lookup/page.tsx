"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";

interface LookupResult {
  orderNumber: string;
  product: string;
  minecraftUsername: string;
  paymentStatus: string;
  deliveryStatus: string;
  purchaseDate: string;
}

function LookupForm() {
  const params = useSearchParams();
  const [orderNumber, setOrderNumber] = useState(params.get("order") ?? "");
  const [result, setResult] = useState<LookupResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function doLookup(value: string) {
    if (!value) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await fetch(`/api/lookup?order=${encodeURIComponent(value)}`);
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Order not found.");
      } else {
        setResult(data);
      }
    } catch {
      setError("Network error - please try again.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (params.get("order")) doLookup(params.get("order")!);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="max-w-md mx-auto px-4 py-16">
      <h1 className="text-2xl font-bold mb-6">Order Lookup</h1>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          doLookup(orderNumber);
        }}
        className="card p-6 space-y-4"
      >
        <div>
          <label className="block text-sm mb-1">Order Number</label>
          <input
            value={orderNumber}
            onChange={(e) => setOrderNumber(e.target.value)}
            placeholder="GR-XXXXXXXX"
            className="w-full bg-transparent border border-card-border rounded-lg px-3 py-2 font-mono"
          />
        </div>
        <button type="submit" disabled={loading} className="w-full py-3 rounded-lg bg-gr-gold text-gr-black font-semibold hover:opacity-90 disabled:opacity-50">
          {loading ? "Looking up..." : "Look up order"}
        </button>
      </form>

      {error && <p className="text-gr-red text-sm mt-4">{error}</p>}

      {result && (
        <div className="card p-6 mt-6 space-y-2 text-sm">
          <Row label="Order" value={result.orderNumber} />
          <Row label="Product" value={result.product} />
          <Row label="Minecraft Username" value={result.minecraftUsername} />
          <Row label="Payment Status" value={result.paymentStatus} />
          <Row label="Delivery Status" value={result.deliveryStatus} />
          <Row label="Purchase Date" value={new Date(result.purchaseDate).toLocaleString()} />
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between border-b border-card-border pb-2 last:border-0">
      <span className="text-foreground/50">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}

export default function LookupPage() {
  return (
    <Suspense fallback={<div className="max-w-md mx-auto px-4 py-16">Loading...</div>}>
      <LookupForm />
    </Suspense>
  );
}
