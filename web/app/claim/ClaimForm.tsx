"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";

export default function ClaimForm({ token, username, signedInEmail }: { token: string; username: string; signedInEmail: string | null }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true); setError(null);
    const res = await fetch("/api/account/claim", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(signedInEmail ? { token } : { token, email, password }),
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) { router.push("/account"); router.refresh(); }
    else { setError(data.error ?? "Something went wrong."); setLoading(false); }
  }

  return (
    <div className="auth-shell">
      <div className="auth-badge">⛏️</div>
      <h1 className="text-2xl font-bold mb-2">Welcome, {username}</h1>
      <p className="text-sm opacity-60 mb-6 leading-relaxed">
        {signedInEmail ? `Link this Minecraft account to ${signedInEmail}.` : "Create your GhanaRealms account. It will be tied to this Minecraft account, so the store and the server share one identity."}
      </p>
      <form onSubmit={submit} className="card auth-card space-y-4">
        {!signedInEmail && (<>
          <div><label className="field-label">Email address</label>
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="field-input" placeholder="you@example.com" /></div>
          <div><label className="field-label">Password</label>
            <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} className="field-input" placeholder="At least 8 characters" /></div>
        </>)}
        {error && <p className="alert-error">{error}</p>}
        <button type="submit" disabled={loading} className="btn-primary">{loading ? "Working..." : signedInEmail ? "Link account" : "Create account"}</button>
      </form>
    </div>
  );
}
