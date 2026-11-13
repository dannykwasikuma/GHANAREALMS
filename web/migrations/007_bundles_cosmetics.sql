-- Bundles and Adinkra chat titles. Titles are granted by the GhanaRealmsAdinkra plugin
-- via `adinkra grant {player} <id>` (see minecraft/GhanaRealmsAdinkra). Placeholder prices.
INSERT INTO products (name, slug, description, price_pesewas, category, rank_permission, delivery_commands, give_money_minor, featured, sort_order) VALUES
 ('Akwaaba Starter Bundle','bundle-akwaaba','Kente Rank + 3-row backpack + Fugu gear set + starter cash. Best way to begin.',6500,'bundle','vip',
   ARRAY['backpack setrows {player} 3','give {player} leather_helmet 1','give {player} leather_chestplate 1','give {player} leather_leggings 1','give {player} leather_boots 1','give {player} stone_sword 1'],5000,true,3),
 ('Asante Warrior Bundle','bundle-asante-warrior','4-row backpack + Adinkra-tier weapon and gear + Gye Nyame title.',9500,'bundle',NULL,
   ARRAY['backpack setrows {player} 4','give {player} diamond_sword 1','give {player} diamond_helmet 1','give {player} diamond_chestplate 1','give {player} diamond_leggings 1','give {player} diamond_boots 1','adinkra grant {player} gye_nyame'],0,false,4),
 ('Gye Nyame Title','title-gye-nyame','"Except God" - a gold chat title beside your name.',1000,'cosmetic',NULL,ARRAY['adinkra grant {player} gye_nyame'],0,false,40),
 ('Sankofa Title','title-sankofa','"Go back and get it" - a green chat title.',1000,'cosmetic',NULL,ARRAY['adinkra grant {player} sankofa'],0,false,41),
 ('Dwennimmen Title','title-dwennimmen','Symbol of strength and humility - a red chat title.',1000,'cosmetic',NULL,ARRAY['adinkra grant {player} dwennimmen'],0,false,42),
 ('Funtunfunefu Title','title-funtunfunefu','Unity in diversity - a gold-and-green chat title.',1000,'cosmetic',NULL,ARRAY['adinkra grant {player} funtunfunefu'],0,false,43),
 ('Nkyinkyim Title','title-nkyinkyim','Initiative and resilience - a purple chat title.',1000,'cosmetic',NULL,ARRAY['adinkra grant {player} nkyinkyim'],0,false,44)
ON CONFLICT (slug) DO NOTHING;
