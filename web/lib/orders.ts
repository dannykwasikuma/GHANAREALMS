import crypto from 'crypto';
import { queryOne } from './db';

/** Short, human-shareable order number for /lookup - not a UUID, since
 * players need to be able to read it out or type it on a phone. */
export async function generateUniqueOrderNumber(): Promise<string> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate =
      'GR-' + crypto.randomBytes(4).toString('hex').toUpperCase();
    const existing = await queryOne('SELECT id FROM orders WHERE order_number = $1', [candidate]);
    if (!existing) return candidate;
  }
  throw new Error('Could not generate a unique order number after 5 attempts');
}

/** Checks the shared secret the Minecraft bridge plugin sends. Constant-time
 * compare, same reasoning as the Paystack webhook signature check. */
export function verifyBridgeSecret(providedSecret: string | null): boolean {
  const expected = process.env.MINECRAFT_BRIDGE_SECRET;
  if (!expected || !providedSecret) return false;
  const a = Buffer.from(providedSecret);
  const b = Buffer.from(expected);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}
