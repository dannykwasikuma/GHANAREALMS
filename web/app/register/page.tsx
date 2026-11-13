"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setMessage(null);
    const res = await fetch("/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    const data = await res.json();
    setLoading(false);
    if (res.ok) {
      setMessage(data.message);
      setTimeout(() => router.push("/login"), 2000);
    } else {
      setError(data.error ?? "Could not create account.");
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-badge">🇬🇭</div>
      <h1 className="text-2xl font-bold mb-2">Create your GhanaRealms account</h1>
      <p className="text-sm opacity-60 mb-6 leading-relaxed">
        You can browse the store without an account. You&apos;ll need one — and a linked
        Minecraft account — before you can purchase.
      </p>
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
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="At least 8 characters"
            className="field-input"
          />
        </div>
        {error && <p className="alert-error">{error}</p>}
        {message && <p className="alert-success">{message}</p>}
        <button type="submit" disabled={loading} className="btn-primary">
          {loading ? "Creating account..." : "Create account"}
        </button>
      </form>
      <p className="text-sm opacity-60 mt-5 text-center">
        Already have an account? <a href="/login" className="text-gr-gold font-semibold">Sign in</a>
      </p>
    </div>
  );
}
