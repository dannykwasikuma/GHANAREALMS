// lib/player-auth.ts
// Website account auth for ORDINARY PLAYERS - separate from lib/admin-auth.ts,
// which is unchanged and still governs /admin only. Mirrors its pattern
// (bcrypt + JWT cookie) rather than inventing a different approach.
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import crypto from 'crypto';
import { cookies } from 'next/headers';
import { queryOne, query } from './db';

function jwtSecret(): string {
  const s = process.env.PLAYER_JWT_SECRET;
  if (!s) throw new Error('PLAYER_JWT_SECRET is not set');
  return s;
}

export interface PlayerSession {
  userId: string;
  email: string;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validEmail(email: string): boolean {
  return EMAIL_RE.test(email);
}

// Same minimum as any reasonable password policy; this is a store account,
// not a bank, but still shouldn't accept "123".
export function validPassword(password: string): boolean {
  return typeof password === 'string' && password.length >= 8;
}

export async function hashPassword(plain: string): Promise<string> {
  return bcrypt.hash(plain, 12);
}

export async function verifyPassword(plain: string, hash: string): Promise<boolean> {
  return bcrypt.compare(plain, hash);
}

export function signPlayerSession(session: PlayerSession): string {
  return jwt.sign(session, jwtSecret(), { expiresIn: '30d' });
}

export function verifyPlayerSession(token: string): PlayerSession | null {
  try {
    return jwt.verify(token, jwtSecret()) as PlayerSession;
  } catch {
    return null;
  }
}

/** Reads the session from the request cookie. Use in Server Components and
 * route handlers - this is the ONLY place that should trust "who is the
 * logged-in player", per the brief's "never trust client-submitted identity". */
export async function getPlayerSession(): Promise<PlayerSession | null> {
  const cookieStore = await cookies();
  const token = cookieStore.get('gr_player_session')?.value;
  if (!token) return null;
  return verifyPlayerSession(token);
}

export function generateEmailVerificationToken(): string {
  return crypto.randomBytes(24).toString('hex');
}

/** Returns the verified Minecraft UUID for a logged-in user, or null if they
 * haven't linked (or verified) an account yet. This is the ONLY function
 * checkout should call to decide whether a purchase may proceed - never
 * trust a username typed into the checkout form again (see migration 008 +
 * lib/account-linking.ts for how a link actually gets verified). */
export async function getVerifiedMinecraftPlayer(userId: string) {
  return queryOne<{ id: string; username: string; minecraft_uuid: string | null }>(
    `SELECT id, username, minecraft_uuid FROM minecraft_players
     WHERE user_id = $1 AND verified_at IS NOT NULL`,
    [userId],
  );
}
