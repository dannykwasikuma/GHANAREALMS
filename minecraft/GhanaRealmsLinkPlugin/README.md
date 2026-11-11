# GhanaRealmsLinkPlugin

The in-game half of account linking (Document 21). A player runs
`/link <CODE>` after generating a code on the website's `/account/link`
page; this plugin verifies it against the store's
`/api/bridge/verify-link` endpoint using the player's server-known UUID
and username, never anything the player types beyond the code itself.

**Status: written, not compiled or tested.** No Paper API dependency or
Java compiler was available in the environment that wrote this - see the
file header in `GhanaRealmsLinkPlugin.java` for details. It needs:

1. A real Maven/Gradle project wrapping this source with a Paper API
   dependency (e.g. `io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT`,
   matched to your actual server version).
2. `config.yml`'s `BRIDGE.STORE-BASE-URL` and `BRIDGE.BRIDGE-SECRET` filled in for your
   real deployment (`bridge-secret` must exactly match
   `MINECRAFT_BRIDGE_SECRET` in the store's environment).
3. A real build (`mvn package` / `gradle build`) and a test on an actual
   Paper server before trusting it in production.
