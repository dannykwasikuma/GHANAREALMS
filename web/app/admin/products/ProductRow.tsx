"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";

export interface P {
  id: string; name: string; slug: string; category: string; price_pesewas: number | null; ingame_price_minor: number | null;
  stock: number | null; purchase_limit_per_player: number | null; discount_percent: number; active: boolean; gameplay_only: boolean;
}

const numOrNull = (v: string) => (v.trim() === "" ? null : Number(v));

export function ProductRow({ p }: { p: P }) {
  const router = useRouter();
  const [f, setF] = useState({
    cedi: p.price_pesewas == null ? "" : (p.price_pesewas / 100).toString(),
    coins: p.ingame_price_minor == null ? "" : String(p.ingame_price_minor),
    stock: p.stock == null ? "" : String(p.stock),
    limit: p.purchase_limit_per_player == null ? "" : String(p.purchase_limit_per_player),
    discount: String(p.discount_percent), active: p.active,
  });
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true); setMsg(null);
    const cedi = numOrNull(f.cedi);
    const body = {
      price_pesewas: cedi == null ? null : Math.round(cedi * 100),
      ingame_price_minor: numOrNull(f.coins), stock: numOrNull(f.stock), purchase_limit_per_player: numOrNull(f.limit),
      discount_percent: Number(f.discount || 0), active: f.active,
    };
    try {
      const res = await fetch(`/api/admin/products/${p.id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
      const j = await res.json();
      setMsg(res.ok ? "Saved" : j.error ?? "Failed");
      if (res.ok) router.refresh();
    } catch { setMsg("Network error"); } finally { setBusy(false); }
  }
  const inp = "w-20 bg-transparent border border-card-border rounded px-1 py-0.5";
  return (
    <tr className="border-b border-card-border last:border-0 align-top">
      <td className="p-2"><div className="font-semibold">{p.name}</div><div className="text-xs text-foreground/40">{p.slug}{p.gameplay_only ? " · gameplay only" : ""}</div></td>
      <td className="p-2"><input className={inp} value={f.cedi} placeholder="-" onChange={(e) => setF({ ...f, cedi: e.target.value })} /></td>
      <td className="p-2"><input className={inp} value={f.coins} placeholder="-" onChange={(e) => setF({ ...f, coins: e.target.value })} /></td>
      <td className="p-2"><input className={inp} value={f.stock} placeholder="∞" onChange={(e) => setF({ ...f, stock: e.target.value })} /></td>
      <td className="p-2"><input className={inp} value={f.limit} placeholder="-" onChange={(e) => setF({ ...f, limit: e.target.value })} /></td>
      <td className="p-2"><input className="w-14 bg-transparent border border-card-border rounded px-1 py-0.5" value={f.discount} onChange={(e) => setF({ ...f, discount: e.target.value })} />%</td>
      <td className="p-2"><input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /></td>
      <td className="p-2 text-right">
        <button onClick={save} disabled={busy} className="text-xs font-semibold px-3 py-1 rounded bg-gr-gold text-black disabled:opacity-40">{busy ? "…" : "Save"}</button>
        {msg && <div className="text-xs mt-1 text-foreground/60">{msg}</div>}
      </td>
    </tr>
  );
}
