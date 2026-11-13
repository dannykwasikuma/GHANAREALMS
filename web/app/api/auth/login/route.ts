import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { queryOne } from '@/lib/db';
import { verifyPassword, signPlayerSession } from '@/lib/player-auth';

const bodySchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

export async function POST(req: NextRequest) {
  let parsed;
  try {
    parsed = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 });
  }

  const user = await queryOne<{ id: string; email: string; password_hash: string | null }>(
    'SELECT id, email, password_hash FROM users WHERE email = $1',
    [parsed.email],
  );

  // Same error for "no such user" and "wrong password" - don't leak which one.
  if (!user || !user.password_hash || !(await verifyPassword(parsed.password, user.password_hash))) {
    return NextResponse.json({ error: 'Invalid email or password.' }, { status: 401 });
  }

  const token = signPlayerSession({ userId: user.id, email: user.email });
  const res = NextResponse.json({ ok: true });
  res.cookies.set('gr_player_session', token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 24 * 30,
  });
  return res;
}
