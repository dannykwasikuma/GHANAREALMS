"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    const res = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    if (res.ok) {
      router.push("/account");
      router.refresh();
    } else {
      const data = await res.json().catch(() => ({}));
      setError(data.error ?? "Invalid email or password.");
      setLoading(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-badge">🇬🇭</div>
      <h1 className="text-2xl font-bold mb-6">Sign in</h1>
      <form onSubmit={handleSubmit} className="card auth-card space-y-4">
        <div>
          <label className="field-label">Email address</label>
          <input
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            className="field-input"
          />
        </div>
        <div>
          <label className="field-label">Password</label>
          <input
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            className="field-input"
          />
        </div>
        {error && <p className="alert-error">{error}</p>}
        <button type="submit" disabled={loading} className="btn-primary">
          {loading ? "Signing in..." : "Sign in"}
        </button>
      </form>
      <p className="text-sm opacity-60 mt-5 text-center">
        No account yet? <a href="/register" className="text-gr-gold font-semibold">Create one</a>
      </p>
    </div>
  );
}
