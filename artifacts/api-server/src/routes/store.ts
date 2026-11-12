import { Router, type IRouter } from "express";
import {
  GetClanLeaderboardResponse,
  GetStoreCatalogueQueryParams,
  GetStoreCatalogueResponse,
  GetStoreHomeResponse,
  GetStoreProductParams,
  GetStoreProductResponse,
  ListStoreProductsResponse,
} from "@workspace/api-zod";
import { grQuery, grQueryOne } from "../lib/ghanarealms-db";

const router: IRouter = Router();

interface ProductRow {
  slug: string;
  name: string;
  description: string;
  price_pesewas: number | string | null;
  currency: string;
  category: string;
  rarity: string | null;
  featured: boolean;
  stock: number | null;
  discount_percent: number;
  discount_ends_at: Date | string | null;
  season: string | null;
  purchase_limit_per_player: number | null;
  ingame_price_minor?: number | string | null;
  duration_days?: number | null;
  limit_window_days?: number | null;
  gameplay_only?: boolean;
  available_from?: Date | string | null;
  available_until?: Date | string | null;
  item_specs?: string[];
}

function productPayload(row: ProductRow) {
  return {
    ...row,
    price_pesewas:
      row.price_pesewas === null ? null : Number(row.price_pesewas),
    ...(row.ingame_price_minor !== undefined
      ? {
          ingame_price_minor:
            row.ingame_price_minor === null
              ? null
              : Number(row.ingame_price_minor),
        }
      : {}),
    discount_ends_at:
      row.discount_ends_at instanceof Date
        ? row.discount_ends_at.toISOString()
        : row.discount_ends_at,
    available_from:
      row.available_from instanceof Date
        ? row.available_from.toISOString()
        : row.available_from,
    available_until:
      row.available_until instanceof Date
        ? row.available_until.toISOString()
        : row.available_until,
  };
}

function reportDatabaseError(req: { log: { warn: (data: object, message: string) => void } }, err: unknown, context: string) {
  req.log.warn({ err }, `${context} unavailable`);
}

router.get("/home", async (req, res): Promise<void> => {
  try {
    const [featuredRows, recent] = await Promise.all([
      grQuery<ProductRow>(
        `SELECT slug, name, description, price_pesewas, currency, category, rarity,
                featured, stock, discount_percent, discount_ends_at, season,
                purchase_limit_per_player
         FROM products
         WHERE active = true AND featured = true
         ORDER BY sort_order ASC LIMIT 3`,
      ),
      grQuery<{ name: string; product: string }>(
        `SELECT left(mp.username, 3) AS name, p.name AS product
         FROM orders o
         JOIN minecraft_players mp ON mp.id = o.minecraft_player_id
         JOIN order_items oi ON oi.order_id = o.id
         JOIN products p ON p.id = oi.product_id
         WHERE o.status = 'PAID'
         ORDER BY o.created_at DESC LIMIT 5`,
      ),
    ]);
    const payload = GetStoreHomeResponse.parse({
      featured: featuredRows.map(productPayload),
      recent,
    });
    res.json(payload);
  } catch (err) {
    reportDatabaseError(req, err, "Store homepage");
    res.status(503).json({
      error: "Store data is unavailable. Check the database connection and schema.",
    });
  }
});

router.get("/products", async (req, res): Promise<void> => {
  try {
    const rows = await grQuery<ProductRow>(
      `SELECT slug, name, description, price_pesewas, currency, category, rarity,
              featured, stock, discount_percent, discount_ends_at, season,
              purchase_limit_per_player
       FROM products
       WHERE active = true AND gameplay_only = false AND price_pesewas IS NOT NULL
         AND (available_from IS NULL OR available_from <= now())
         AND (available_until IS NULL OR available_until >= now())
       ORDER BY sort_order ASC, name ASC`,
    );
    res.json(ListStoreProductsResponse.parse(rows.map(productPayload)));
  } catch (err) {
    reportDatabaseError(req, err, "Store products");
    res.status(503).json({
      error: "Store data is unavailable. Check the database connection and schema.",
    });
  }
});

router.get("/products/:slug", async (req, res): Promise<void> => {
  const parsedParams = GetStoreProductParams.safeParse(req.params);
  if (!parsedParams.success) {
    res.status(400).json({ error: "Invalid product slug." });
    return;
  }

  try {
    const row = await grQueryOne<ProductRow>(
      `SELECT slug, name, description, price_pesewas, currency, category, rarity,
              featured, stock, discount_percent, discount_ends_at, season,
              purchase_limit_per_player, ingame_price_minor, duration_days,
              limit_window_days, gameplay_only, available_from, available_until,
              item_specs
       FROM products WHERE slug = $1 AND active = true`,
      [parsedParams.data.slug],
    );
    if (!row) {
      res.status(404).json({ error: "Product not found." });
      return;
    }
    res.json(GetStoreProductResponse.parse(productPayload(row)));
  } catch (err) {
    reportDatabaseError(req, err, "Product details");
    res.status(503).json({
      error: "Product data is unavailable. Check the database connection and schema.",
    });
  }
});

