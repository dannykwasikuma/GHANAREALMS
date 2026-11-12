import crypto from "node:crypto";

const PAYSTACK_BASE_URL = "https://api.paystack.co";

function secretKey(): string {
  const key = process.env.PAYSTACK_SECRET_KEY;
  if (!key) throw new Error("PAYSTACK_SECRET_KEY is not configured.");
  return key;
}

export interface PaystackVerification {
  ok: boolean;
  status?: string;
  amountPesewas?: number;
  currency?: string;
  channel?: string;
  message?: string;
}

export function verifyWebhookSignature(
  rawBody: string,
  signature: string | undefined,
): boolean {
  if (!signature) return false;
  let computed: string;
  try {
    computed = crypto
      .createHmac("sha512", secretKey())
      .update(rawBody)
      .digest("hex");
  } catch {
    return false;
  }
  const expected = Buffer.from(computed);
  const provided = Buffer.from(signature);
  return (
    expected.length === provided.length &&
    crypto.timingSafeEqual(expected, provided)
  );
}

export async function verifyTransaction(
  reference: string,
): Promise<PaystackVerification> {
  let response: Response;
  try {
    response = await fetch(
      `${PAYSTACK_BASE_URL}/transaction/verify/${encodeURIComponent(reference)}`,
      { headers: { Authorization: `Bearer ${secretKey()}` } },
    );
  } catch {
    return { ok: false, message: "Could not reach Paystack." };
  }

  let data: {
    status?: boolean;
    message?: string;
    data?: {
      status?: string;
      amount?: number;
      currency?: string;
      channel?: string;
    };
  };
  try {
    data = (await response.json()) as typeof data;
  } catch {
    return { ok: false, message: "Paystack returned an unexpected response." };
  }
  if (!data.status || !data.data) {
    return { ok: false, message: data.message };
  }
  return {
    ok: true,
    status: data.data.status,
    amountPesewas: data.data.amount,
    currency: data.data.currency,
    channel: data.data.channel,
  };
}
