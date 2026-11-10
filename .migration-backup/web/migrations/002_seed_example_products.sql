-- Example products for testing the store end-to-end. These are placeholder
-- names/prices, not finalized GhanaRealms products - the brief explicitly
-- said not to invent final pricing, so treat these as seed data to verify
-- checkout works, not as the real store catalogue.

INSERT INTO products (name, slug, description, price_pesewas, category, rank_permission, active, featured, sort_order) VALUES
  ('Adinkra Pack', 'adinkra-pack', 'A starter pack of in-game currency to get going on GhanaRealms.', 2000, 'package', NULL, true, true, 1),
  ('Kente Rank', 'kente-rank', 'A supporter rank with cosmetic perks and a colored name in chat.', 5000, 'rank', 'vip', true, true, 2),
  ('Golden Stool Rank', 'golden-stool-rank', 'Our top supporter rank - full cosmetic set, priority queue, and more.', 15000, 'rank', 'champion', true, true, 3)
ON CONFLICT (slug) DO NOTHING;
