import Link from "next/link";

async function getStatus(reference: string) {
  const base = process.env.STORE_BASE_URL ?? "";
  try {
    const res = await fetch(`${base}/api/payments/verify?reference=${encodeURIComponent(reference)}`, {
      cache: "no-store",
    });
    return await res.json();
  } catch {
    return { state: "UNKNOWN" };
  }
}

const STATE_COPY: Record<string, { title: string; body: string; color: string }> = {
  PAID: {
    title: "Payment confirmed",
    body: "Your purchase is on its way. If you're online, it should arrive within a minute or two; if you're offline, it'll be waiting when you join.",
    color: "text-gr-green",
  },
  PENDING: {
    title: "Payment processing",
    body: "We're still confirming this with Paystack. This page does not grant your purchase itself - refresh in a moment, or check /lookup with your order number.",
    color: "text-gr-gold",
  },
  FAILED: {
    title: "Payment failed",
    body: "This transaction was not successful. No charge should have gone through - nothing was delivered.",
    color: "text-gr-red",
  },
  CANCELLED: {
    title: "Payment cancelled",
    body: "You cancelled this payment. Nothing was charged, nothing was delivered.",
    color: "text-gr-red",
  },
  UNKNOWN: {
    title: "Unknown status",
    body: "We couldn't determine the status of this payment right now. Please check /lookup with your order number, or contact support.",
    color: "text-foreground/60",
  },
};

export default async function CallbackPage({
  searchParams,
}: {
  searchParams: Promise<{ reference?: string }>;
}) {
  const { reference } = await searchParams;
  const result = reference ? await getStatus(reference) : { state: "UNKNOWN" };
  const copy = STATE_COPY[result.state] ?? STATE_COPY.UNKNOWN;

  return (
    <div className="max-w-md mx-auto px-4 py-20 text-center">
      <h1 className={`text-2xl font-bold ${copy.color}`}>{copy.title}</h1>
      <p className="text-foreground/70 mt-4">{copy.body}</p>
      {result.orderNumber && (
        <p className="mt-6 card inline-block px-4 py-2 font-mono text-sm">
          Order: {result.orderNumber}
        </p>
      )}
      <div className="mt-8 flex gap-4 justify-center text-sm">
        <Link href="/store" className="text-gr-gold hover:underline">Back to store</Link>
        {result.orderNumber && (
          <Link href={`/lookup?order=${result.orderNumber}`} className="text-gr-gold hover:underline">
            View order status
          </Link>
        )}
      </div>
    </div>
  );
}
