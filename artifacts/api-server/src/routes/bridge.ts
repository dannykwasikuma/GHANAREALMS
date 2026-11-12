import crypto from "node:crypto";
import { Router, type IRouter, type Request } from "express";
import { z } from "zod";
import { verifyBridgeSecret, effectivePrice, isInWindow, purchasedCount, closePendingOrder } from "../lib/bridge";
import { getGhanaRealmsPool, grQuery, grQueryOne } from "../lib/ghanarealms-db";
import { verifyTransaction, verifyWebhookSignature } from "../lib/paystack";

const router: IRouter = Router();

router.get("/bridge/pending-deliveries", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  try {
    const deliveries = await grQuery(
      `SELECT dq.id AS delivery_id, mp.username AS minecraft_username,
              mp.minecraft_uuid, 'purchase' AS source, p.name AS label,
              p.rank_permission, p.duration_days, p.delivery_commands, p.give_money_minor
       FROM delivery_queue dq
       JOIN minecraft_players mp ON mp.id = dq.minecraft_player_id
       JOIN order_items oi ON oi.id = dq.order_item_id
       JOIN products p ON p.id = oi.product_id
       WHERE dq.status IN ('PENDING', 'RETRYING') AND dq.order_item_id IS NOT NULL
       UNION ALL
       SELECT dq.id AS delivery_id, mp.username AS minecraft_username,
              mp.minecraft_uuid, 'clan_reward' AS source,
              ('Clan vote reward: ' || t.label) AS label,
              t.rank_permission, t.duration_days, t.delivery_commands, t.give_money_minor
       FROM delivery_queue dq
       JOIN minecraft_players mp ON mp.id = dq.minecraft_player_id
       JOIN clan_vote_rewards r ON r.id = dq.clan_vote_reward_id
       JOIN clan_reward_tiers t ON t.id = r.tier_id
       WHERE dq.status IN ('PENDING', 'RETRYING') AND dq.clan_vote_reward_id IS NOT NULL
       ORDER BY delivery_id ASC LIMIT 100`,
    );
    res.json({ deliveries });
  } catch (err) {
    req.log.error({ err }, "Pending deliveries query failed");
    res.status(503).json({ error: "Delivery queue is unavailable." });
  }
});

const acknowledgementSchema = z.object({
  deliveryId: z.string().uuid(),
  success: z.boolean(),
  error: z.string().optional(),
});

router.post("/bridge/ack-delivery", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const parsed = acknowledgementSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid request" });
    return;
  }
  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const current = await client.query<{
      attempt_count: number;
      status: string;
    }>(
      "SELECT attempt_count, status FROM delivery_queue WHERE id = $1 FOR UPDATE",
      [parsed.data.deliveryId],
    );
    const row = current.rows[0];
    if (!row) {
      await client.query("ROLLBACK");
      res.status(404).json({ error: "Delivery not found" });
      return;
    }
    if (row.status === "DELIVERED") {
      await client.query("ROLLBACK");
      res.json({ ok: true, alreadyDelivered: true });
      return;
    }
    const attempts = row.attempt_count + 1;
    await client.query(
      `INSERT INTO delivery_attempts (delivery_queue_id, success, error)
       VALUES ($1, $2, $3)`,
      [parsed.data.deliveryId, parsed.data.success, parsed.data.error ?? null],
    );
    if (parsed.data.success) {
      await client.query(
        `UPDATE delivery_queue SET status = 'DELIVERED', attempt_count = $1,
          delivered_at = now(), updated_at = now(), last_error = NULL WHERE id = $2`,
        [attempts, parsed.data.deliveryId],
      );
    } else {
      await client.query(
        `UPDATE delivery_queue SET status = $1, attempt_count = $2,
          last_error = $3, updated_at = now() WHERE id = $4`,
        [
          attempts >= 5 ? "FAILED" : "RETRYING",
          attempts,
          parsed.data.error ?? "Unknown error",
          parsed.data.deliveryId,
        ],
      );
    }
    await client.query("COMMIT");
    res.json({ ok: true });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "Delivery acknowledgement failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

