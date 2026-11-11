// lib/account-linking.ts
// Website-side half of Document 21's account-linking handshake. Generates
// and verifies short-lived codes; a player proves ownership by entering the
// code in-game. The actual Minecraft-side command that reads a typed code
// and calls verifyAndConsumeLinkCode() below does NOT exist in this repo -
// no Minecraft plugin source was ever provided to inspect or extend (same
// gap flagged for the delivery bridge). This file implements everything the
// website can do unilaterally and the one HTTP endpoint
// (/api/bridge/verify-link) the plugin would call; the plugin-side command
// handler itself is a documented gap, see docs/MINECRAFT_BRIDGE.md.
import { generateLinkCode } from './link-code';
import { query, queryOne } from './db';

const CODE_TTL_MINUTES = 15;

export async function startAccountLink(userId: string, minecraftUsername: string) {
  // Supersede any previous unconsumed code for this user, so only the most
  // recent one is valid - prevents an old leaked code from still working.
  await query(
    `UPDATE minecraft_link_codes SET consumed_at = now()
     WHERE user_id = $1 AND consumed_at IS NULL`,
    [userId],
  );

  let code = generateLinkCode();
  // Vanishingly unlikely, but guard the UNIQUE constraint anyway rather than
  // letting an insert fail with a raw DB error.
  for (let attempt = 0; attempt < 5; attempt++) {
    const existing = await queryOne('SELECT id FROM minecraft_link_codes WHERE code = $1', [code]);
    if (!existing) break;
    code = generateLinkCode();
  }

  const expiresAt = new Date(Date.now() + CODE_TTL_MINUTES * 60_000);
  await query(
    `INSERT INTO minecraft_link_codes (user_id, code, minecraft_username, expires_at)
     VALUES ($1, $2, $3, $4)`,
    [userId, code, minecraftUsername, expiresAt],
  );

  return { code, expiresAt, ttlMinutes: CODE_TTL_MINUTES };
}

export interface LinkVerificationResult {
  ok: boolean;
  reason?: 'invalid_code' | 'expired' | 'already_consumed' | 'already_linked' | 'username_mismatch';
}

/**
 * Called by the Minecraft bridge (authenticated the same way as
 * /api/bridge/ack-delivery - shared secret, see lib/orders.ts's
 * verifyBridgeSecret) once a player proves they're in-game with the
 * matching username and types the code. This function does NOT verify the
 * bridge secret itself - that's the caller's job, same separation of
 * concerns as the existing bridge routes.
 */
export async function verifyAndConsumeLinkCode(
  code: string,
  minecraftUsername: string,
  minecraftUuid: string,
): Promise<LinkVerificationResult> {
  const row = await queryOne<{
    id: string; user_id: string; minecraft_username: string;
    expires_at: string; consumed_at: string | null;
  }>('SELECT * FROM minecraft_link_codes WHERE code = $1', [code]);

  if (!row) return { ok: false, reason: 'invalid_code' };
  if (row.consumed_at) return { ok: false, reason: 'already_consumed' };
  if (new Date(row.expires_at).getTime() < Date.now()) return { ok: false, reason: 'expired' };
  if (row.minecraft_username.toLowerCase() !== minecraftUsername.toLowerCase()) {
    return { ok: false, reason: 'username_mismatch' };
  }

  // Is this Minecraft account already linked to a DIFFERENT website user?
  const existingPlayer = await queryOne<{ id: string; user_id: string | null }>(
    'SELECT id, user_id FROM minecraft_players WHERE minecraft_uuid = $1',
    [minecraftUuid],
  );
  if (existingPlayer?.user_id && existingPlayer.user_id !== row.user_id) {
    return { ok: false, reason: 'already_linked' };
  }

  if (existingPlayer) {
    await query(
      'UPDATE minecraft_players SET user_id = $1, verified_at = now(), username = $2 WHERE id = $3',
      [row.user_id, minecraftUsername, existingPlayer.id],
    );
  } else {
    await query(
      `INSERT INTO minecraft_players (username, minecraft_uuid, user_id, verified_at)
       VALUES ($1, $2, $3, now())`,
      [minecraftUsername, minecraftUuid, row.user_id],
    );
  }

  await query('UPDATE minecraft_link_codes SET consumed_at = now() WHERE id = $1', [row.id]);
  return { ok: true };
}
