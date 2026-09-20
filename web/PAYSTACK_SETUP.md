# Paystack Setup

## Getting your keys
1. Sign up / log in at https://dashboard.paystack.com
2. Settings -> API Keys & Webhooks
3. You'll see four keys: Test Public, Test Secret, Live Public, Live Secret

**SECRET KEY = BACKEND ONLY.** It goes in `PAYSTACK_SECRET_KEY` on your
server's environment only - never in frontend code, never in a browser
network request, never committed to git, never pasted in a screenshot or
support ticket. It's used in exactly one file: `lib/paystack.ts`.

**PUBLIC KEY = frontend-allowed.** This build doesn't currently use the
public key client-side (checkout happens via a redirect to Paystack's
hosted page, initiated server-side) - `PAYSTACK_PUBLIC_KEY` is in
`.env.example` for future use if you add Paystack's inline JS checkout
instead of the redirect flow.

## Test mode first
Use `sk_test_...` / `pk_test_...` until you've run a full checkout -> pay
-> webhook -> delivery cycle successfully. Switching to live is changing
the env vars to `sk_live_.../pk_live_...` - no code changes required,
per the brief's requirement.

## Webhook URL
Set this in the Paystack dashboard (same API Keys & Webhooks page) to:
`https://your-domain/api/payments/paystack/webhook`

If you don't have a domain, see the root `cloudflare-tunnel.sh` script -
it gives you a stable HTTPS URL via Cloudflare without buying one.

## Callback URL
Set `PAYSTACK_CALLBACK_URL` in your env to:
`https://your-domain/payment/callback`
This is where Paystack redirects the player's browser after they pay -
note this page only *displays* status, it never grants anything itself
(see `app/payment/callback/page.tsx` and section 19 of the brief).

## IP whitelist
Paystack's webhook IPs are documented at
https://paystack.com/docs/payments/webhooks/#ip-whitelisting - if you
want to add IP-level filtering in front of the webhook route (in nginx/
Caddy, not in this app's code), use that list. Not implemented in this
build - signature verification is the primary defense.
