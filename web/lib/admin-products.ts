import { z } from 'zod';

const nullableInt = (min = 0) => z.number().int().min(min).nullable();

/** Fields an admin may edit. All optional (PATCH); price fields are integers (pesewas / coins). */
export const productPatchSchema = z.object({
  name: z.string().min(1).max(120).optional(),
  description: z.string().max(2000).optional(),
  price_pesewas: nullableInt(1).optional(),          // null = not sold for GH₵
  ingame_price_minor: nullableInt(1).optional(),     // null = not sold in-game
  active: z.boolean().optional(),
  featured: z.boolean().optional(),
  gameplay_only: z.boolean().optional(),
  stock: nullableInt(0).optional(),                  // null = unlimited
  purchase_limit_per_player: nullableInt(1).optional(),
  limit_window_days: nullableInt(1).optional(),
  discount_percent: z.number().int().min(0).max(90).optional(),
  discount_ends_at: z.string().datetime().nullable().optional(),
  image_url: z.string().url().max(500).nullable().optional(),
  rarity: z.enum(['BASIC', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY']).nullable().optional(),
  category: z.string().min(1).max(40).optional(),
  sort_order: z.number().int().optional(),
  available_from: z.string().datetime().nullable().optional(),
  available_until: z.string().datetime().nullable().optional(),
});

export const productCreateSchema = productPatchSchema.extend({
  name: z.string().min(1).max(120),
  slug: z.string().regex(/^[a-z0-9]+(-[a-z0-9]+)*$/).max(80),
  category: z.string().min(1).max(40),
  /** Items handed over, `gritem` spec format, e.g. "DIAMOND_SWORD 1 ench=SHARPNESS:3". */
  item_specs: z.array(z.string().max(300)).max(40).default([]),
});

export const EDITABLE = [
  'name', 'description', 'price_pesewas', 'ingame_price_minor', 'active', 'featured', 'gameplay_only', 'stock',
  'purchase_limit_per_player', 'limit_window_days', 'discount_percent', 'discount_ends_at', 'image_url', 'rarity',
  'category', 'sort_order', 'available_from', 'available_until',
] as const;
