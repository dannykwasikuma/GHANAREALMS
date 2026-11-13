#!/bin/bash
# GhanaRealms SMP - VPS setup script
# Run this ON YOUR VPS after uploading/extracting this whole folder there.
# This sandbox could not run this script itself (papermc.io and Maven Central
# are not reachable from where this package was built) - it is written
# correctly against each project's official source, but UNTESTED. Watch the
# output for errors on first run.

set -e
cd "$(dirname "$0")"

echo "=== 1. Checking for Java 21+ ==="
if ! command -v java >/dev/null 2>&1; then
  echo "Java not found. On Debian/Ubuntu:  sudo apt update && sudo apt install -y openjdk-21-jre-headless"
  exit 1
fi
java -version

echo "=== 2. Downloading Paper server jar ==="
# CHANGE-ME: confirm this is still the Minecraft version you want at
# https://papermc.io/downloads/paper - this script targets the 1.21 API
# line the plugins here declare in their plugin.yml (api-version: '1.21').
MC_VERSION="1.21.4"
BUILD=$(curl -s "https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}/builds" | \
  grep -o '"build":[0-9]*' | tail -1 | grep -o '[0-9]*')
if [ -z "$BUILD" ]; then
  echo "Could not resolve latest Paper build for ${MC_VERSION}. Check https://papermc.io/downloads/paper manually and download the jar as paper.jar yourself."
else
  curl -o paper.jar "https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}/builds/${BUILD}/downloads/paper-${MC_VERSION}-${BUILD}.jar"
  echo "Downloaded Paper ${MC_VERSION} build ${BUILD} -> paper.jar"
fi

echo "=== 3. Downloading official third-party plugins (free, from their own GitHub releases) ==="
mkdir -p plugins
# LuckPerms - permissions backend the GhanaRealms plugins expect
curl -sL "https://api.github.com/repos/LuckPerms/LuckPerms/releases/latest" \
  | grep "browser_download_url.*Bukkit-.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/LuckPerms.jar {} || echo "LuckPerms download failed - get it manually from https://luckperms.net/download"

# Vault - economy API bridge GhanaRealmsPaystack and UltimateDonutSMP both use
curl -sL "https://api.github.com/repos/MilkBowl/Vault/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/Vault.jar {} || echo "Vault download failed - get it manually from https://github.com/MilkBowl/Vault/releases"

# PlaceholderAPI - required (not soft) dependency of UltimateDonutSMP
curl -sL "https://api.github.com/repos/PlaceholderAPI/PlaceholderAPI/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/PlaceholderAPI.jar {} || echo "PlaceholderAPI download failed - get it manually from https://github.com/PlaceholderAPI/PlaceholderAPI/releases"

# Multiverse-Core - needed to load the Resource/Creative/Skyblock worlds alongside Survival
curl -sL "https://api.github.com/repos/Multiverse/Multiverse-Core/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/Multiverse-Core.jar {} || echo "Multiverse-Core download failed - get it manually from https://github.com/Multiverse/Multiverse-Core/releases"

echo "=== 3b. Downloading infrastructure plugins the audit found missing ==="
# GriefPrevention - land claims (nothing in the 4 audited repos protects builds)
curl -sL "https://api.github.com/repos/TechFortress/GriefPrevention/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/GriefPrevention.jar {} || echo "GriefPrevention download failed - https://github.com/TechFortress/GriefPrevention/releases"

# CoreProtect - block-change logging/rollback (no logging found in any repo)
curl -sL "https://api.github.com/repos/PlayPro/CoreProtect/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/CoreProtect.jar {} || echo "CoreProtect download failed - https://github.com/PlayPro/CoreProtect/releases"

# ViaVersion + ViaBackwards - cross-version play (audit criteria asked for this, nothing had it)
curl -sL "https://api.github.com/repos/ViaVersion/ViaVersion/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/ViaVersion.jar {} || echo "ViaVersion download failed - https://github.com/ViaVersion/ViaVersion/releases"
curl -sL "https://api.github.com/repos/ViaVersion/ViaBackwards/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/ViaBackwards.jar {} || echo "ViaBackwards download failed - https://github.com/ViaVersion/ViaBackwards/releases"

# DecentHolograms - the world-building brief wanted Lobby holograms; nothing here had them
curl -sL "https://api.github.com/repos/DecentSoftware-eu/DecentHolograms/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/DecentHolograms.jar {} || echo "DecentHolograms download failed - https://github.com/DecentSoftware-eu/DecentHolograms/releases"

