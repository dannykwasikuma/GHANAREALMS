/**
 * Validates a Minecraft username against Mojang's real API and resolves
 * its UUID, per the brief's "do not blindly trust client-provided identity
 * information."
 *
 * HONESTY NOTE: api.mojang.com is not reachable from the sandbox this was
 * built in (outside that environment's allowed domain list), so this
 * specific function has NOT been run against the real Mojang API - unlike
 * the database layer, which was actually tested against live Postgres.
 * It's written against Mojang's documented, stable endpoint shape, but
 * verify it once deployed somewhere with normal internet access.
 */

export type MojangLookupResult =
  | { status: 'found'; uuid: string; correctedUsername: string }
  | { status: 'not_found' }
  | { status: 'lookup_failed' };

export async function resolveMinecraftUuid(username: string): Promise<MojangLookupResult> {
  if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) {
    return { status: 'not_found' }; // fails Mojang's own username format rules
  }

  try {
    const res = await fetch(
      `https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`,
      { cache: 'no-store' }
    );
    if (res.status === 404) return { status: 'not_found' };
    if (!res.ok) {
      // Mojang's API is rate-limited and occasionally flaky - the caller
      // decides whether to proceed with an unverified username rather
      // than hard-blocking a real player over a transient API issue.
      return { status: 'lookup_failed' };
    }
    const data = (await res.json()) as { id: string; name: string };
    const raw = data.id; // Mojang returns UUIDs without dashes
    const uuid = `${raw.slice(0, 8)}-${raw.slice(8, 12)}-${raw.slice(12, 16)}-${raw.slice(16, 20)}-${raw.slice(20)}`;
    return { status: 'found', uuid, correctedUsername: data.name };
  } catch (err) {
    console.error('Mojang lookup failed:', err);
    return { status: 'lookup_failed' };
  }
}
