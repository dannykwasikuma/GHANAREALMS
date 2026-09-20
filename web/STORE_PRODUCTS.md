# Store Products

Products are entirely database-driven (`products` table) - nothing is
hard-coded in the frontend, per section 8. The 3 seeded products
(`migrations/002_seed_example_products.sql`) are placeholders for testing
checkout end-to-end, NOT final GhanaRealms pricing - the brief explicitly
said not to invent final prices.

## Adding a real product
Currently done directly in SQL (no admin product-creation UI yet - see
STATUS.md):
```sql
INSERT INTO products (name, slug, description, price_pesewas, category,
                       rank_permission, duration_days, delivery_commands,
                       give_money_minor, active, featured, sort_order)
VALUES (
  'Your Product Name',
  'your-product-slug',
  'Description shown on the product page.',
  5000,              -- GH₵50.00, in pesewas (smallest unit)
  'rank',            -- or 'cosmetic', 'package', etc - your own categories
  'yourluckperms group',  -- NULL if this isn't a rank
  NULL,              -- or an integer for a temporary duration
  ARRAY['lp user {player} parent add yourgroup'],  -- {player} is replaced at delivery time
  0,                 -- in-game currency to grant, in minor units (0 = none)
  true, true, 1
);
```

## Field notes
- `price_pesewas`: always the smallest currency unit (pesewas for GHS) as
  an integer, never a float - avoids rounding errors.
- `delivery_commands`: run as CONSOLE commands by the Minecraft plugin,
  `{player}` is substituted with the buyer's username. These come ONLY
  from this admin-controlled column - never from anything a customer submits.
- `active = false` hides a product from the store without deleting order history.
