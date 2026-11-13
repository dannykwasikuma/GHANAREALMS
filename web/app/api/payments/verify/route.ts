import { NextRequest, NextResponse } from 'next/server';
import { queryOne } from '@/lib/db';

/**
 * Read-only status check for the callback page. Deliberately does NOT
 * grant anything - per section 19, only the webhook handler (which
 * independently re-verifies with Paystack) ever changes payment/order
 * status or queues delivery. This endpoint just reports what's already
 * true in the database.
 */
export async function GET(req: NextRequest) {
  const reference = req.nextUrl.searchParams.get('reference');
  if (!reference) {
    return NextResponse.json({ error: 'Missing reference' }, { status: 400 });
  }

  const payment = await queryOne<{
    status: string;
    order_id: string;
  }>('SELECT status, order_id FROM payments WHERE paystack_reference = $1', [reference]);

  if (!payment) {
    return NextResponse.json({ state: 'UNKNOWN' });
  }

  const order = await queryOne<{ order_number: string; status: string }>(
    'SELECT order_number, status FROM orders WHERE id = $1',
    [payment.order_id]
  );

  const state =
    payment.status === 'PAID' ? 'PAID' :
    payment.status === 'FAILED' ? 'FAILED' :
    payment.status === 'ABANDONED' ? 'CANCELLED' :
    'PENDING';

  return NextResponse.json({
    state,
    orderNumber: order?.order_number ?? null,
  });
}
