# GhanaRealms SMP

A strictly Ghanaian-themed Minecraft Paper SMP: Ghana-flag branding, Cedi
currency, Ghanaian-inspired worlds, and its own Paystack payment plugin.

This package is meant to be **uploaded whole to a VPS and set up there** -
several steps (downloading Paper, downloading third-party plugins, and
`mvn` building the GhanaRealms plugins) need real internet access this
package was *prepared* without, so they're scripted for you to run on the
VPS rather than already done. Read this whole file before running anything.

## What's actually in this folder

```
GHANAREALMS-SMP/
├── README.md                  <- you are here
├── setup.sh                   <- run first, once, on the VPS
├── start.sh                   <- run to launch the server
├── server.properties          <- configured, a few CHANGE-ME values left
├── eula.txt                   <- NOT pre-accepted, see below
├── ops.json / whitelist.json / banned-*.json  <- empty templates
├── worlds/
│   ├── GhanaRealms-Survival/  <- main world: 10 hand-placed Ghanaian
│   │                             landmarks on real generated terrain
│   ├── GhanaRealms-Resource/  <- mining/prison-style world
│   ├── GhanaRealms-Creative/  <- building world
│   └── GhanaRealms-Skyblock/  <- 5 void islands
└── plugins-to-build/
    ├── UltimateDonutSMP-GhanaRealms/   <- themed, needs `mvn package`
    ├── SMP-Core-GhanaRealms/           <- themed, see its own README
    ├── GhanaRealmsPaystack/            <- new plugin, needs `mvn package`
    ├── GhanaRealmsQuests/              <- new plugin, needs `mvn package`
    ├── GhanaRealmsCasino/              <- new plugin, needs `mvn package`
    ├── GhanaRealmsAscension/           <- new plugin, needs `mvn package`
    ├── GhanaRealmsParkour/             <- new plugin, needs `mvn package`
    └── GhanaRealmsShops/               <- new plugin, needs `mvn package`
```

## Before you do anything else: things I could not do for you

- **I did not accept Mojang's EULA.** `eula.txt` ships with `eula=false`.
  That agreement is between you and Mojang/Microsoft, not something an AI
  can consent to on your behalf. Read https://aka.ms/MinecraftEULA and
  change the file yourself.
- **I did not compile or run anything.** No Paper server, no `mvn build`,
  nothing here has been started up and watched work. `setup.sh` and
  `start.sh` are written correctly against each project's real build
  files and official download sources, but treat first boot as a test,
  not a known-good deploy.
- **I did not enable cracked/offline-mode play**, and won't. Online-mode
  stays `true` in `server.properties`. See the earlier conversation for
  why - short version: offline-mode mainly serves unlicensed copies of
  the game, and that's not something I'll help configure regardless of
  how the request is framed.
- **joshymc-plugin and Pulse are not part of this package.** Neither has
  a license, so I don't have standing to include modified copies of
  either without their authors' permission.

## Setup (on your VPS, not before)

1. Upload this whole `GHANAREALMS-SMP/` folder to your VPS (`scp -r`,
   `rsync`, or a zip transfer + `unzip`).
2. `chmod +x setup.sh start.sh`
3. `./setup.sh` — downloads Paper, downloads LuckPerms/Vault/
   PlaceholderAPI/Multiverse-Core from their own official GitHub
   releases, and `mvn package`s the two GhanaRealms plugins that have
   one. Read its output; a few steps have manual fallback links if a
   download fails.
