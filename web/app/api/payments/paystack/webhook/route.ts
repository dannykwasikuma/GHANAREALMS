import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { verifyWebhookSignature, verifyTransaction } from '@/lib/paystack';

/**
 * This is the authoritative payment-confirmation path. Same discipline as
 * GhanaRealmsPaystack's Java webhook handler had: verify the signature,
 * then independently re-verify the transaction server-to-server (never
 * trust the webhook body's amount/status alone), then use a database
 * constraint - not just application logic - as the actual duplicate guard.
 */
export async function POST(req: NextRequest) {
  const rawBody = await req.text();
  const signature = req.headers.get('x-paystack-signature');

  if (!verifyWebhookSignature(rawBody, signature)) {
    console.warn('Rejected webhook with invalid/missing signature');
    return NextResponse.json({ error: 'Invalid signature' }, { status: 401 });
  }

  let payload: any;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 });
  }

  const eventType: string = payload.event;
  const reference: string | undefined = payload.data?.reference;
  if (!reference) {
    return NextResponse.json({ received: true }); // nothing we can act on, ack anyway
  }

  const client = await pool.connect();
  try {
    // Record the event first, using the UNIQUE(paystack_reference, event_type)
    // constraint as the real idempotency guard - if this insert fails on
    // conflict, we've already processed this exact event and stop here.
    let insertedEvent;
    try {
      insertedEvent = await client.query(
        `INSERT INTO payment_events (paystack_reference, event_type, raw_payload)
         VALUES ($1, $2, $3) RETURNING id`,
        [reference, eventType, payload]
      );
    } catch (err: any) {
      if (err.code === '23505') {
        // unique_violation - duplicate webhook delivery, expected from Paystack retries
        console.log(`Duplicate webhook for ${reference}/${eventType} - ignoring`);
        return NextResponse.json({ received: true });
      }
      throw err;
    }

    if (eventType !== 'charge.success') {
      await client.query('UPDATE payment_events SET processed = true WHERE id = $1', [insertedEvent.rows[0].id]);
      return NextResponse.json({ received: true });
    }

    // Independent server-to-server verification - do not trust the webhook
    // payload's amount/status, per section 19.
    const verification = await verifyTransaction(reference);

    await client.query('BEGIN');

    const payment = await client.query(
      `SELECT id, order_id, amount_pesewas, currency, status FROM payments WHERE paystack_reference = $1 FOR UPDATE`,
      [reference]
    );
    if (!payment.rows[0]) {
      console.error(`Webhook for unknown reference ${reference} - no matching payment row`);
      await client.query('ROLLBACK');
      return NextResponse.json({ received: true });
    }
    const paymentRow = payment.rows[0];

    if (paymentRow.status === 'PAID') {
      // Already processed via another path (e.g. the callback page's own
      // verify call racing this webhook) - stop here, do not deliver twice.
      await client.query('ROLLBACK');
      return NextResponse.json({ received: true });
    }

    if (!verification.ok || verification.status !== 'success') {
      await client.query(`UPDATE payments SET status = 'FAILED' WHERE id = $1`, [paymentRow.id]);
      await client.query(`UPDATE orders SET status = 'FAILED', updated_at = now() WHERE id = $1`, [paymentRow.order_id]);
      await client.query('COMMIT');
      return NextResponse.json({ received: true });
    }

    // Amount/currency sanity check - what Paystack actually confirms must
    // match what we recorded when the order was created.
    if (
      verification.amountPesewas !== paymentRow.amount_pesewas ||
      verification.currency !== paymentRow.currency
    ) {
      console.error(
        `Amount/currency mismatch for ${reference}: expected ${paymentRow.amount_pesewas} ${paymentRow.currency}, ` +
        `got ${verification.amountPesewas} ${verification.currency} - NOT delivering, needs manual review`
      );
      await client.query(`UPDATE payments SET status = 'FAILED' WHERE id = $1`, [paymentRow.id]);
      await client.query('COMMIT');
      return NextResponse.json({ received: true });
    }

    await client.query(
      `UPDATE payments SET status = 'PAID', channel = $1, verified_at = now() WHERE id = $2`,
      [verification.channel ?? null, paymentRow.id]
    );
    await client.query(`UPDATE orders SET status = 'PAID', updated_at = now() WHERE id = $1`, [paymentRow.order_id]);

    // Queue delivery for every item on this order. ON CONFLICT DO NOTHING
    // relies on the UNIQUE(order_item_id) constraint - if a delivery_queue
    // row already exists for this item, this is a no-op, not a duplicate.
    const items = await client.query(
      `SELECT oi.id AS order_item_id, o.minecraft_player_id
       FROM order_items oi
       JOIN orders o ON o.id = oi.order_id
       WHERE oi.order_id = $1`,
      [paymentRow.order_id]
    );
    for (const item of items.rows) {
      await client.query(
        `INSERT INTO delivery_queue (order_id, order_item_id, minecraft_player_id, status)
         VALUES ($1, $2, $3, 'PENDING')
         ON CONFLICT (order_item_id) DO NOTHING`,
        [paymentRow.order_id, item.order_item_id, item.minecraft_player_id]
      );
    }

    await client.query('UPDATE payment_events SET processed = true WHERE id = $1', [insertedEvent.rows[0].id]);
    await client.query('COMMIT');

    return NextResponse.json({ received: true });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Webhook processing failed:', err);
    // Still return 200 - per Paystack's guidance, a 5xx here causes retries
    // which is fine, but we've logged the error either way. Returning 500
    // is also defensible; kept as 500 here so retries actually happen for
    // genuine transient failures (DB connection blip, etc).
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
