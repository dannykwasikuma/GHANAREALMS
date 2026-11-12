import bcrypt from "bcryptjs";
import { Router, type IRouter, type Request, type Response } from "express";
import {
  AdminLoginBody,
  AdminLoginResponse,
  CreateAdminProductBody,
  CreateAdminProductResponse,
  GetAdminClansResponse,
  GetAdminOverviewResponse,
  GetAdminProductsQueryParams,
  GetAdminProductsResponse,
  SettleClanPeriodParams,
  SettleClanPeriodResponse,
  UpdateAdminProductBody,
  UpdateAdminProductParams,
  UpdateAdminProductResponse,
} from "@workspace/api-zod";
import { adminSessionFromRequest, setAdminSessionCookie } from "../lib/admin-session";
import { getGhanaRealmsPool, grQuery, grQueryOne } from "../lib/ghanarealms-db";

const router: IRouter = Router();

function requireAdmin(req: Request, res: Response) {
  const session = adminSessionFromRequest(req);
  if (!session) {
    res.status(401).json({ error: "Unauthorized" });
    return null;
  }
  return session;
}

function intValue(value: number | string | null): number | null {
  return value === null ? null : Number(value);
}

function adminProduct(row: Record<string, unknown>) {
  return {
    id: String(row.id),
    name: String(row.name),
    slug: String(row.slug),
    category: String(row.category),
    price_pesewas: intValue(row.price_pesewas as number | string | null),
    ingame_price_minor: intValue(
      row.ingame_price_minor as number | string | null,
    ),
    stock: row.stock === null ? null : Number(row.stock),
    purchase_limit_per_player:
      row.purchase_limit_per_player === null
        ? null
        : Number(row.purchase_limit_per_player),
    discount_percent: Number(row.discount_percent ?? 0),
    active: Boolean(row.active),
    gameplay_only: Boolean(row.gameplay_only),
  };
}

router.post("/admin/login", async (req, res): Promise<void> => {
  const parsed = AdminLoginBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Missing credentials." });
    return;
  }
  if (!process.env.ADMIN_JWT_SECRET) {
    res.status(503).json({ error: "Admin sign-in is not configured." });
    return;
  }

  try {
    const admin = await grQueryOne<{
      id: string;
      username: string;
      password_hash: string;
      role: "admin" | "superadmin";
    }>(
      "SELECT id, username, password_hash, role FROM admin_users WHERE username = $1",
      [parsed.data.username],
    );
    if (!admin || !(await bcrypt.compare(parsed.data.password, admin.password_hash))) {
      res.status(401).json({ error: "Invalid credentials." });
      return;
    }

    await getGhanaRealmsPool().query(
      "UPDATE admin_users SET last_login_at = now() WHERE id = $1",
      [admin.id],
    );
    setAdminSessionCookie(res, {
      adminId: admin.id,
      username: admin.username,
      role: admin.role,
    });
    res.json(AdminLoginResponse.parse({ ok: true }));
  } catch (err) {
    req.log.error({ err }, "Admin sign-in failed");
    res.status(503).json({ error: "Admin sign-in is temporarily unavailable." });
  }
});

router.get("/admin/overview", async (req, res): Promise<void> => {
  if (!requireAdmin(req, res)) return;
  try {
    const [orders, payments, deliveries, events] = await Promise.all([
      grQuery<{ status: string; count: number | string }>(
        "SELECT status, count(*)::int AS count FROM orders GROUP BY status",
      ),
      grQuery<{ status: string; total: number | string }>(
        `SELECT status, coalesce(sum(amount_pesewas), 0) AS total
         FROM payments GROUP BY status`,
      ),
      grQuery<{ status: string; count: number | string }>(
        "SELECT status, count(*)::int AS count FROM delivery_queue GROUP BY status",
      ),
      grQuery<{
        id: string;
        paystack_reference: string;
        event_type: string;
        processed: boolean;
        received_at: Date | string;
      }>(
        `SELECT id, paystack_reference, event_type, processed, received_at
         FROM payment_events ORDER BY received_at DESC LIMIT 10`,
      ),
    ]);
    const result = GetAdminOverviewResponse.parse({
      paidTotalPesewas: Number(
        payments.find((row) => row.status === "PAID")?.total ?? 0,
      ),
      orders: orders.map((row) => ({ ...row, count: Number(row.count) })),
      deliveries: deliveries.map((row) => ({ ...row, count: Number(row.count) })),
      events: events.map((row) => ({
        ...row,
        id: String(row.id),
        received_at:
          row.received_at instanceof Date
            ? row.received_at.toISOString()
            : row.received_at,
      })),
    });
    res.json(result);
  } catch (err) {
    req.log.warn({ err }, "Admin overview unavailable");
    res.status(503).json({ error: "Admin data is temporarily unavailable." });
  }
});