router.get("/bridge/catalogue", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  try {
    const products = await grQuery(
      `SELECT slug, name, description, category, rarity, ingame_price_minor,
              item_specs, icon_material, stock, purchase_limit_per_player,
              limit_window_days,
              CASE WHEN discount_percent > 0
                         AND (discount_ends_at IS NULL OR discount_ends_at > now())
                   THEN floor(ingame_price_minor * (100 - discount_percent) / 100.0)::bigint
                   ELSE ingame_price_minor END AS price
       FROM products
       WHERE active = true AND gameplay_only = false
         AND ingame_price_minor IS NOT NULL AND cardinality(item_specs) > 0
         AND (available_from IS NULL OR available_from <= now())
         AND (available_until IS NULL OR available_until >= now())
       ORDER BY sort_order ASC, name ASC`,
    );
    res.json({ products });
  } catch (err) {
    req.log.error({ err }, "In-game catalogue query failed");
    res.status(503).json({ error: "Catalogue is unavailable." });
  }
});

const purchaseSchema = z.object({
  minecraftUuid: z.string().uuid(),
  minecraftUsername: z.string().min(3).max(16),
  slug: z.string().min(1),
  quantity: z.number().int().min(1).max(64).default(1),
  expectedUnitPrice: z.number().int().positive(),
});

router.post("/bridge/catalogue/purchase", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const parsed = purchaseSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid request" });
    return;
  }
  const body = parsed.data;
  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const productResult = await client.query<{
      id: string;
      active: boolean;
      gameplay_only: boolean;
      ingame_price_minor: number | string | null;
      discount_percent: number;
      discount_ends_at: Date | null;
      available_from: Date | null;
      available_until: Date | null;
      stock: number | null;
      lim: number | null;
      win: number | null;
      item_specs: string[];
    }>(
      `SELECT id, active, gameplay_only, ingame_price_minor, discount_percent,
              discount_ends_at, available_from, available_until, stock,
              purchase_limit_per_player AS lim, limit_window_days AS win, item_specs
       FROM products WHERE slug = $1 FOR UPDATE`,
      [body.slug],
    );
    const product = productResult.rows[0];
    if (
      !product ||
      !product.active ||
      product.gameplay_only ||
      product.ingame_price_minor === null ||
      !isInWindow(product.available_from, product.available_until)
    ) {
      await client.query("ROLLBACK");
      res.status(404).json({ error: "Not available" });
      return;
    }
    const unitPrice = effectivePrice(
      Number(product.ingame_price_minor),
      product.discount_percent,
      product.discount_ends_at,
    );
    if (unitPrice === null || unitPrice !== body.expectedUnitPrice) {
      await client.query("ROLLBACK");
      res.status(409).json({ error: "Price changed", unitPrice });
      return;
    }

    let player = await client.query<{ id: string }>(
      "SELECT id FROM minecraft_players WHERE minecraft_uuid = $1",
      [body.minecraftUuid],
    );
    if (!player.rows[0]) {
      player = await client.query<{ id: string }>(
        `INSERT INTO minecraft_players (username, minecraft_uuid)
         VALUES ($1, $2) RETURNING id`,
        [body.minecraftUsername, body.minecraftUuid],
      );
    }
    const playerId = player.rows[0]?.id;
    if (!playerId) throw new Error("Minecraft player insert returned no id.");

    if (product.lim !== null) {
      const have = await purchasedCount(
        client,
        playerId,
        product.id,
        product.win,
      );
      if (have + body.quantity > product.lim) {
        await client.query("ROLLBACK");
        res.status(409).json({
          error: `Limit reached (${product.lim}${product.win ? ` per ${product.win} days` : ""})`,
        });
        return;
      }
    }
    if (product.stock !== null) {
      const updated = await client.query(
        `UPDATE products SET stock = stock - $2, updated_at = now()
         WHERE id = $1 AND stock >= $2`,
        [product.id, body.quantity],
      );
      if (updated.rowCount === 0) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "Sold out" });
        return;
      }
    }
    const purchase = await client.query<{ id: string }>(
      `INSERT INTO ingame_purchases
        (product_id, minecraft_player_id, quantity, unit_price_minor)
       VALUES ($1, $2, $3, $4) RETURNING id`,
      [product.id, playerId, body.quantity, unitPrice],
    );
    await client.query("COMMIT");
    res.json({
      ok: true,
      purchaseId: purchase.rows[0]?.id,
      unitPrice,
      itemSpecs: product.item_specs,
    });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "In-game purchase reservation failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

const refundSchema = z.object({ purchaseId: z.string().uuid() });

