# GhanaRealms Store

The official GhanaRealms web store - Next.js + PostgreSQL + Paystack,
delivering purchases into the GhanaRealms Minecraft server.

**Start here:** [STATUS.md](./STATUS.md) - an honest account of what's
built, what's actually been tested (not just written), and what isn't
done yet.

## Quick links
- [DEPLOYMENT.md](./DEPLOYMENT.md) - how to actually deploy this
- [PAYSTACK_SETUP.md](./PAYSTACK_SETUP.md) - getting and configuring Paystack keys
- [MINECRAFT_BRIDGE.md](./MINECRAFT_BRIDGE.md) - how purchases reach the game
- [SECURITY.md](./SECURITY.md) - what's implemented, what isn't
- [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) - common issues
- [STORE_PRODUCTS.md](./STORE_PRODUCTS.md) - how to add real products

## Local development
```bash
npm install
cp .env.example .env.local   # fill in real values
psql $DATABASE_URL -f migrations/001_init.sql
psql $DATABASE_URL -f migrations/002_seed_example_products.sql  # optional test data
node scripts/create-admin.js youradmin YourSecurePassword123
npm run dev
```

## Tech stack
Next.js 16 (App Router, TypeScript), Tailwind CSS 4, PostgreSQL (via `pg`,
no ORM), Paystack REST API directly (no SDK dependency, same reasoning as
the Minecraft plugins' own Paystack client), bcrypt + JWT for admin auth.

## Design
Ghana flag palette (red/gold/green/black) used as the actual design
language throughout - see `app/globals.css` - not decoration layered on
top of a generic template, per the project brief's requirement.