router.get("/catalogue", async (req, res): Promise<void> => {
  const parsedQuery = GetStoreCatalogueQueryParams.safeParse(req.query);
  if (!parsedQuery.success) {
    res.status(400).json({ error: "Invalid catalogue category." });
    return;
  }

  try {
    const categories = await grQuery<{ category: string; count: number | string }>(
      `SELECT category, count(*)::int AS count
       FROM products WHERE active = true
       GROUP BY category
       ORDER BY min(sort_order) ASC, category ASC`,
    );
    const selected =
      categories.find((row) => row.category === parsedQuery.data.c)?.category ??
      categories[0]?.category ??
      null;
    const rows = selected
      ? await grQuery<ProductRow>(
          `SELECT slug, name, description, price_pesewas, currency, category, rarity,
                  featured, stock, discount_percent, discount_ends_at, season,
                  purchase_limit_per_player, ingame_price_minor, duration_days,
                  limit_window_days, gameplay_only, available_from, available_until,
                  item_specs
           FROM products
           WHERE active = true AND category = $1
             AND (available_from IS NULL OR available_from <= now())
             AND (available_until IS NULL OR available_until >= now())
           ORDER BY sort_order ASC, name ASC`,
          [selected],
        )
      : [];

    res.json(
      GetStoreCatalogueResponse.parse({
        categories: categories.map((row) => ({
          category: row.category,
          count: Number(row.count),
        })),
        selectedCategory: selected,
        items: rows.map(productPayload),
      }),
    );
  } catch (err) {
    reportDatabaseError(req, err, "Store catalogue");
    res.status(503).json({
      error: "Catalogue data is unavailable. Check the database connection and schema.",
    });
  }
});

interface PeriodRow {
  id: string;
  period_end: Date | string;
}

async function currentPeriod(type: "WEEKLY" | "MONTHLY"): Promise<PeriodRow | null> {
  return grQueryOne<PeriodRow>(
    `SELECT id, period_end FROM clan_vote_periods
     WHERE period_type = $1 AND status = 'OPEN' LIMIT 1`,
    [type],
  );
}

async function standings(periodId: string) {
  const rows = await grQuery<{
    clan_id: string;
    tag: string;
    name: string;
    votes: number | string;
  }>(
    `SELECT c.id AS clan_id, c.tag, c.name, count(v.id)::int AS votes
     FROM clans c
     LEFT JOIN clan_votes v ON v.clan_id = c.id AND v.period_id = $1
     WHERE c.active = true
     GROUP BY c.id, c.tag, c.name
     ORDER BY votes DESC, c.tag ASC
     LIMIT 20`,
    [periodId],
  );
  return rows.map((row) => ({ ...row, votes: Number(row.votes) }));
}

async function lastWinners(type: "WEEKLY" | "MONTHLY") {
  const period = await grQueryOne<{
    id: string;
    period_end: Date | string;
  }>(
    `SELECT id, period_end FROM clan_vote_periods
     WHERE period_type = $1 AND status = 'SETTLED'
     ORDER BY period_end DESC LIMIT 1`,
    [type],
  );
  if (!period) return { periodEnd: null, winners: [] };

  const winners = await grQuery<{
    rank: number;
    tag: string;
    name: string;
    vote_count: number | string;
    label: string | null;
  }>(
    `SELECT DISTINCT r.rank, c.tag, c.name, r.vote_count, t.label
     FROM clan_vote_rewards r
     JOIN clans c ON c.id = r.clan_id
     LEFT JOIN clan_reward_tiers t ON t.id = r.tier_id
     WHERE r.period_id = $1
     ORDER BY r.rank ASC`,
    [period.id],
  );
  return {
    periodEnd:
      period.period_end instanceof Date
        ? period.period_end.toISOString()
        : period.period_end,
    winners: winners.map((row) => ({
      ...row,
      vote_count: Number(row.vote_count),
    })),
  };
}

router.get("/clans", async (req, res): Promise<void> => {
  try {
    const [weekly, monthly] = await Promise.all([
      currentPeriod("WEEKLY"),
      currentPeriod("MONTHLY"),
    ]);
    const [weeklyStandings, monthlyStandings, weeklyWinners, monthlyWinners] =
      await Promise.all([
        weekly ? standings(weekly.id) : Promise.resolve([]),
        monthly ? standings(monthly.id) : Promise.resolve([]),
        lastWinners("WEEKLY"),
        lastWinners("MONTHLY"),
      ]);
    const payload = GetClanLeaderboardResponse.parse({
      weekly: {
        periodEnd: weekly
          ? weekly.period_end instanceof Date
            ? weekly.period_end.toISOString()
            : weekly.period_end
          : null,
        standings: weeklyStandings,
        winners: weeklyWinners.winners,
      },
      monthly: {
        periodEnd: monthly
          ? monthly.period_end instanceof Date
            ? monthly.period_end.toISOString()
            : monthly.period_end
          : null,
        standings: monthlyStandings,
        winners: monthlyWinners.winners,
      },
    });
    res.json(payload);
  } catch (err) {
    reportDatabaseError(req, err, "Clan standings");
    res.status(503).json({
      error: "Clan standings are unavailable. Check the database connection and schema.",
    });
  }
});

export default router;