router.post("/bridge/catalogue/refund", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const parsed = refundSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid request" });
    return;
  }
  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const deleted = await client.query<{
      product_id: string;
      quantity: number;
    }>(
      "DELETE FROM ingame_purchases WHERE id = $1 RETURNING product_id, quantity",
      [parsed.data.purchaseId],
    );
    if (deleted.rows[0]) {
      await client.query(
        `UPDATE products SET stock = stock + $2
         WHERE id = $1 AND stock IS NOT NULL`,
        [deleted.rows[0].product_id, deleted.rows[0].quantity],
      );
    }
    await client.query("COMMIT");
    res.json({ ok: true, refunded: Boolean(deleted.rows[0]) });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "In-game purchase refund failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

type ClanPeriodType = "WEEKLY" | "MONTHLY";

function periodEnd(start: Date, type: ClanPeriodType): Date {
  const end = new Date(start);
  if (type === "WEEKLY") end.setUTCDate(end.getUTCDate() + 7);
  else end.setUTCMonth(end.getUTCMonth() + 1);
  return end;
}

async function getOrCreatePeriod(
  client: { query: typeof getGhanaRealmsPool extends () => infer P ? P extends { query: infer Q } ? Q : never : never },
  type: ClanPeriodType,
) {
  const existing = await client.query(
    `SELECT id, period_start, period_end, reward_tier_count
     FROM clan_vote_periods WHERE period_type = $1 AND status = 'OPEN'`,
    [type],
  );
  if (existing.rows[0]) return existing.rows[0];
  const start = new Date();
  start.setUTCHours(0, 0, 0, 0);
  const end = periodEnd(start, type);
  const inserted = await client.query(
    `INSERT INTO clan_vote_periods (period_type, period_start, period_end, status)
     VALUES ($1, $2, $3, 'OPEN')
     RETURNING id, period_start, period_end, reward_tier_count`,
    [type, start.toISOString(), end.toISOString()],
  );
  return inserted.rows[0];
}

router.get("/bridge/clans/current-period", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const type = req.query.type;
  if (type !== "WEEKLY" && type !== "MONTHLY") {
    res.status(400).json({ error: "type must be WEEKLY or MONTHLY" });
    return;
  }
  try {
    const period = await getOrCreatePeriod(getGhanaRealmsPool(), type);
    res.json({ period });
  } catch (err) {
    req.log.error({ err }, "Current clan period unavailable");
    res.status(503).json({ error: "Clan period is unavailable." });
  }
});

const clanSyncSchema = z.object({
  clans: z.array(
    z.object({
      tag: z.string().min(1).max(16),
      name: z.string().min(1).max(64),
      leaderMinecraftUuid: z.string().uuid(),
      leaderMinecraftUsername: z.string().min(3).max(16),
      members: z.array(
        z.object({
          minecraftUuid: z.string().uuid(),
          minecraftUsername: z.string().min(3).max(16),
        }),
      ),
    }),
  ),
});

async function upsertMinecraftPlayer(
  client: { query: (text: string, values?: unknown[]) => Promise<{ rows: { id: string }[] }> },
  uuid: string,
  username: string,
): Promise<string> {
  const existing = await client.query(
    "SELECT id FROM minecraft_players WHERE minecraft_uuid = $1",
    [uuid],
  );
  if (existing.rows[0]) {
    await client.query(
      "UPDATE minecraft_players SET username = $2 WHERE id = $1",
      [existing.rows[0].id, username],
    );
    return existing.rows[0].id;
  }
  const inserted = await client.query(
    `INSERT INTO minecraft_players (username, minecraft_uuid)
     VALUES ($1, $2) RETURNING id`,
    [username, uuid],
  );
  const id = inserted.rows[0]?.id;
  if (!id) throw new Error("Minecraft player insert returned no id.");
  return id;
}

