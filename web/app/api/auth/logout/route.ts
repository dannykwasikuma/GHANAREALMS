import { NextResponse } from 'next/server';

export async function POST() {
  const res = NextResponse.json({ ok: true });
  res.cookies.set('gr_player_session', '', { path: '/', maxAge: 0 });
  return res;
}
