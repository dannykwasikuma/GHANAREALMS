import { getPlayerSession } from "@/lib/player-auth";
import { lookupClaimToken } from "@/lib/account-claim";
import ClaimForm from "./ClaimForm";

export default async function ClaimPage({ searchParams }: { searchParams: Promise<{ token?: string }> }) {
  const { token } = await searchParams;
  const claim = token && token.length === 64 ? await lookupClaimToken(token) : null;
  if (!token || !claim) {
    return (
      <div className="auth-shell">
        <h1 className="text-2xl font-bold mb-2">Link expired</h1>
        <p className="text-sm opacity-60">Run <code>/store</code> in-game on GhanaRealms to get a fresh link.</p>
      </div>
    );
  }
  const session = await getPlayerSession();
  return <ClaimForm token={token} username={claim.minecraft_username} signedInEmail={session?.email ?? null} />;
}
