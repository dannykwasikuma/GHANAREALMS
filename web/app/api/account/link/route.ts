import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { getPlayerSession } from '@/lib/player-auth';
import { startAccountLink } from '@/lib/account-linking';
import { resolveMinecraftUuid } from '@/lib/mojang';

const bodySchema = z.object({
  minecraftUsername: z.string().min(3).max(16).regex(/^[A-Za-z0-9_]+$/),
});

export async function POST(req: NextRequest) {
  const session = await getPlayerSession();
  if (!session) return NextResponse.json({ error: 'Not signed in' }, { status: 401 });

  let parsed;
  try {
    parsed = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'Enter a valid Minecraft Java username (3-16 characters).' }, { status: 400 });
  }

  // Same validation principle the original checkout route used: don't issue
  // a link code for a username that doesn't exist. A Mojang API hiccup
  // (lookup_failed) doesn't block this - only a definite not_found does -
  // the in-game verification step is still the real proof of ownership.
  const lookup = await resolveMinecraftUuid(parsed.minecraftUsername);
  if (lookup.status === 'not_found') {
    return NextResponse.json({ error: 'That Minecraft username does not exist.' }, { status: 400 });
  }
  const usernameToUse = lookup.status === 'found' ? lookup.correctedUsername : parsed.minecraftUsername;

  const { code, expiresAt, ttlMinutes } = await startAccountLink(session.userId, usernameToUse);
  return NextResponse.json({ code, expiresAt, ttlMinutes });
}
