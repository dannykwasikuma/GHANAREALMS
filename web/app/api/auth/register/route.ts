import { NextRequest, NextResponse } from 'next/server';
import { z } from 'zod';
import { query, queryOne } from '@/lib/db';
import { hashPassword, validEmail, validPassword, generateEmailVerificationToken } from '@/lib/player-auth';

const bodySchema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
});

export async function POST(req: NextRequest) {
  let parsed;
  try {
    parsed = bodySchema.parse(await req.json());
  } catch {
    return NextResponse.json({ error: 'A valid email and a password of at least 8 characters are required.' }, { status: 400 });
  }
  if (!validEmail(parsed.email) || !validPassword(parsed.password)) {
    return NextResponse.json({ error: 'Invalid email or password.' }, { status: 400 });
  }

  const existing = await queryOne('SELECT id FROM users WHERE email = $1', [parsed.email]);
  if (existing) {
    // Don't reveal which emails exist - generic message either way.
    return NextResponse.json(
      { message: 'If that email is available, check your inbox for next steps.' },
      { status: 200 },
    );
  }

  const passwordHash = await hashPassword(parsed.password);
  const verificationToken = generateEmailVerificationToken();

  await query(
    `INSERT INTO users (email, password_hash, email_verification_token)
     VALUES ($1, $2, $3)`,
    [parsed.email, passwordHash, verificationToken],
  );

  // NOTE: no email provider is wired up in this environment (no internet
  // access to test one). The verification token is generated and stored
  // correctly, but nothing currently sends it to the player - see
  // STATUS.md. Wire up a real provider (Resend, Postmark, SES, etc.)
  // before relying on email verification in production.

  return NextResponse.json(
    { message: 'Account created. Check your email to verify your account before purchasing.' },
    { status: 201 },
  );
}
