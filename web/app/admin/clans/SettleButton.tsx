"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

/**
 * Deliberately a dumb button with no inputs — settlement takes no
 * parameters from the admin (see settle/route.ts docblock for why: every
 * decision it makes is mechanical, not something an admin fills in at
 * settlement time).
 */
export function SettleButton({ periodId, disabled }: { periodId: string; disabled: boolean }) {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSettle() {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/admin/clans/periods/${periodId}/settle`, { method: "POST" });
      const body = await res.json();
      if (!res.ok) {
        setError(body.error ?? "Settlement failed");
        return;
      }
      router.refresh();
    } catch {
      setError("Network error");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        onClick={handleSettle}
        disabled={disabled || loading}
        className="text-xs font-semibold px-3 py-1.5 rounded-md bg-gr-gold text-black disabled:opacity-40 disabled:cursor-not-allowed"
      >
        {loading ? "Settling…" : "Settle period"}
      </button>
      {error && <span className="text-xs text-red-500">{error}</span>}
    </div>
  );
}