router.post("/bridge/clans/sync", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const parsed = clanSyncSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid request" });
    return;
  }
  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const seenTags: string[] = [];
    for (const input of parsed.data.clans) {
      seenTags.push(input.tag);
      const leaderId = await upsertMinecraftPlayer(
        client,
        input.leaderMinecraftUuid,
        input.leaderMinecraftUsername,
      );
      const clan = await client.query<{ id: string }>(
        `INSERT INTO clans (tag, name, leader_minecraft_player_id, active)
         VALUES ($1, $2, $3, true)
         ON CONFLICT (tag) DO UPDATE SET name = EXCLUDED.name,
          leader_minecraft_player_id = EXCLUDED.leader_minecraft_player_id,
          active = true, updated_at = now()
         RETURNING id`,
        [input.tag, input.name, leaderId],
      );
      const clanId = clan.rows[0]?.id;
      if (!clanId) throw new Error("Clan sync returned no clan id.");
      const memberIds = [leaderId];
      for (const member of input.members) {
        memberIds.push(
          await upsertMinecraftPlayer(
            client,
            member.minecraftUuid,
            member.minecraftUsername,
          ),
        );
      }
      await client.query(
        `UPDATE clan_members SET left_at = now()
         WHERE clan_id = $1 AND left_at IS NULL
           AND minecraft_player_id != ALL($2::uuid[])`,
        [clanId, memberIds],
      );
      for (const playerId of memberIds) {
        const role = playerId === leaderId ? "leader" : "member";
        const current = await client.query<{ id: string; role: string }>(
          `SELECT id, role FROM clan_members
           WHERE clan_id = $1 AND minecraft_player_id = $2 AND left_at IS NULL`,
          [clanId, playerId],
        );
        if (current.rows[0]) {
          if (current.rows[0].role !== role) {
            await client.query("UPDATE clan_members SET role = $2 WHERE id = $1", [
              current.rows[0].id,
              role,
            ]);
          }
        } else {
          await client.query(
            `UPDATE clan_members SET left_at = now()
             WHERE minecraft_player_id = $1 AND left_at IS NULL`,
            [playerId],
          );
          await client.query(
            `INSERT INTO clan_members (clan_id, minecraft_player_id, role)
             VALUES ($1, $2, $3)`,
            [clanId, playerId, role],
          );
        }
      }
    }
    if (seenTags.length > 0) {
      await client.query(
        `UPDATE clans SET active = false, updated_at = now()
         WHERE tag != ALL($1::text[]) AND active = true`,
        [seenTags],
      );
    }
    await client.query("COMMIT");
    res.json({ ok: true, clansSynced: parsed.data.clans.length });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "Clan sync failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

const clanVoteSchema = z.object({
  voterMinecraftUuid: z.string().uuid(),
  voterMinecraftUsername: z.string().min(3).max(16),
  clanTag: z.string().min(1).max(16),
  periodType: z.enum(["WEEKLY", "MONTHLY"]),
});

