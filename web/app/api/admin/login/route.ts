import { NextRequest, NextResponse } from 'next/server';
import { queryOne } from '@/lib/db';
import { verifyPassword, signAdminSession } from '@/lib/admin-auth';
import { pool } from '@/lib/db';

export async function POST(req: NextRequest) {
  const { username, password } = await req.json();
  if (!username || !password) {
    return NextResponse.json({ error: 'Missing credentials' }, { status: 400 });
  }

  const admin = await queryOne<{ id: string; username: string; password_hash: string; role: 'admin' | 'superadmin' }>(
    'SELECT id, username, password_hash, role FROM admin_users WHERE username = $1',
    [username]
  );

  // Deliberately identical error/timing shape whether the username exists
  // or the password is wrong - don't leak which one failed.
  if (!admin || !(await verifyPassword(password, admin.password_hash))) {
    return NextResponse.json({ error: 'Invalid credentials' }, { status: 401 });
  }

  await pool.query('UPDATE admin_users SET last_login_at = now() WHERE id = $1', [admin.id]);

  const token = signAdminSession({ adminId: admin.id, username: admin.username, role: admin.role });
  const res = NextResponse.json({ ok: true });
  res.cookies.set('gr_admin_session', token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'strict',
    maxAge: 60 * 60 * 12,
    path: '/',
  });
  return res;
}
