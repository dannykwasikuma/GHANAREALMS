# Minecraft Server Guide (Windows cmd)
1. Install Java 21 and Maven. Download Paper 1.21.x into a folder as `paper.jar`.
2. Run `java -Xmx4G -jar paper.jar nogui` once, open `eula.txt` and set `eula=true` yourself (owner decision). Keep `online-mode=true`. If RCON is enabled use a strong password and block the port.
3. Install Vault + an economy plugin into `plugins\`.
4. For each plugin: `mvn -f minecraft\<Plugin>\pom.xml package`, copy the shaded jar from `target\` to `plugins\`.
5. In Paystack, Clans, Items and LinkPlugin configs set store URL and bridge secret (= MINECRAFT_BRIDGE_SECRET).
6. Restart. In game: `/gmarket plan`, `/gmarket build confirm` (back up the world first; never run in the lobby world).
Players: `/myaccount` to claim, `/link CODE` to link. `setup.sh` is Linux-only and untested. Backups: copy world folders and `plugins\` before updates.
