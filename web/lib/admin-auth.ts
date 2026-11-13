import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';

function jwtSecret(): string {
  const s = process.env.ADMIN_JWT_SECRET;
  if (!s) throw new Error('ADMIN_JWT_SECRET is not set');
  return s;
}

export async function hashPassword(plain: string): Promise<string> {
  return bcrypt.hash(plain, 12);
}

export async function verifyPassword(plain: string, hash: string): Promise<boolean> {
  return bcrypt.compare(plain, hash);
}

export interface AdminSession {
  adminId: string;
  username: string;
  role: 'admin' | 'superadmin';
}

export function signAdminSession(session: AdminSession): string {
  return jwt.sign(session, jwtSecret(), { expiresIn: '12h' });
}

export function verifyAdminSession(token: string): AdminSession | null {
  try {
    return jwt.verify(token, jwtSecret()) as AdminSession;
  } catch {
    return null;
  }
}