4. Get **ProtocolLib** yourself (SpigotMC, not GitHub releases - link is
   in `setup.sh`'s output). UltimateDonutSMP requires it.
5. Build **SMP-Core** separately - it's 11 small plugins, not one; see
   `plugins-to-build/SMP-Core-GhanaRealms/README.md` for how its author
   built it. I didn't invent a unified build step that wasn't there.
6. Open `eula.txt`, read the linked agreement, set `eula=true` yourself.
7. Edit the `CHANGE-ME` values in `server.properties` (rcon password) and
   `start.sh` (memory allocation - set it below your VPS's actual RAM).
8. `./start.sh`
9. Once it's up: `/mv import GhanaRealms-Resource normal`, same for
   `-Creative` and `-Skyblock`, to load the other three worlds alongside
   Survival (Multiverse-Core handles multi-world loading; Paper only
   auto-loads the world named in `server.properties` by itself).

## Infrastructure plugins now auto-fetched by setup.sh

Real gaps found in the audit that none of the 4 repos (or anything built
so far) covered:

| Plugin | Why | Auto-fetched? |
|---|---|---|
| GriefPrevention | Land claims - nothing stopped griefing | Yes |
| CoreProtect | Block-change logging/rollback | Yes |
| ViaVersion + ViaBackwards | Cross-version play | Yes |
| DecentHolograms | Lobby holograms (asked for in the world-building brief) | Yes |
| Chunky | Pre-generates chunks so new areas don't lag on first visit | Yes |
| Spark | Performance profiling once real players are on | Yes |
| ProtocolLib | Required (not soft) dependency of UltimateDonutSMP | **Yes now** - verified real download URL |
| BlueMap | Public web map | **Yes now** - verified real download URL (version pinned, check for updates) |
| WorldGuard | Extra/alternative region protection | No - confirmed EngineHub has zero GitHub release assets (own site/Jenkins), manual link. Note: GriefPrevention above already covers the core land-claim gap, so this is optional extra, not a blocker |
| Anticheat (Grim) | Zero anticheat found across all 4 repos | No - confirmed Grim's latest GitHub release has zero attached files (gated CI build), manual link to Vulcan as an alternative that might have real releases |
| Geyser + Floodgate | Bedrock cross-play (UltimateDonutSMP has Bedrock-aware code but no bridge) | No - confirmed GeyserMC has zero GitHub release assets (own download site), manual link |

These three "No" rows aren't guesses or laziness - I checked each repo directly
(GitHub release pages, not just assumption) before deciding. Building original
replacements for Grim or Geyser isn't realistic scope either: an anticheat
needs real movement-prediction/packet analysis, and Geyser is a full Bedrock
protocol translation layer - both are multi-year projects by their actual
teams, not something to approximate from scratch here.

## New plugin: GhanaRealmsAscension (level/rank/stat progression)

Built from scratch after you asked for a "Solo Leveling"-style system.
I'm not using that name or its specific terms (Shadow Monarch, Hunter
ranks, etc.) - that's someone else's licensed IP, the same reasoning
that kept joshymc-plugin/Pulse's code out and cracked-client support off
the table. What's actually built has the same *shape*:
  - XP from mob/player kills, a level curve, stat points per level
  - Three stats (Strength/Vitality/Agility) applied as capped vanilla
    Bukkit AttributeModifiers - stays inside normal combat balance rather
    than a custom damage layer
  - Rank titles at level milestones, styled on well-known Akan words
    (Ɔkofo = warrior, Ohene = chief/king) - flavor naming, not a claim of
    precise traditional rank structure
  - **Gates**: instanced mob-wave combat trials (Bronze/Silver/Gold),
    reusing the Arena coordinates already folded into Survival

**Disclosed scope limit**: Gates share one arena location per tier rather
than spinning up a private instance per party (real multi-instance
dungeoning needs world-cloning/region-isolation infrastructure this
doesn't have) - only one player/party can be inside a given Gate tier at
a time; others are told it's occupied. Good enough for a small-to-medium
server, a real limit on a large one.

## New plugins: GhanaRealmsParkour and GhanaRealmsShops

**Correction on an earlier mistake:** GhanaRealmsBounty (built two rounds
ago) has been removed. UltimateDonutSMP already has a complete bounty
system (`BountyManager`/`BountyCommand`/`BountyMenu`) that I missed in
the original audit - my plugin would have conflicted with it over the
`/bounty` command. Caught before shipping, not after. UltimateDonutSMP's
own bounty messages are now Ghanaian-themed (Cedi symbol) instead.

- **GhanaRealmsParkour** - checkpoint courses, timers, leaderboards,
  `/parkouradmin` builds a course from wherever you're standing.
- **GhanaRealmsShops** - sign-based player shops attached to chests
  (place a chest, hold an item, place a sign reading `[shop]` /
  quantity / `B<price>` or `S<price>`). This is the actual gap joshymc-
  plugin's SignShopManager covered that UltimateDonutSMP's global
  auction house doesn't - a shop tied to one player's specific chest at
  one location, not a server-wide listing.

## A real bug found and fixed during this pass

Checking these files with an actual YAML parser (not just eyeballing
them) turned up a genuine syntax error introduced several rounds ago in
messages.yml's Discord section - bash-style quote-escaping used where
valid YAML escaping was needed, which would have made the whole file
fail to load on server start. Fixed, and every YAML file in this package
(all 7 new plugins + the two themed repos) is now verified to parse
cleanly with a real parser. Also found and fixed ~33 more hardcoded `$`
signs scattered through messages.yml/menus.yml that the earlier
CurrencyManager.java edit didn't reach (that edit only changed the
*default*, not literal `$` characters already written into messages).

## World content added this pass

Survival: Mountain Viewpoint (rest area overlooking the coast/lake) and
a Historic Trading Post along the village-to-market route - verified the
same way as every other landmark. Creative: an actual road grid and
central plaza now divide the flat building plot into real city blocks,
per the original brief's ask for "Ghanaian city blocks."

## Getting a public HTTPS webhook URL without a domain (Cloudflare Tunnel)

Paystack needs to reach your webhook over the internet - that's Paystack's
requirement, not optional. You don't need to buy a domain or build a
website for this; `cloudflare-tunnel.sh` sets up a free, stable tunnel
using an existing Cloudflare account (confirmed you have one):

  1. `chmod +x cloudflare-tunnel.sh && ./cloudflare-tunnel.sh`
  2. Step 2 opens a login URL - this is Cloudflare's own auth flow, it
     genuinely cannot be scripted around; open it in a browser and approve
  3. Everything else (installing cloudflared, creating the tunnel, writing
     config) is automated
  4. Use the resulting URL + WEBHOOK.PATH as your Paystack webhook URL

The `.deb` download URL in this script was tested directly (got a real
200 response with the actual file) before being included, not guessed.

## Paystack setup

See `plugins-to-build/GhanaRealmsPaystack/src/main/resources/config.yml`
- it's commented inline. Short version: get your keys from the Paystack
dashboard, set them there, point Paystack's webhook URL at your server,
and test with `sk_test_...` keys before switching to live ones. Full
detail is in `GHANAREALMS-PLUGINS-README.txt` from the earlier package.

## Discord

The invite `discord.gg/z5uwKstfp4` is wired into the tablist, MOTD, `/discord`
command, and rules menu. Its expiry is a Discord-side setting - in your
Discord server go to Server Settings → Invites → this invite → set
**Expire After: Never**, since nothing in this Minecraft-side package can
change that for you.

## Ghanaian identity - what's actually themed vs. what's structural

Themed: Discord/social links, tablist, MOTD, scoreboard title, currency
name and symbol (Cedi, ₵), shop menu titles, crate tiers and keys
(Adinkra/Kente/Ashanti/Golden Stool), Paystack package names (Adinkra
Pack, Kente Rank, Golden Stool Rank), all Survival-world landmarks
(compound-house village, market, coastal fort, chief's palace, durbar
grounds, cocoa grove, fishing village, savanna farm, logging camp, hidden
Adinkra shrine).

New original plugins (built from scratch, no code borrowed from
joshymc-plugin or Pulse - see the audit's licensing findings for why):
- **GhanaRealmsQuests** - daily/weekly quests, including two that send
  players to the actual Chief's Palace and Durbar Grounds coordinates in
  GhanaRealms-Survival.
- **GhanaRealmsCasino** - Oware (the real, centuries-old traditional
  Ghanaian/West African mancala game - genuine sowing/capture rules, not
  a reskinned slot machine) as a wagered 1v1 GUI game, plus a simple
  consent-gated coin-toss ("Cedi Toss"). Both require the *target's*
  explicit `accept` before any money moves - an earlier draft of Cedi
  Toss resolved on the challenger's command alone, which would have let
  anyone force a withdrawal from another player without consent; that
  was caught and fixed before being included here.

Still not done, flagged rather than skipped silently: hundreds of
individual messages.yml lines (kill/death messages, most command
feedback and error text), achievement/advancement text, and SMP-Core's
punishment/chat-filter message wording are still generic English. A
full line-by-line pass across both repos' full message files is a much
bigger job than what's been done so far - say the word if you want that
next.

Deliberately left alone: internal plugin IDs, Java package names, and
permission node strings (e.g. `ultimatedonutsmp.command.*`) in the two
existing plugins. These are referenced in hundreds of places; renaming
them is a real refactor that needs a build-and-test loop I don't have
here. Worth doing once you can build locally, but it's cosmetic to the
plugin's internals, not something players ever see.