router.post("/bridge/clans/vote", async (req, res): Promise<void> => {
  if (!verifyBridgeSecret(req.get("x-bridge-secret"))) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  const parsed = clanVoteSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid request" });
    return;
  }
  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const clan = await client.query<{ id: string }>(
      "SELECT id FROM clans WHERE tag = $1 AND active = true",
      [parsed.data.clanTag],
    );
    if (!clan.rows[0]) {
      await client.query("ROLLBACK");
      res.status(404).json({ error: "Unknown or inactive clan tag" });
      return;
    }
    const voterId = await upsertMinecraftPlayer(
      client,
      parsed.data.voterMinecraftUuid,
      parsed.data.voterMinecraftUsername,
    );
    const ownClan = await client.query<{ clan_id: string }>(
      `SELECT clan_id FROM clan_members
       WHERE minecraft_player_id = $1 AND left_at IS NULL`,
      [voterId],
    );
    if (ownClan.rows[0]?.clan_id === clan.rows[0].id) {
      await client.query("ROLLBACK");
      res.status(400).json({ error: "You cannot vote for your own clan" });
      return;
    }
    const period = await getOrCreatePeriod(client, parsed.data.periodType);
    if (new Date(period.period_end) <= new Date()) {
      await client.query("ROLLBACK");
      res.status(409).json({ error: "Voting is closed for this period." });
      return;
    }
    const inserted = await client.query<{ id: string }>(
      `INSERT INTO clan_votes (period_id, voter_minecraft_player_id, clan_id)
       VALUES ($1, $2, $3)
       ON CONFLICT (period_id, voter_minecraft_player_id) DO NOTHING
       RETURNING id`,
      [period.id, voterId, clan.rows[0].id],
    );
    await client.query("COMMIT");
    if (!inserted.rows[0]) {
      res.json({ ok: true, alreadyVoted: true, message: "You already voted this period" });
      return;
    }
    res.json({ ok: true, alreadyVoted: false });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "Clan vote failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

function rawBodyText(req: Request): string | null {
  const requestWithRawBody = req as Request & { rawBody?: Buffer };
  return requestWithRawBody.rawBody
    ? requestWithRawBody.rawBody.toString("utf8")
    : null;
}

router.post("/payments/paystack/webhook", async (req, res): Promise<void> => {
  const rawBody = rawBodyText(req);
  if (!rawBody) {
    res.status(400).json({ error: "Missing raw webhook body." });
    return;
  }
  if (
    !verifyWebhookSignature(
      rawBody,
      req.get("x-paystack-signature") ?? undefined,
    )
  ) {
    req.log.warn("Rejected Paystack webhook with invalid signature");
    res.status(401).json({ error: "Invalid signature" });
    return;
  }

  let payload: {
    event?: string;
    data?: { reference?: string };
  };
  try {
    payload = JSON.parse(rawBody) as typeof payload;
  } catch {
    res.status(400).json({ error: "Invalid JSON" });
    return;
  }
  const eventType = payload.event ?? "unknown";
  const reference = payload.data?.reference;
  if (!reference) {
    res.json({ received: true });
    return;
  }

  const client = await getGhanaRealmsPool().connect();
  try {
    let eventId: string;
    try {
      const event = await client.query<{ id: string }>(
        `INSERT INTO payment_events (paystack_reference, event_type, raw_payload)
         VALUES ($1, $2, $3) RETURNING id`,
        [reference, eventType, payload],
      );
      eventId = event.rows[0]?.id ?? "";
    } catch (err) {
      if (
        err &&
        typeof err === "object" &&
        "code" in err &&
        err.code === "23505"
      ) {
        res.json({ received: true });
        return;
      }
      throw err;
    }
    if (eventType !== "charge.success") {
      await client.query(
        "UPDATE payment_events SET processed = true WHERE id = $1",
        [eventId],
      );
      res.json({ received: true });
      return;
    }

    const verification = await verifyTransaction(reference);
    await client.query("BEGIN");
    const paymentResult = await client.query<{
      id: string;
      order_id: string;
      amount_pesewas: number | string;
      currency: string;
      status: string;
    }>(
      `SELECT id, order_id, amount_pesewas, currency, status
       FROM payments WHERE paystack_reference = $1 FOR UPDATE`,
      [reference],
    );
    const payment = paymentResult.rows[0];
    if (!payment || payment.status === "PAID") {
      await client.query("ROLLBACK");
      res.json({ received: true });
      return;
    }
    if (!verification.ok || verification.status !== "success") {
      await client.query("UPDATE payments SET status = 'FAILED' WHERE id = $1", [
        payment.id,
      ]);
      await closePendingOrder(client, payment.order_id, "FAILED");
      await client.query("COMMIT");
      res.json({ received: true });
      return;
    }
    if (
      verification.amountPesewas !== Number(payment.amount_pesewas) ||
      verification.currency !== payment.currency
    ) {
      req.log.error(
        { reference },
        "Paystack amount or currency did not match the stored payment",
      );
      await client.query("UPDATE payments SET status = 'FAILED' WHERE id = $1", [
        payment.id,
      ]);
      await client.query("COMMIT");
      res.json({ received: true });
      return;
    }

    await client.query(
      `UPDATE payments SET status = 'PAID', channel = $1, verified_at = now()
       WHERE id = $2`,
      [verification.channel ?? null, payment.id],
    );
    await client.query(
      `UPDATE orders SET status = 'PAID', updated_at = now() WHERE id = $1`,
      [payment.order_id],
    );
    const items = await client.query<{
      order_item_id: string;
      minecraft_player_id: string;
    }>(
      `SELECT oi.id AS order_item_id, o.minecraft_player_id
       FROM order_items oi JOIN orders o ON o.id = oi.order_id
       WHERE oi.order_id = $1`,
      [payment.order_id],
    );
    for (const item of items.rows) {
      await client.query(
        `INSERT INTO delivery_queue
          (order_id, order_item_id, minecraft_player_id, status)
         VALUES ($1, $2, $3, 'PENDING')
         ON CONFLICT (order_item_id) DO NOTHING`,
        [payment.order_id, item.order_item_id, item.minecraft_player_id],
      );
    }
    await client.query(
      "UPDATE payment_events SET processed = true WHERE id = $1",
      [eventId],
    );
    await client.query("COMMIT");
    res.json({ received: true });
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.error({ err }, "Paystack webhook processing failed");
    res.status(500).json({ error: "Internal error" });
  } finally {
    client.release();
  }
});

export default router;
