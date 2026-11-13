// Called by the Minecraft plugin's /store command. Bridge-secret protected.
import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { verifyBridgeSecret } from '@/lib/orders';
import { createClaimToken } from '@/lib/account-claim';

const bodySchema = z.object({
  minecraftUuid: z.string().uuid(),
  minecraftUsername: z.string().min(3).max(16),
  onlineMode: z.boolean(),
});

export async function POST(req: NextRequest) {
  if (!verifyBridgeSecret(req.headers.get('x-bridge-secret'))) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }
  let parsed;
  try { parsed = bodySchema.parse(await req.json()); }
  catch { return NextResponse.json({ error: 'Invalid request' }, { status: 400 }); }

  // Without Mojang verification a UUID/username proves nothing, so the
  // server-first flow is refused for offline-mode servers.
  if (!parsed.onlineMode) {
    return NextResponse.json({ error: 'server_not_online_mode' }, { status: 400 });
  }
  const { token, expiresAt } = await createClaimToken(parsed.minecraftUuid, parsed.minecraftUsername);
  return NextResponse.json({ url: `${process.env.STORE_BASE_URL}/claim?token=${token}`, expiresAt });
}