router.get("/admin/products", async (req, res): Promise<void> => {
  if (!requireAdmin(req, res)) return;
  const parsed = GetAdminProductsQueryParams.safeParse(req.query);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid product filters." });
    return;
  }
  const category = parsed.data.c ?? null;
  const search = parsed.data.q?.trim() || null;
  try {
    const categories = await grQuery<{ category: string; count: number | string }>(
      `SELECT category, count(*)::int AS count
       FROM products GROUP BY category ORDER BY min(sort_order), category`,
    );
    const selectedCategory =
      categories.find((row) => row.category === category)?.category ??
      (search ? null : categories[0]?.category ?? null);
    const products = await grQuery<Record<string, unknown>>(
      `SELECT id, name, slug, category, price_pesewas, ingame_price_minor,
              stock, purchase_limit_per_player, discount_percent, active, gameplay_only
       FROM products
       WHERE ($1::text IS NULL OR category = $1)
         AND ($2::text IS NULL OR name ILIKE '%' || $2 || '%' OR slug ILIKE '%' || $2 || '%')
       ORDER BY sort_order ASC, name ASC LIMIT 300`,
      [selectedCategory, search],
    );
    res.json(
      GetAdminProductsResponse.parse({
        categories: categories.map((row) => ({
          category: row.category,
          count: Number(row.count),
        })),
        selectedCategory,
        products: products.map(adminProduct),
      }),
    );
  } catch (err) {
    req.log.warn({ err }, "Admin product list unavailable");
    res.status(503).json({ error: "Product data is temporarily unavailable." });
  }
});

router.post("/admin/products", async (req, res): Promise<void> => {
  const session = requireAdmin(req, res);
  if (!session) return;
  const parsed = CreateAdminProductBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: "Invalid product fields." });
    return;
  }
  const product = parsed.data;
  const itemSpecs = product.item_specs ?? [];
  const commands = itemSpecs.map((spec) => `gritem give {player} ${spec}`);
  const icon = itemSpecs[0]?.split(" ")[0] ?? null;
  try {
    const created = await getGhanaRealmsPool().query<{ id: string }>(
      `INSERT INTO products
        (name, slug, description, price_pesewas, ingame_price_minor, category,
         delivery_commands, item_specs, icon_material, active)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true)
       RETURNING id`,
      [
        product.name,
        product.slug,
        product.description ?? "",
        product.price_pesewas ?? null,
        product.ingame_price_minor ?? null,
        product.category,
        commands,
        itemSpecs,
        icon,
      ],
    );
    const id = created.rows[0]?.id;
    if (!id) throw new Error("Product insert returned no id.");
    await getGhanaRealmsPool().query(
      `INSERT INTO audit_logs (admin_user_id, action, target_type, target_id, details)
       VALUES ($1, 'product_created', 'product', $2, $3)`,
      [session.adminId, id, JSON.stringify({ slug: product.slug })],
    );
    res.status(201).json(
      CreateAdminProductResponse.parse({ ok: true, id: String(id) }),
    );
  } catch (err) {
    req.log.warn({ err }, "Admin product creation failed");
    res.status(400).json({ error: "Could not create product." });
  }
});

const editableProductFields = [
  "price_pesewas",
  "ingame_price_minor",
  "stock",
  "purchase_limit_per_player",
  "discount_percent",
  "active",
] as const;