# Chunky - pre-generates world chunks so players don't lag hitting new terrain
curl -sL "https://api.github.com/repos/pop4959/Chunky/releases/latest" \
  | grep "browser_download_url.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/Chunky.jar {} || echo "Chunky download failed - https://github.com/pop4959/Chunky/releases"

# Spark - performance profiler, essential once real players are on
curl -sL "https://api.github.com/repos/lucko/spark/releases/latest" \
  | grep "browser_download_url.*spark-paper.*\.jar" | head -1 | cut -d '"' -f4 \
  | xargs -I{} curl -L -o plugins/spark.jar {} || echo "Spark download failed - https://github.com/lucko/spark/releases"

# ProtocolLib - required (not soft) dependency of UltimateDonutSMP. Verified
# working: dmulloy2 publishes a rolling "dev-build" release with a real jar.
curl -L -o plugins/ProtocolLib.jar \
  "https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar" \
  || echo "ProtocolLib download failed - https://github.com/dmulloy2/ProtocolLib/releases"

# BlueMap - public web map. Verified working: real versioned release assets.
# NOTE: pin the version below to whatever is current when you run this -
# BlueMap doesn't have a clean "latest" redirect to a fixed filename.
BLUEMAP_VERSION="5.27"
curl -L -o plugins/BlueMap.jar \
  "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v${BLUEMAP_VERSION}/bluemap-${BLUEMAP_VERSION}-paper.jar" \
  || echo "BlueMap download failed - check https://github.com/BlueMap-Minecraft/BlueMap/releases for the current version and update BLUEMAP_VERSION above"

echo "The following are NOT auto-downloaded here because they're not hosted on"
echo "GitHub releases (verified by checking each repo directly, not assumed):"
echo "  - WorldGuard + WorldEdit: EngineHub publishes via their own site/Jenkins,"
echo "    not GitHub releases (their GitHub repos have no release assets at all)."
echo "    https://enginehub.org/worldguard  and  https://enginehub.org/worldedit"
echo "  - An anticheat (Grim): checked GrimAnticheat/Grim's latest GitHub release"
echo "    directly - it has NO attached jar files (built via a gated CI flow, not"
echo "    public release assets). Get a build from their Discord/docs, or use"
echo "    Vulcan instead: https://github.com/Vulcanized-Tech/Vulcan"
echo "  - Geyser + Floodgate (Bedrock cross-play): GeyserMC's GitHub repos also"
echo "    have no release assets - they distribute via their own downloads site."
echo "    https://geysermc.org/download"
echo "Get these manually following the linked instructions; I'd rather tell you"
echo "plainly which ones need a manual step than paste in a URL I couldn't"
echo "actually verify returns a real file."

echo "NOTE: ProtocolLib (required, not soft, dependency of UltimateDonutSMP) is on"
echo "SpigotMC/Jenkins, not GitHub releases, so it isn't auto-downloaded here."
echo "Get it from: https://www.spigotmc.org/resources/protocollib.1997/"

echo "=== 4. Building the GhanaRealms plugins ==="
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven not found. On Debian/Ubuntu: sudo apt install -y maven"
  exit 1
fi

for proj in plugins-to-build/UltimateDonutSMP-GhanaRealms plugins-to-build/GhanaRealmsPaystack plugins-to-build/GhanaRealmsQuests plugins-to-build/GhanaRealmsCasino plugins-to-build/GhanaRealmsAscension plugins-to-build/GhanaRealmsParkour plugins-to-build/GhanaRealmsShops plugins-to-build/GhanaRealmsGuard; do
  echo "--- Building $proj ---"
  (cd "$proj" && mvn -q clean package) || echo "!! Build failed for $proj - check the Maven output above"
done
for proj in UltimateDonutSMP-GhanaRealms GhanaRealmsPaystack GhanaRealmsQuests GhanaRealmsCasino GhanaRealmsAscension GhanaRealmsParkour GhanaRealmsShops GhanaRealmsGuard; do
  find plugins-to-build/$proj/target -name "*.jar" ! -name "*sources*" -exec cp {} plugins/ \;
done
echo "GhanaRealmsBounty is deliberately NOT built/copied - see README: it"
echo "duplicated UltimateDonutSMP's existing BountyManager/BountyCommand and"
echo "would conflict with it over the /bounty command."

echo "SMP-Core is a suite of separate small plugins (Pizza*), each with its own"
echo "build - see plugins-to-build/SMP-Core-GhanaRealms/README.md and setup/"
echo "for how the original author intended it to be built. I did not guess at"
echo "a unified build for it since none was present in the cloned repo."

echo "=== Setup script done. Review the output above for any failed steps. ==="
echo "Next: read README.md, accept the EULA yourself, then run ./start.sh"
