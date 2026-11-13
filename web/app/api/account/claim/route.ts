import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { consumeClaim } from '@/lib/account-claim';
import { getPlayerSession, signPlayerSession, validEmail, validPassword } from '@/lib/player-auth';

const bodySchema = z.object({
  token: z.string().length(64),
  email: z.string().optional(),
  password: z.string().optional(),
});

export async function POST(req: NextRequest) {
  let parsed;
  try { parsed = bodySchema.parse(await req.json()); }
  catch { return NextResponse.json({ error: 'Invalid request' }, { status: 400 }); }

  const session = await getPlayerSession();
  if (!session && (!parsed.email || !validEmail(parsed.email) || !parsed.password || !validPassword(parsed.password))) {
    return NextResponse.json({ error: 'Enter a valid email and a password of at least 8 characters.' }, { status: 400 });
  }

  const result = await consumeClaim(parsed.token, {
    existingUserId: session?.userId, existingEmail: session?.email,
    email: parsed.email, password: parsed.password,
  });
  if (!result.ok) {
    const msg: Record<string, string> = {
      invalid_token: 'This link has expired. Run /store in-game for a new one.',
      already_linked: 'This Minecraft account is already linked to a different website account. Sign in to that account instead.',
      user_has_account: 'Your website account is already linked to a Minecraft account.',
      email_unavailable: 'We could not create an account with that email. If you already have one, sign in first, then open this link again.',
    };
    return NextResponse.json({ error: msg[result.reason] }, { status: 400 });
  }

  const res = NextResponse.json({ ok: true });
  if (!session) {
    res.cookies.set('gr_player_session', signPlayerSession({ userId: result.userId, email: result.email }), {
      httpOnly: true, secure: process.env.NODE_ENV === 'production', sameSite: 'lax', path: '/', maxAge: 60 * 60 * 24 * 30,
    });
  }
  return res;
}
