# Environment Variables (names only; never commit values)
Set secrets with `npx wrangler secret put NAME` in `web\`.
| Name | Purpose |
|---|---|
| PAYSTACK_PUBLIC_KEY / PAYSTACK_SECRET_KEY | Paystack keys (server only) |
| PAYSTACK_CALLBACK_URL / PAYSTACK_WEBHOOK_URL | redirect and webhook URLs |
| PAYSTACK_CHANNELS | optional, e.g. card,mobile_money |
| MINECRAFT_BRIDGE_SECRET | shared with plugins (`x-bridge-secret`) |
| ADMIN_JWT_SECRET | admin session signing |
| PLAYER_JWT_SECRET | player session signing; MUST differ from admin |
| DATABASE_URL | Postgres (local dev; production uses the Hyperdrive binding) |
| STORE_BASE_URL | `wrangler.jsonc` var, public site URL |
Hubtel: none, not implemented.
