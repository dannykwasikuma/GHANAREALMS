# Troubleshooting

**"Could not reach Paystack" / HTTP 502 on checkout**
Check `PAYSTACK_SECRET_KEY` is set and valid, and that your server has
outbound internet access to api.paystack.co. (This exact error is what
you'll see in this sandbox's own testing - it's expected here since that
domain isn't reachable from the build environment; it should NOT happen
on a real server with normal internet access. If it does, that's a real
problem worth investigating.)

**Webhook returns 401**
The signature didn't match. Confirm `PAYSTACK_SECRET_KEY` in your env is
the SAME key shown in the Paystack dashboard right now (not an old/rotated
one), and that nothing between Paystack and your server (a proxy, a WAF)
is modifying the raw request body before it reaches the app - the
signature is computed over the exact raw bytes.

**Purchases aren't reaching the game**
1. Check `BRIDGE.ENABLED: true` in GhanaRealmsPaystack's config.yml
2. Check `BRIDGE-SECRET` matches `MINECRAFT_BRIDGE_SECRET` in the web app's env, exactly
3. Check the delivery_queue table: `SELECT * FROM delivery_queue WHERE status = 'PENDING'`
   - if rows exist and aren't clearing, the Minecraft server can't reach
     the web store's URL, or the player was offline every time it polled
4. Check the Minecraft server's console log for "Bridge poll failed" messages

**Admin login always fails**
Confirm you ran `node scripts/create-admin.js` and that `ADMIN_JWT_SECRET`
is set (a missing secret throws, not fails silently - check server logs).

**Migration fails with "extension pgcrypto does not exist"**
Your PostgreSQL user needs superuser or the pgcrypto extension pre-created
by a superuser: `CREATE EXTENSION pgcrypto;` run as a superuser once.
