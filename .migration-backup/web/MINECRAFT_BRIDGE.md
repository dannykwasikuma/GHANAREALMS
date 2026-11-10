# Minecraft Bridge

How the web store delivers purchases in-game, and how it relates to the
in-game `/buy` command that already existed before this store was built.

## Two independent purchase paths
1. **In-game `/buy`** (GhanaRealmsPaystack's own checkout+webhook) -
   unchanged, still works exactly as before this store existed.
2. **The website** (`/store` -> `/checkout` -> Paystack) - new, uses the
   `delivery_queue` table and the bridge described below.

They don't interfere with each other; a player can use either.

## How website purchases reach the game
```
Web backend confirms payment (webhook)
        |
        v
INSERT INTO delivery_queue (status = PENDING)
        |
        v
GhanaRealmsPaystack polls GET /api/bridge/pending-deliveries
   every BRIDGE.POLL-INTERVAL-SECONDS (config.yml, default 30s)
        |
        v
For each pending delivery: is the player online right now?
   NO  -> skip, leave PENDING, try again next poll (offline-safe)
   YES -> deposit GIVE-MONEY via Vault, run DELIVERY-COMMANDS,
          POST /api/bridge/ack-delivery {success: true/false}
```

This is a pull model (Minecraft polls the web backend), not push, so the
web backend never needs inbound network access to your Minecraft server -
they can be on completely different VPSes, per the brief's section 36.

## Enabling it
In `minecraft/GhanaRealmsPaystack/.../config.yml`:
```yaml
BRIDGE:
  ENABLED: true
  STORE-BASE-URL: 'https://your-store-domain.example'
  BRIDGE-SECRET: 'the same value as MINECRAFT_BRIDGE_SECRET in the web app'
```
`BRIDGE-SECRET` must be identical in both places - generate one with
`openssl rand -hex 32` and set it in both `web/.env` and this config.yml.

## Known limitation (disclosed, not hidden)
If in-game delivery succeeds but the acknowledgement HTTP call back to the
web backend fails (network blip), the web backend still sees the delivery
as PENDING and will hand it out again on the next poll - possibly a
double deposit. See STATUS.md's Known Issues for the real fix needed.
