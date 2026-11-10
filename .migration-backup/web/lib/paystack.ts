import crypto from 'crypto';

// This file must NEVER be imported by client components. Next.js's
// server/client boundary already prevents this file's contents from
// reaching the browser bundle as long as it's only used from API routes
// and Server Components - but the discipline of "secret key lives in
// exactly one file, server-only" is the actual safeguard per the brief's
// section 13/37.

const PAYSTACK_BASE_URL = 'https://api.paystack.co';

function secretKey(): string {
  const key = process.env.PAYSTACK_SECRET_KEY;
  if (!key) throw new Error('PAYSTACK_SECRET_KEY is not set');
  return key;
}

export interface InitializeResult {
  ok: boolean;
  authorizationUrl?: string;
  reference?: string;
  message?: string;
}

export async function initializeTransaction(params: {
  email: string;
  amountPesewas: number;
  reference: string;
  currency: string;
  callbackUrl: string;
}): Promise<InitializeResult> {
  let res: Response;
  try {
    res = await fetch(`${PAYSTACK_BASE_URL}/transaction/initialize`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${secretKey()}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        email: params.email,
        amount: params.amountPesewas,
        currency: params.currency,
        reference: params.reference,
        callback_url: params.callbackUrl,
      }),
    });
  } catch (err) {
    console.error('Paystack initializeTransaction network error:', err);
    return { ok: false, message: 'Could not reach Paystack' };
  }

  let data: any;
  try {
    data = await res.json();
  } catch (err) {
    // Paystack (or a proxy/network layer in front of it) returned something
    // that isn't JSON - don't let that crash the request, surface it as a
    // clean failure instead. Found via actually running this against a
    // sandboxed network that couldn't reach api.paystack.co - the exact
    // kind of bug that only shows up by testing, not by reading the code.
    console.error('Paystack initializeTransaction: non-JSON response', err);
    return { ok: false, message: 'Paystack returned an unexpected response' };
  }

  if (!data.status) {
    return { ok: false, message: data.message };
  }
  return {
    ok: true,
    authorizationUrl: data.data.authorization_url,
    reference: data.data.reference,
  };
}

export interface VerifyResult {
  ok: boolean;
  status?: string; // 'success', 'failed', 'abandoned'
  amountPesewas?: number;
  currency?: string;
  channel?: string;
  customerEmail?: string;
  message?: string;
}

/**
 * Server-to-server verification - the authoritative check. The webhook
 * handler and the callback page BOTH call this rather than trusting their
 * own inputs, per section 19 ("never independently grant products").
 */
export async function verifyTransaction(reference: string): Promise<VerifyResult> {
  let res: Response;
  try {
    res = await fetch(`${PAYSTACK_BASE_URL}/transaction/verify/${encodeURIComponent(reference)}`, {
      headers: { Authorization: `Bearer ${secretKey()}` },
      cache: 'no-store',
    });
  } catch (err) {
    console.error('Paystack verifyTransaction network error:', err);
    return { ok: false, message: 'Could not reach Paystack' };
  }

  let data: any;
  try {
    data = await res.json();
  } catch (err) {
    console.error('Paystack verifyTransaction: non-JSON response', err);
    return { ok: false, message: 'Paystack returned an unexpected response' };
  }

  if (!data.status) {
    return { ok: false, message: data.message };
  }
  return {
    ok: true,
    status: data.data.status,
    amountPesewas: data.data.amount,
    currency: data.data.currency,
    channel: data.data.channel,
    customerEmail: data.data.customer?.email,
  };
}

/**
 * Verifies the x-paystack-signature header (HMAC-SHA512 of the raw body,
 * keyed with the secret key) - the same scheme GhanaRealmsPaystack's Java
 * webhook handler already used, now moved here since the web backend owns
 * the webhook in this architecture.
 */
export function verifyWebhookSignature(rawBody: string, signatureHeader: string | null): boolean {
  if (!signatureHeader) return false;
  const computed = crypto.createHmac('sha512', secretKey()).update(rawBody).digest('hex');
  return timingSafeEqual(computed, signatureHeader);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
}
