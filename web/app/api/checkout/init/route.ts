import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { query, queryOne, pool } from '@/lib/db';
import { resolveMinecraftUuid } from '@/lib/mojang';
import { initializeTransaction } from '@/lib/paystack';
import { generateUniqueOrderNumber } from '@/lib/orders';
import crypto from 'crypto';

const CURRENT_TERMS_VERSION = 'v1-2026';
const CURRENT_PRIVACY_VERSION = 'v1-2026';
const CURRENT_REFUND_VERSION = 'v1-2026';

const bodySchema = z.object({
  productSlug: z.string().min(1),
  minecraftUsername: z.string().min(3).max(16),
  agreedToTerms: z.literal(true),
});

export async function POST(req: NextRequest) {
  let parsed;
  try {
    parsed = bodySchema.parse(await req.json());
  } catch (err) {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 });
  }

  const product = await queryOne<{
    id: string;
    price_pesewas: number;
    currency: string;
    active: boolean;
  }>('SELECT id, price_pesewas, currency, active FROM products WHERE slug = $1', [parsed.productSlug]);

  if (!product || !product.active) {
    return NextResponse.json({ error: 'Product not available' }, { status: 404 });
  }

  // Validate the Minecraft username server-side rather than trusting the
  // client - per section 10. A lookup failure (Mojang API flaky) doesn't
  // block checkout; a definite not_found does.
  const lookup = await resolveMinecraftUuid(parsed.minecraftUsername);
  if (lookup.status === 'not_found') {
    return NextResponse.json({ error: 'That Minecraft username does not exist' }, { status: 400 });
  }
  const minecraftUuid = lookup.status === 'found' ? lookup.uuid : null;
  const usernameToStore = lookup.status === 'found' ? lookup.correctedUsername : parsed.minecraftUsername;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Reuse an existing minecraft_players row for this UUID if we have one,
    // otherwise create it. Falls back to username-only matching if the
    // Mojang lookup failed (lookup_failed case) so checkout still works.
    let playerId: string;
    if (minecraftUuid) {
      const existing = await client.query(
        'SELECT id FROM minecraft_players WHERE minecraft_uuid = $1',
        [minecraftUuid]
      );
      if (existing.rows[0]) {
        playerId = existing.rows[0].id;
      } else {
        const inserted = await client.query(
          'INSERT INTO minecraft_players (username, minecraft_uuid) VALUES ($1, $2) RETURNING id',
          [usernameToStore, minecraftUuid]
        );
        playerId = inserted.rows[0].id;
      }
    } else {
      const inserted = await client.query(
        'INSERT INTO minecraft_players (username) VALUES ($1) RETURNING id',
        [usernameToStore]
      );
      playerId = inserted.rows[0].id;
    }

    const orderNumber = await generateUniqueOrderNumber();
    const order = await client.query(
      `INSERT INTO orders
        (order_number, minecraft_player_id, status, total_pesewas, currency,
         terms_version, privacy_version, refund_policy_version, terms_accepted_at)
       VALUES ($1, $2, 'PENDING', $3, $4, $5, $6, $7, now())
       RETURNING id, order_number`,
      [orderNumber, playerId, product.price_pesewas, product.currency,
       CURRENT_TERMS_VERSION, CURRENT_PRIVACY_VERSION, CURRENT_REFUND_VERSION]
    );
    const orderId = order.rows[0].id;

    const orderItem = await client.query(
      `INSERT INTO order_items (order_id, product_id, unit_price_pesewas, quantity)
       VALUES ($1, $2, $3, 1) RETURNING id`,
      [orderId, product.id, product.price_pesewas]
    );

    // Paystack requires an email; Minecraft accounts don't have one visible
    // to us, so we use a deterministic placeholder - same approach
    // GhanaRealmsPaystack's Java side already used, kept consistent here.
    const placeholderEmail = `${minecraftUuid ?? crypto.randomUUID()}@ghanarealms.players`;
    const reference = 'GR-' + crypto.randomBytes(8).toString('hex').toUpperCase();

    await client.query(
      `INSERT INTO payments
        (order_id, paystack_reference, status, amount_pesewas, currency, paystack_customer_email)
       VALUES ($1, $2, 'PENDING', $3, $4, $5)`,
      [orderId, reference, product.price_pesewas, product.currency, placeholderEmail]
    );

    await client.query('COMMIT');

    const callbackUrl = `${process.env.STORE_BASE_URL}/payment/callback`;
    const initResult = await initializeTransaction({
      email: placeholderEmail,
      amountPesewas: product.price_pesewas,
      reference,
      currency: product.currency,
      callbackUrl,
    });

    if (!initResult.ok) {
      return NextResponse.json({ error: initResult.message ?? 'Could not start checkout' }, { status: 502 });
    }

    return NextResponse.json({
      orderNumber: order.rows[0].order_number,
      authorizationUrl: initResult.authorizationUrl,
      reference,
    });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Checkout init failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
