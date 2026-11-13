// lib/account-claim.ts
// Server-first account claiming. The Minecraft UUID (verified by Mojang
// because the server runs online-mode) is the root identity; this lets that
// identity create or attach to a website account via a one-time link.
import crypto from 'crypto';
import { pool, query, queryOne } from './db';
import { hashPassword } from './player-auth';

const CLAIM_TTL_MINUTES = 15;

export function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export async function createClaimToken(minecraftUuid: string, minecraftUsername: string) {
  // Only the newest token per player is valid.
  await query(
    'UPDATE minecraft_claim_tokens SET consumed_at = now() WHERE minecraft_uuid = $1 AND consumed_at IS NULL',
    [minecraftUuid],
  );
  const token = crypto.randomBytes(32).toString('hex');
  const expiresAt = new Date(Date.now() + CLAIM_TTL_MINUTES * 60_000);
  await query(
    `INSERT INTO minecraft_claim_tokens (token_hash, minecraft_uuid, minecraft_username, expires_at)
     VALUES ($1, $2, $3, $4)`,
    [hashToken(token), minecraftUuid, minecraftUsername, expiresAt],
  );
  return { token, expiresAt };
}

export async function lookupClaimToken(token: string) {
  return queryOne<{ minecraft_uuid: string; minecraft_username: string }>(
    `SELECT minecraft_uuid, minecraft_username FROM minecraft_claim_tokens
     WHERE token_hash = $1 AND consumed_at IS NULL AND expires_at > now()`,
    [hashToken(token)],
  );
}

export type ClaimResult =
  | { ok: true; userId: string; email: string }
  | { ok: false; reason: 'invalid_token' | 'already_linked' | 'user_has_account' | 'email_unavailable' };

/** existingUserId (from the session) attaches the player to that account;
 * otherwise email+password create a new account. Everything is one transaction. */
export async function consumeClaim(
  token: string,
  opts: { existingUserId?: string; existingEmail?: string; email?: string; password?: string },
): Promise<ClaimResult> {
  const passwordHash = !opts.existingUserId && opts.password ? await hashPassword(opts.password) : null;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const t = (await client.query(
      `SELECT id, minecraft_uuid, minecraft_username FROM minecraft_claim_tokens
       WHERE token_hash = $1 AND consumed_at IS NULL AND expires_at > now() FOR UPDATE`,
      [hashToken(token)],
    )).rows[0];
    if (!t) { await client.query('ROLLBACK'); return { ok: false, reason: 'invalid_token' }; }

    const mp = (await client.query(
      'SELECT id, user_id FROM minecraft_players WHERE minecraft_uuid = $1 FOR UPDATE',
      [t.minecraft_uuid],
    )).rows[0];

    let userId: string;
    let email: string;
    if (mp?.user_id) {
      // This Minecraft account already belongs to a website account.
      if (opts.existingUserId && opts.existingUserId === mp.user_id) {
        userId = mp.user_id; email = opts.existingEmail ?? '';
      } else {
        await client.query('ROLLBACK'); return { ok: false, reason: 'already_linked' };
      }
    } else if (opts.existingUserId) {
      const other = (await client.query('SELECT 1 FROM minecraft_players WHERE user_id = $1', [opts.existingUserId])).rows[0];
      if (other) { await client.query('ROLLBACK'); return { ok: false, reason: 'user_has_account' }; }
      userId = opts.existingUserId; email = opts.existingEmail ?? '';
    } else {
      if (!opts.email || !passwordHash) { await client.query('ROLLBACK'); return { ok: false, reason: 'email_unavailable' }; }
      const taken = (await client.query('SELECT 1 FROM users WHERE email = $1', [opts.email])).rows[0];
      if (taken) { await client.query('ROLLBACK'); return { ok: false, reason: 'email_unavailable' }; }
      const u = await client.query('INSERT INTO users (email, password_hash) VALUES ($1, $2) RETURNING id', [opts.email, passwordHash]);
      userId = u.rows[0].id; email = opts.email;
    }

    if (mp) {
      await client.query('UPDATE minecraft_players SET user_id = $1, verified_at = now(), username = $2 WHERE id = $3',
        [userId, t.minecraft_username, mp.id]);
    } else {
      await client.query('INSERT INTO minecraft_players (username, minecraft_uuid, user_id, verified_at) VALUES ($1, $2, $3, now())',
        [t.minecraft_username, t.minecraft_uuid, userId]);
    }
    await client.query('UPDATE minecraft_claim_tokens SET consumed_at = now() WHERE id = $1', [t.id]);
    await client.query('COMMIT');
    return { ok: true, userId, email };
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}
