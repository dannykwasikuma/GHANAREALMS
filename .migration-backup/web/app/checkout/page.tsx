import { redirect } from "next/navigation";
import { getPlayerSession, getVerifiedMinecraftPlayer } from "@/lib/player-auth";
import CheckoutForm from "./CheckoutForm";

export default async function CheckoutPage() {
  // Document 21, section 4: "If the Minecraft account is not linked, reject
  // checkout before initiating payment" - enforced here AND again inside
  // /api/checkout/init itself (never rely on the page redirect alone).
  const session = await getPlayerSession();
  if (!session) redirect("/login");

  const linkedPlayer = await getVerifiedMinecraftPlayer(session.userId);
  if (!linkedPlayer) redirect("/account/link");

  return <CheckoutForm minecraftUsername={linkedPlayer.username} />;
}
