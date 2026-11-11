-- Backpack upgrades sold in the store. Delivered through the normal
-- delivery_queue -> GhanaRealmsPaystack plugin console-command path, which
-- runs `backpack setrows {player} N`. setrows is upgrade-only, so buying a
-- smaller tier after a bigger one is harmless. Placeholder prices - set real ones.
INSERT INTO products (name, slug, description, price_pesewas, category, delivery_commands, active, featured, sort_order) VALUES
  ('Backpack - 3 Rows', 'backpack-3-rows', 'Portable 27-slot backpack, open anywhere with /backpack.', 1500, 'item', ARRAY['backpack setrows {player} 3'], true, false, 10),
  ('Backpack - 4 Rows', 'backpack-4-rows', 'Portable 36-slot backpack.', 3000, 'item', ARRAY['backpack setrows {player} 4'], true, false, 11),
  ('Backpack - 6 Rows', 'backpack-6-rows', 'Maximum 54-slot backpack.', 6000, 'item', ARRAY['backpack setrows {player} 6'], true, true, 12)
ON CONFLICT (slug) DO NOTHING;
