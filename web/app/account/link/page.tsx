"use client";

import { useState } from "react";

export default function LinkMinecraftPage() {
  const [username, setUsername] = useState("");
  const [code, setCode] = useState<string | null>(null);
  const [ttlMinutes, setTtlMinutes] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    const res = await fetch("/api/account/link", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ minecraftUsername: username }),
    });
    const data = await res.json();
    setLoading(false);
    if (res.ok) {
      setCode(data.code);
      setTtlMinutes(data.ttlMinutes);
    } else {
      setError(data.error ?? "Could not start linking.");
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-badge">🔗</div>
      <h1 className="text-2xl font-bold mb-2">Link Minecraft Account</h1>
      <p className="text-sm opacity-60 mb-6 leading-relaxed">
        Enter your Minecraft Java username. We&apos;ll give you a short code — type it
        in-game on the GhanaRealms server to finish linking.
      </p>

      {!code ? (
        <form onSubmit={handleSubmit} className="card auth-card space-y-4">
          <div>
            <label className="field-label">Minecraft username</label>
            <input
              required
              minLength={3}
              maxLength={16}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="Your exact Java Edition username"
              className="field-input"
            />
          </div>
          {error && <p className="alert-error">{error}</p>}
          <button type="submit" disabled={loading} className="btn-primary">
            {loading ? "Generating code..." : "Get Link Code"}
          </button>
        </form>
      ) : (
        <div className="card auth-card text-center">
          <p className="field-label !mb-0">Your link code</p>
          <p className="link-code-display">{code}</p>
          <p className="text-sm opacity-60 leading-relaxed mb-4">
            Expires in {ttlMinutes} minutes. Join the GhanaRealms server and run the
            in-game link command with this code to finish.
          </p>
          <div className="alert-success text-left">
            In-game: <code className="font-mono">/link {code}</code>
          </div>
        </div>
      )}
    </div>
  );
}