router.patch("/admin/products/:id", async (req, res): Promise<void> => {
  const session = requireAdmin(req, res);
  if (!session) return;
  const parsedParams = UpdateAdminProductParams.safeParse(req.params);
  const parsedBody = UpdateAdminProductBody.safeParse(req.body);
  if (!parsedParams.success || !parsedBody.success) {
    res.status(400).json({ error: "Invalid product fields." });
    return;
  }
  const keys = editableProductFields.filter((key) =>
    Object.hasOwn(parsedBody.data, key),
  );
  if (keys.length === 0) {
    res.status(400).json({ error: "Nothing to update." });
    return;
  }

  const client = await getGhanaRealmsPool().connect();
  try {
    await client.query("BEGIN");
    const before = await client.query<Record<string, unknown>>(
      "SELECT * FROM products WHERE id = $1 FOR UPDATE",
      [parsedParams.data.id],
    );
    if (!before.rows[0]) {
      await client.query("ROLLBACK");
      res.status(404).json({ error: "Product not found." });
      return;
    }

    const setSql = keys.map((key, index) => `${key} = $${index + 2}`);
    const values = keys.map((key) => parsedBody.data[key]);
    const updated = await client.query<Record<string, unknown>>(
      `UPDATE products SET ${setSql.join(", ")}, updated_at = now()
       WHERE id = $1 RETURNING *`,
      [parsedParams.data.id, ...values],
    );
    const changes = Object.fromEntries(
      keys.map((key) => [
        key,
        {
          from: before.rows[0][key],
          to: updated.rows[0]?.[key],
        },
      ]),
    );
    await client.query(
      `INSERT INTO audit_logs (admin_user_id, action, target_type, target_id, details)
       VALUES ($1, 'product_updated', 'product', $2, $3)`,
      [session.adminId, parsedParams.data.id, JSON.stringify(changes)],
    );
    await client.query("COMMIT");
    res.json(
      UpdateAdminProductResponse.parse({
        ok: true,
        product: adminProduct(updated.rows[0] ?? {}),
      }),
    );
  } catch (err) {
    await client.query("ROLLBACK");
    req.log.warn({ err }, "Admin product update failed");
    res.status(400).json({ error: "Product update failed." });
  } finally {
    client.release();
  }
});

router.get("/admin/clans", async (req, res): Promise<void> => {
  if (!requireAdmin(req, res)) return;
  try {
    const [periods, tierCounts] = await Promise.all([
      grQuery<{
        id: string;
        period_type: string;
        period_start: Date | string;
        period_end: Date | string;
        status: string;
        reward_tier_count: number;
        vote_count: number | string;
      }>(
        `SELECT p.id, p.period_type, p.period_start, p.period_end, p.status,
                p.reward_tier_count, count(v.id)::int AS vote_count
         FROM clan_vote_periods p
         LEFT JOIN clan_votes v ON v.period_id = p.id
         GROUP BY p.id
         ORDER BY p.period_start DESC LIMIT 20`,
      ),
      grQuery<{ period_type: string; count: number | string }>(
        `SELECT period_type, count(*)::int AS count
         FROM clan_reward_tiers GROUP BY period_type`,
      ),
    ]);
    res.json(
      GetAdminClansResponse.parse({
        periods: periods.map((period) => ({
          ...period,
          id: String(period.id),
          period_start:
            period.period_start instanceof Date
              ? period.period_start.toISOString()
              : period.period_start,
          period_end:
            period.period_end instanceof Date
              ? period.period_end.toISOString()
              : period.period_end,
          vote_count: Number(period.vote_count),
        })),
        weeklyTierCount: Number(
          tierCounts.find((row) => row.period_type === "WEEKLY")?.count ?? 0,
        ),
        monthlyTierCount: Number(
          tierCounts.find((row) => row.period_type === "MONTHLY")?.count ?? 0,
        ),
      }),
    );
  } catch (err) {
    req.log.warn({ err }, "Admin clan periods unavailable");
    res.status(503).json({ error: "Clan voting data is temporarily unavailable." });
  }
});

function addPeriod(start: Date, type: "WEEKLY" | "MONTHLY"): Date {
  const end = new Date(start);
  if (type === "WEEKLY") end.setUTCDate(end.getUTCDate() + 7);
  else end.setUTCMonth(end.getUTCMonth() + 1);
  return end;
}

