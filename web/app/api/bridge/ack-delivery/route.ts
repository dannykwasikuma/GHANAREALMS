import { NextRequest, NextResponse } from 'next/server';
import { pool } from '@/lib/db';
import { verifyBridgeSecret } from '@/lib/orders';
import { z } from 'zod';

const bodySchema = z.object({
  deliveryId: z.string().uuid(),
  success: z.boolean(),
  error: z.string().optional(),
});

const MAX_ATTEMPTS = 5;

export async function POST(req: NextRequest) {
  const secret = req.headers.get('x-bridge-secret');
  if (!verifyBridgeSecret(secret)) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  let body;
  try {
    body = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 });
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const current = await client.query(
      'SELECT attempt_count, status FROM delivery_queue WHERE id = $1 FOR UPDATE',
      [body.deliveryId]
    );
    if (!current.rows[0]) {
      await client.query('ROLLBACK');
      return NextResponse.json({ error: 'Delivery not found' }, { status: 404 });
    }
    if (current.rows[0].status === 'DELIVERED') {
      // Already marked delivered by an earlier ack - do not double-count
      // or overwrite; this guards against the plugin retrying an ack that
      // actually succeeded but whose response was lost.
      await client.query('ROLLBACK');
      return NextResponse.json({ ok: true, alreadyDelivered: true });
    }

    const newAttemptCount = current.rows[0].attempt_count + 1;

    await client.query(
      'INSERT INTO delivery_attempts (delivery_queue_id, success, error) VALUES ($1, $2, $3)',
      [body.deliveryId, body.success, body.error ?? null]
    );

    if (body.success) {
      await client.query(
        `UPDATE delivery_queue
         SET status = 'DELIVERED', attempt_count = $1, delivered_at = now(), updated_at = now(), last_error = NULL
         WHERE id = $2`,
        [newAttemptCount, body.deliveryId]
      );
    } else {
      const newStatus = newAttemptCount >= MAX_ATTEMPTS ? 'FAILED' : 'RETRYING';
      await client.query(
        `UPDATE delivery_queue
         SET status = $1, attempt_count = $2, last_error = $3, updated_at = now()
         WHERE id = $4`,
        [newStatus, newAttemptCount, body.error ?? 'Unknown error', body.deliveryId]
      );
    }

    await client.query('COMMIT');
    return NextResponse.json({ ok: true });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('ack-delivery failed:', err);
    return NextResponse.json({ error: 'Internal error' }, { status: 500 });
  } finally {
    client.release();
  }
}
