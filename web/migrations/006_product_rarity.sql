-- Rarity tiers for sellable weapons/gear: BASIC < UNCOMMON < RARE < EPIC < LEGENDARY.
ALTER TABLE products
  ADD COLUMN rarity TEXT CHECK (rarity IN ('BASIC','UNCOMMON','RARE','EPIC','LEGENDARY'));
CREATE INDEX idx_products_category_rarity ON products (category, rarity);

-- (Weapon/gear products are generated in 009_catalogue.sql by scripts/generate_catalogue.py.)
