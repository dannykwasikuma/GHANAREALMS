# Security

## What's implemented
- Paystack secret key: server-only (`lib/paystack.ts`), never reaches the browser
- Webhook signature: HMAC-SHA512, constant-time comparison (`crypto.timingSafeEqual`)
- Webhook idempotency: enforced at the DATABASE level (UNIQUE constraint),
  not just application logic - verified with a real duplicate-insert test, see STATUS.md
- Payment amount/currency: independently re-verified server-to-server with
  Paystack before any delivery is queued - a forged webhook body alone
  cannot trigger delivery
- Admin passwords: bcrypt, cost factor 12
- Admin sessions: signed JWT in an httpOnly, sameSite=strict cookie
  (secure=true in production) - not readable by client-side JS, not sent
  cross-site
- Bridge secret: constant-time comparison, same pattern as the webhook signature
- SQL: parameterized queries throughout (`pg` library's `$1, $2...` placeholders) -
  no string-concatenated SQL anywhere in this codebase
- Order lookup: deliberately narrow field exposure (no payment amounts, no
  internal IDs, no email - see `app/api/lookup/route.ts`)
- Command injection: delivery commands come only from `products.delivery_commands`,
  a column only an admin can set - no user input is ever concatenated into
  a console command

## What's NOT implemented yet (honestly, not hidden)
- Rate limiting on any endpoint (checkout could be spammed to create
  PENDING orders, though none can be paid without a real Paystack transaction)
- CSRF tokens (mitigated but not fully covered by sameSite=strict cookies)
- Security headers (CSP, HSTS, X-Frame-Options, etc.) - not set anywhere yet
- Formal security review - none has been done, by me or anyone else
- Audit logging - the `audit_logs` table exists in the schema but nothing writes to it yet
- 2FA for admin accounts

## Secret management
All secrets live in environment variables (`.env`, never committed -
`.gitignore` excludes `.env.local` and any `.env*.local`). `.env.example`
documents every variable with no real values. Generate real secrets with
`openssl rand -hex 32`, never reuse example/placeholder values in production.
