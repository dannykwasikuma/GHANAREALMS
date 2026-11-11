// Called by the Minecraft server bridge once a player types their link code
// in-game - NOT reachable by the browser/player directly (same shared-secret
// pattern as /api/bridge/ack-delivery). The in-game command handler that
// calls this endpoint does not exist in this repo - see
// lib/account-linking.ts's header and docs/MINECRAFT_BRIDGE.md.
import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { verifyBridgeSecret } from '@/lib/orders';
import { verifyAndConsumeLinkCode } from '@/lib/account-linking';

const bodySchema = z.object({
  code: z.string().length(6),
  minecraftUsername: z.string().min(3).max(16),
  minecraftUuid: z.string().uuid(),
});

export async function POST(req: NextRequest) {
  if (!verifyBridgeSecret(req.headers.get('x-bridge-secret'))) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  let parsed;
  try {
    parsed = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 });
  }

  const result = await verifyAndConsumeLinkCode(parsed.code, parsed.minecraftUsername, parsed.minecraftUuid);
  if (!result.ok) {
    return NextResponse.json({ linked: false, reason: result.reason }, { status: 400 });
  }
  return NextResponse.json({ linked: true });
}