router.post(
  "/admin/clans/periods/:id/settle",
  async (req, res): Promise<void> => {
    const session = requireAdmin(req, res);
    if (!session) return;
    const parsed = SettleClanPeriodParams.safeParse(req.params);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid period id." });
      return;
    }
    const client = await getGhanaRealmsPool().connect();
    try {
      await client.query("BEGIN");
      const periodResult = await client.query<{
        id: string;
        period_type: "WEEKLY" | "MONTHLY";
        period_end: Date;
        status: string;
        reward_tier_count: number;
      }>(
        `SELECT id, period_type, period_end, status, reward_tier_count
         FROM clan_vote_periods WHERE id = $1 FOR UPDATE`,
        [parsed.data.id],
      );
      const period = periodResult.rows[0];
      if (!period) {
        await client.query("ROLLBACK");
        res.status(404).json({ error: "Period not found." });
        return;
      }
      if (period.status === "SETTLED") {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "This period has already been settled." });
        return;
      }
      if (period.period_end > new Date()) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "Voting has not closed yet." });
        return;
      }

      const tally = await client.query<{
        clan_id: string;
        votes: number;
      }>(
        `SELECT clan_id, count(*)::int AS votes
         FROM clan_votes WHERE period_id = $1
         GROUP BY clan_id ORDER BY votes DESC, clan_id ASC LIMIT $2`,
        [period.id, period.reward_tier_count],
      );
      const tiers = await client.query<{
        id: string;
        min_rank: number;
        max_rank: number;
      }>(
        `SELECT id, min_rank, max_rank FROM clan_reward_tiers
         WHERE period_type = $1 ORDER BY min_rank ASC`,
        [period.period_type],
      );

      const rewardsCreated: {
        clanId: string;
        rank: number;
        votes: number;
        tierId: string | null;
      }[] = [];
      for (const [index, row] of tally.rows.entries()) {
        const rank = index + 1;
        const tier = tiers.rows.find(
          (candidate) =>
            rank >= candidate.min_rank && rank <= candidate.max_rank,
        );
        if (!tier) {
          rewardsCreated.push({
            clanId: row.clan_id,
            rank,
            votes: row.votes,
            tierId: null,
          });
          continue;
        }

        const members = await client.query<{ minecraft_player_id: string }>(
          `SELECT minecraft_player_id FROM clan_members
           WHERE clan_id = $1 AND joined_at <= $2
             AND (left_at IS NULL OR left_at > $2)`,
          [row.clan_id, period.period_end],
        );
        for (const member of members.rows) {
          const reward = await client.query<{ id: string }>(
            `INSERT INTO clan_vote_rewards
              (period_id, clan_id, rank, tier_id, recipient_minecraft_player_id, vote_count)
             VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
            [
              period.id,
              row.clan_id,
              rank,
              tier.id,
              member.minecraft_player_id,
              row.votes,
            ],
          );
          await client.query(
            `INSERT INTO delivery_queue
              (order_item_id, minecraft_player_id, clan_vote_reward_id, status)
             VALUES (NULL, $1, $2, 'PENDING')`,
            [member.minecraft_player_id, reward.rows[0]?.id],
          );
        }
        rewardsCreated.push({
          clanId: row.clan_id,
          rank,
          votes: row.votes,
          tierId: tier.id,
        });
      }

      await client.query(
        `UPDATE clan_vote_periods SET status = 'SETTLED', settled_at = now()
         WHERE id = $1`,
        [period.id],
      );
      await client.query(
        `INSERT INTO audit_logs
          (admin_user_id, action, target_type, target_id, details)
         VALUES ($1, 'clan_period_settled', 'clan_vote_period', $2, $3)`,
        [session.adminId, period.id, JSON.stringify({ rewardsCreated })],
      );
      const nextStart = period.period_end;
      const nextEnd = addPeriod(nextStart, period.period_type);
      await client.query(
        `INSERT INTO clan_vote_periods
          (period_type, period_start, period_end, status, reward_tier_count)
         VALUES ($1, $2, $3, 'OPEN', $4) ON CONFLICT DO NOTHING`,
        [
          period.period_type,
          nextStart.toISOString(),
          nextEnd.toISOString(),
          period.reward_tier_count,
        ],
      );
      await client.query("COMMIT");
      res.json(
        SettleClanPeriodResponse.parse({ ok: true, rewardsCreated }),
      );
    } catch (err) {
      await client.query("ROLLBACK");
      req.log.error({ err }, "Clan period settlement failed");
      res.status(500).json({ error: "Period settlement failed." });
    } finally {
      client.release();
    }
  },
);

export default router;
