#!/usr/bin/env python3
"""Generates migrations/009_catalogue.sql - the GhanaRealms product catalogue.

Run:  python3 scripts/generate_catalogue.py > migrations/009_catalogue.sql

Everything lives here as data so prices/items are reviewable in one place. After the
migration is applied, ALL of it is editable from /admin/products (prices, stock, limits,
discounts, enable/disable) - re-running the migration never overwrites admin edits
(ON CONFLICT (slug) DO NOTHING).

Rules enforced by this script (it aborts if violated):
  * every enchantment on an item must be applicable to that item type,
    within the vanilla max level, and not mutually exclusive with another on the same item
    (so no impossible / incompatible combinations can ever be sold);
  * item prices of enchanted gear = base price + 80% of the matching enchanted-book prices,
    so prices stay consistent across the catalogue.

PRICING MODEL (starting values - tune in the admin dashboard):
  In-game currency ("coins"): 1 iron ingot ~ 25, 1 diamond ~ 400, 1 netherite ingot ~ 5,000.
  Basic food ~ 5-15 per portion, so a beginner can eat from day one; gear is priced at
  roughly 1.5-2x the raw material cost so crafting stays the cheaper path.
  Real money (GH₵): ONLY cosmetics, food baskets and beginner/explorer bundles are sold
  for cedis, with per-player limits. Powerful/endgame items (netherite, elytra, totems,
  nether stars, rare enchants, exclusive weapons) are in-game currency or gameplay only,
  so there is no pay-to-win. No exchange rate between the two is assumed anywhere.
"""
import sys

# ---------------------------------------------------------------- enchantments
ENCH = {  # name: (max_level, base_price, treasure)
    "protection": (4, 150, False), "fire_protection": (4, 120, False),
    "blast_protection": (4, 150, False), "projectile_protection": (4, 120, False),
    "thorns": (3, 200, False), "respiration": (3, 200, False), "aqua_affinity": (1, 250, False),
    "feather_falling": (4, 130, False), "depth_strider": (3, 220, False),
    "frost_walker": (2, 1200, True), "soul_speed": (3, 1000, True), "swift_sneak": (3, 1000, True),
    "unbreaking": (3, 200, False), "mending": (1, 4500, True),
    "sharpness": (5, 150, False), "smite": (5, 100, False), "bane_of_arthropods": (5, 100, False),
    "knockback": (2, 120, False), "fire_aspect": (2, 200, False), "looting": (3, 300, False),
    "sweeping_edge": (3, 250, False),
    "efficiency": (5, 200, False), "silk_touch": (1, 800, False), "fortune": (3, 400, False),
    "power": (5, 150, False), "punch": (2, 150, False), "flame": (1, 300, False), "infinity": (1, 1500, False),
    "luck_of_the_sea": (3, 250, False), "lure": (3, 250, False),
    "loyalty": (3, 300, False), "impaling": (5, 200, False), "riptide": (3, 400, False), "channeling": (1, 500, False),
    "multishot": (1, 500, False), "piercing": (4, 150, False), "quick_charge": (3, 250, False),
    "density": (5, 400, False), "breach": (4, 400, False), "wind_burst": (3, 800, True),
}
ENCH_DESC = {
    "protection": "Reduces most damage.", "fire_protection": "Reduces fire and lava damage.",
    "blast_protection": "Reduces explosion damage.", "projectile_protection": "Reduces arrow and projectile damage.",
    "thorns": "Reflects damage onto attackers.", "respiration": "Breathe longer underwater.",
    "aqua_affinity": "Mine at normal speed underwater.", "feather_falling": "Reduces fall damage.",
    "depth_strider": "Move faster underwater.", "frost_walker": "Freezes water under your feet.",
    "soul_speed": "Move faster on soul sand and soil.", "swift_sneak": "Sneak faster.",
    "unbreaking": "Items last longer.", "mending": "Repairs the item with XP.",
    "sharpness": "Extra melee damage.", "smite": "Extra damage to undead.",
    "bane_of_arthropods": "Extra damage to spiders and silverfish.", "knockback": "Knocks targets back further.",
    "fire_aspect": "Sets targets on fire.", "looting": "More mob drops.", "sweeping_edge": "Stronger sweep attacks.",
    "efficiency": "Mine faster.", "silk_touch": "Mine blocks as themselves.", "fortune": "More ore drops.",
    "power": "Extra arrow damage.", "punch": "Arrow knockback.", "flame": "Flaming arrows.",
    "infinity": "Shooting does not use arrows.", "luck_of_the_sea": "Better fishing loot.", "lure": "Fish bite faster.",
    "loyalty": "Trident returns after a throw.", "impaling": "Extra damage to aquatic mobs.",
    "riptide": "Launches you with the trident in water or rain.", "channeling": "Summons lightning in storms.",
    "multishot": "Fires three arrows.", "piercing": "Arrows pass through mobs.", "quick_charge": "Faster crossbow reload.",
    "density": "Mace smash damage scales with fall.", "breach": "Mace ignores part of the armour.",
    "wind_burst": "Mace smash launches you upward.",
}
ROMAN = {1: "I", 2: "II", 3: "III", 4: "IV", 5: "V"}

ARMOR = {"helmet", "chestplate", "leggings", "boots"}
APPLIES = {
    "armor": {"protection", "fire_protection", "blast_protection", "projectile_protection", "thorns", "unbreaking", "mending"},
    "helmet": {"respiration", "aqua_affinity"}, "leggings": {"swift_sneak"},
    "boots": {"feather_falling", "depth_strider", "frost_walker", "soul_speed"},
    "sword": {"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "unbreaking", "mending"},
    "axe": {"sharpness", "smite", "bane_of_arthropods", "efficiency", "silk_touch", "fortune", "unbreaking", "mending"},
    "tool": {"efficiency", "silk_touch", "fortune", "unbreaking", "mending"},
    "bow": {"power", "punch", "flame", "infinity", "unbreaking", "mending"},
    "crossbow": {"multishot", "piercing", "quick_charge", "unbreaking", "mending"},
    "trident": {"impaling", "riptide", "loyalty", "channeling", "unbreaking", "mending"},
    "mace": {"density", "breach", "wind_burst", "smite", "bane_of_arthropods", "fire_aspect", "unbreaking", "mending"},
    "shield": {"unbreaking", "mending"}, "elytra": {"unbreaking", "mending"},
    "fishing_rod": {"luck_of_the_sea", "lure", "unbreaking", "mending"},
}
EXCLUSIVE = [
    {"protection", "fire_protection", "blast_protection", "projectile_protection"},
    {"depth_strider", "frost_walker"}, {"sharpness", "smite", "bane_of_arthropods"},
    {"density", "breach", "smite", "bane_of_arthropods"}, {"infinity", "mending"},
    {"multishot", "piercing"}, {"loyalty", "riptide"}, {"riptide", "channeling"}, {"fortune", "silk_touch"},
]


def kind(material):
    m = material.upper()
    for a in ARMOR:
        if m.endswith("_" + a.upper()):
            return a
    for k in ("SWORD", "AXE", "PICKAXE", "SHOVEL", "HOE"):
        if m.endswith("_" + k):
            return "sword" if k == "SWORD" else "axe" if k == "AXE" else "tool"
    return {"BOW": "bow", "CROSSBOW": "crossbow", "TRIDENT": "trident", "MACE": "mace",
            "SHIELD": "shield", "ELYTRA": "elytra", "FISHING_ROD": "fishing_rod"}.get(m)


def allowed(material):
    k = kind(material)
    if k is None:
        return set()
    s = set(APPLIES.get(k, set()))
    if k in ARMOR:
        s |= APPLIES["armor"]
    return s


def validate(material, ench):
    ok = allowed(material)
    for n, lvl in ench.items():
        if n not in ENCH:
            raise SystemExit(f"unknown enchantment {n}")
        if n not in ok:
            raise SystemExit(f"{n} cannot go on {material}")
        if not 1 <= lvl <= ENCH[n][0]:
            raise SystemExit(f"{n} level {lvl} exceeds max {ENCH[n][0]} ({material})")
    for grp in EXCLUSIVE:
        if len(grp & set(ench)) > 1:
            raise SystemExit(f"incompatible enchantments {grp & set(ench)} on {material}")


def book_price(n, lvl):
    mx, base, treasure = ENCH[n]
    if mx == 1:
        return base
    return int(round(base * (lvl ** 1.7) / 10.0)) * 10


def ench_cost(ench):
    return sum(book_price(n, l) for n, l in ench.items())


def r10(x):
    return int(round(x / 10.0)) * 10


def spec(material, amount=1, ench=None, name=None, potion=None, lore=None):
    ench = ench or {}
    if ench:
        validate(material, ench)
    parts = [material, str(amount)]
    if name:
        parts.append("name=" + name.replace(" ", "_"))
    if ench:
        parts.append("ench=" + ",".join(f"{n.upper()}:{l}" for n, l in ench.items()))
    if potion:
        parts.append("potion=" + potion)
    if lore:
        parts.append("lore=" + lore.replace(" ", "_"))
    return " ".join(parts)


# ---------------------------------------------------------------- output helpers
PRODUCTS = []
SLUGS = set()


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


def arr(xs):
    return "ARRAY[" + ",".join(q(x) for x in xs) + "]::text[]" if xs else "'{}'::text[]"


def add(slug, name, desc, category, rarity, ingame, cash, specs, icon=None, stock=None, limit=None,
        window=None, gameplay_only=False, season=None, avail=None, featured=False, commands=None):
    if slug in SLUGS:
        raise SystemExit("duplicate slug " + slug)
    SLUGS.add(slug)
    cmds = commands if commands is not None else [f"gritem give {{player}} {s}" for s in specs]
    icon = icon or (specs[0].split(" ")[0] if specs else None)
    PRODUCTS.append(dict(slug=slug, name=name, desc=desc, category=category, rarity=rarity, ingame=ingame, cash=cash,
                         specs=specs, cmds=cmds, icon=icon, stock=stock, limit=limit, window=window,
                         gameplay_only=gameplay_only, season=season, avail=avail, featured=featured))


# ================================================================ ARMOUR
PIECES = ("helmet", "chestplate", "leggings", "boots")
ARM = {  # material: (display, [helm, chest, legs, boots] prices, rarity)
    "LEATHER": ("Savanna Leather", [60, 100, 90, 50], "BASIC"),
    "CHAINMAIL": ("Volta Mail", [150, 250, 220, 130], "BASIC"),
    "IRON": ("Kumasi Iron", [160, 260, 230, 150], "UNCOMMON"),
    "GOLDEN": ("Gold Coast", [150, 240, 210, 130], "UNCOMMON"),
    "DIAMOND": ("Adinkra Diamond", [1200, 1900, 1700, 1200], "RARE"),
    "NETHERITE": ("Golden Stool Netherite", [5000, 8000, 7000, 5000], "EPIC"),
}
LABEL = {"helmet": "Helmet", "chestplate": "Chestplate", "leggings": "Leggings", "boots": "Boots"}

for mat, (disp, prices, rar) in ARM.items():
    for pc, pr in zip(PIECES, prices):
        lim = (1, 7) if mat == "NETHERITE" else (None, None)
        add(f"armour-{mat.lower()}-{pc}", f"{disp} {LABEL[pc]}", f"Unenchanted {mat.title()} {LABEL[pc].lower()}.",
            "armour", rar, pr, None, [spec(f"{mat}_{pc.upper()}")], limit=lim[0], window=lim[1],
            gameplay_only=False)
    add(f"armour-{mat.lower()}-set", f"{disp} Armour Set", f"Full {mat.title()} set, unenchanted: helmet, chestplate, leggings, boots.",
        "armour", rar, sum(prices), None, [spec(f"{mat}_{p.upper()}") for p in PIECES],
        icon=f"{mat}_CHESTPLATE", limit=(1 if mat == "NETHERITE" else None), window=(7 if mat == "NETHERITE" else None))

TIERS = {  # tier: base enchants applied to every piece
    "Beginner": {"protection": 1, "unbreaking": 1},
    "Intermediate": {"protection": 2, "unbreaking": 2},
    "Advanced": {"protection": 3, "unbreaking": 3},
    "Premium": {"protection": 4, "unbreaking": 3, "mending": 1},
}
PREMIUM_EXTRA = {"helmet": {"respiration": 3, "aqua_affinity": 1}, "chestplate": {"thorns": 2},
                 "leggings": {"swift_sneak": 3}, "boots": {"feather_falling": 4, "depth_strider": 3}}
PLAN = {"IRON": ["Beginner", "Intermediate"], "DIAMOND": ["Intermediate", "Advanced", "Premium"],
        "NETHERITE": ["Advanced", "Premium"]}


def enchanted_set(mat, tier):
    pieces = []
    for pc in PIECES:
        e = dict(TIERS[tier])
        if tier == "Premium":
            e.update(PREMIUM_EXTRA[pc])
        pieces.append((f"{mat}_{pc.upper()}", e))
    return pieces


for mat, tiers in PLAN.items():
    disp, prices, rar = ARM[mat]
    for tier in tiers:
        pcs = enchanted_set(mat, tier)
        price = sum(ARM[mat][1]) + int(0.8 * sum(ench_cost(e) for _, e in pcs))
        extra = " Adds Respiration III, Aqua Affinity, Thorns II, Swift Sneak III, Feather Falling IV, Depth Strider III." if tier == "Premium" else ""
        prot = TIERS[tier]
        txt = f"Protection {ROMAN[prot['protection']]}, Unbreaking {ROMAN[prot['unbreaking']]}" + (", Mending." if prot.get("mending") else ".")
        rarity = "EPIC" if (mat == "NETHERITE" or tier == "Premium") else rar
        add(f"armour-{mat.lower()}-{tier.lower()}-set", f"{disp} {tier} Set",
            f"{mat.title()} full set - {txt}{extra}", "armour", rarity, r10(price), None,
            [spec(m, 1, e) for m, e in pcs], icon=f"{mat}_CHESTPLATE",
            limit=(1 if mat == "NETHERITE" else None), window=(7 if mat == "NETHERITE" else None))

# specialist sets (single protection type, never mixed)
for mat in ("DIAMOND", "NETHERITE"):
    for prot, label in (("fire_protection", "Fire"), ("blast_protection", "Blast"), ("projectile_protection", "Projectile")):
        e = {prot: 4, "unbreaking": 3, "mending": 1}
        price = sum(ARM[mat][1]) + int(0.8 * 4 * ench_cost(e))
        add(f"armour-{mat.lower()}-{label.lower()}-guard-set", f"{ARM[mat][0]} {label} Guard Set",
            f"{mat.title()} full set with {label} Protection IV, Unbreaking III, Mending.", "armour",
            "EPIC", r10(price), None, [spec(f"{mat}_{p.upper()}", 1, e) for p in PIECES],
            icon=f"{mat}_CHESTPLATE", limit=1, window=7)

# specialist single pieces
SPECIAL = [
    ("diver-helmet", "Diver's Helmet", "NETHERITE_HELMET", {"protection": 4, "respiration": 3, "aqua_affinity": 1, "unbreaking": 3, "mending": 1}, "Breathe and mine underwater."),
    ("nether-runner-boots", "Nether Runner Boots", "NETHERITE_BOOTS", {"protection": 4, "soul_speed": 3, "feather_falling": 4, "unbreaking": 3, "mending": 1}, "Fast across soul sand and soil."),
    ("ice-walker-boots", "Ice Walker Boots", "DIAMOND_BOOTS", {"protection": 4, "frost_walker": 2, "feather_falling": 4, "unbreaking": 3, "mending": 1}, "Walk over water on ice."),
    ("quiet-steps-leggings", "Quiet Steps Leggings", "NETHERITE_LEGGINGS", {"protection": 4, "swift_sneak": 3, "unbreaking": 3, "mending": 1}, "Sneak at speed."),
    ("thornmail-chestplate", "Thornmail Chestplate", "NETHERITE_CHESTPLATE", {"protection": 4, "thorns": 3, "unbreaking": 3, "mending": 1}, "Reflects damage onto attackers."),
]
for slug, nm, mat, e, d in SPECIAL:
    base = ARM[mat.split("_")[0]][1][PIECES.index(mat.split("_")[1].lower())]
    add("armour-" + slug, nm, d + " " + ", ".join(f"{k.replace('_', ' ').title()} {ROMAN[v]}" for k, v in e.items()) + ".",
        "armour", "EPIC", r10(base + 0.8 * ench_cost(e)), None, [spec(mat, 1, e)], limit=1, window=7)

# ================================================================ WEAPONS & TOOLS
WBASE = {
    "STONE_SWORD": ("Stone Sword", 60, "BASIC"), "IRON_SWORD": ("Iron Sword", 250, "UNCOMMON"),
    "GOLDEN_SWORD": ("Gold Sword", 220, "UNCOMMON"), "DIAMOND_SWORD": ("Diamond Sword", 1800, "RARE"),
    "NETHERITE_SWORD": ("Netherite Sword", 8000, "EPIC"),
    "IRON_AXE": ("Iron Axe", 300, "UNCOMMON"), "DIAMOND_AXE": ("Diamond Axe", 2000, "RARE"), "NETHERITE_AXE": ("Netherite Axe", 8500, "EPIC"),
    "BOW": ("Bow", 200, "BASIC"), "CROSSBOW": ("Crossbow", 350, "UNCOMMON"), "TRIDENT": ("Trident", 7000, "EPIC"),
    "SHIELD": ("Shield", 150, "BASIC"),
}
for mat, (nm, pr, rar) in WBASE.items():
    add("weapon-" + mat.lower().replace("_", "-"), nm, f"Unenchanted {nm.lower()}.", "weapon", rar, pr, None, [spec(mat)],
        limit=(1 if mat in ("NETHERITE_SWORD", "NETHERITE_AXE", "TRIDENT") else None),
        window=(7 if mat in ("NETHERITE_SWORD", "NETHERITE_AXE", "TRIDENT") else None))
add("weapon-mace", "Mace", "Heavy smash weapon. Progression-limited: one per player per 14 days.", "weapon", "EPIC", 15000, None, [spec("MACE")], limit=1, window=14)
add("weapon-arrows-64", "Arrows x64", "A full stack of arrows.", "weapon", "BASIC", 120, None, [spec("ARROW", 64)])
add("weapon-spectral-arrows-32", "Spectral Arrows x32", "Highlight targets through walls.", "weapon", "UNCOMMON", 200, None, [spec("SPECTRAL_ARROW", 32)])

WTIERS = {
    "sword": {"Intermediate": {"sharpness": 3, "unbreaking": 2},
              "Advanced": {"sharpness": 4, "unbreaking": 3, "looting": 2, "fire_aspect": 1},
              "Premium": {"sharpness": 5, "unbreaking": 3, "mending": 1, "looting": 3, "fire_aspect": 2, "sweeping_edge": 3}},
    "axe": {"Intermediate": {"sharpness": 3, "efficiency": 3, "unbreaking": 2},
            "Advanced": {"sharpness": 4, "efficiency": 4, "unbreaking": 3},
            "Premium": {"sharpness": 5, "efficiency": 5, "unbreaking": 3, "mending": 1}},
    "bow": {"Intermediate": {"power": 3, "unbreaking": 2}, "Advanced": {"power": 4, "punch": 1, "unbreaking": 3, "flame": 1},
            "Premium": {"power": 5, "punch": 2, "flame": 1, "unbreaking": 3, "infinity": 1}},
    "crossbow": {"Intermediate": {"quick_charge": 2, "unbreaking": 2}, "Advanced": {"quick_charge": 3, "piercing": 3, "unbreaking": 3},
                 "Premium": {"quick_charge": 3, "multishot": 1, "unbreaking": 3, "mending": 1}},
    "trident": {"Advanced": {"impaling": 4, "loyalty": 3, "unbreaking": 3}, "Premium": {"impaling": 5, "loyalty": 3, "channeling": 1, "unbreaking": 3, "mending": 1}},
    "shield": {"Advanced": {"unbreaking": 3}, "Premium": {"unbreaking": 3, "mending": 1}},
}
WKIND = {"STONE_SWORD": "sword", "IRON_SWORD": "sword", "GOLDEN_SWORD": "sword", "DIAMOND_SWORD": "sword", "NETHERITE_SWORD": "sword",
         "IRON_AXE": "axe", "DIAMOND_AXE": "axe", "NETHERITE_AXE": "axe", "BOW": "bow", "CROSSBOW": "crossbow",
         "TRIDENT": "trident", "SHIELD": "shield"}
ENCH_FOR = ["IRON_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD", "DIAMOND_AXE", "NETHERITE_AXE", "BOW", "CROSSBOW", "TRIDENT", "SHIELD"]
for mat in ENCH_FOR:
    nm, pr, rar = WBASE[mat]
    for tier, e in WTIERS[WKIND[mat]].items():
        if mat == "IRON_SWORD" and tier == "Premium":
            continue
        price = pr + int(0.8 * ench_cost(e))
        add(f"weapon-{mat.lower().replace('_', '-')}-{tier.lower()}", f"{nm} - {tier}",
            ", ".join(f"{k.replace('_', ' ').title()} {ROMAN[v]}" if ENCH[k][0] > 1 else k.replace('_', ' ').title() for k, v in e.items()) + ".",
            "weapon", "EPIC" if tier == "Premium" else rar, r10(price), None, [spec(mat, 1, e)],
            limit=(1 if mat.startswith("NETHERITE") or mat == "TRIDENT" else None),
            window=(7 if mat.startswith("NETHERITE") or mat == "TRIDENT" else None))
mace_e = {"density": 4, "breach": 0}
mace_e = {"density": 5, "unbreaking": 3, "mending": 1}
add("weapon-mace-premium", "Mace - Premium", "Density V, Unbreaking III, Mending.", "weapon", "EPIC",
    r10(15000 + 0.8 * ench_cost(mace_e)), None, [spec("MACE", 1, mace_e)], limit=1, window=14)

# exclusive GhanaRealms collections (limited stock, in-game only)
EXCL = [
    ("asante-gold-blade", "Asante Gold Blade", "NETHERITE_SWORD", {"sharpness": 5, "looting": 3, "fire_aspect": 2, "sweeping_edge": 3, "unbreaking": 3, "mending": 1},
     "Asante Gold Blade", "Forged for champions of the Ashanti Kingdom.", 40),
    ("golden-stool-axe", "Golden Stool Warden Axe", "NETHERITE_AXE", {"sharpness": 5, "efficiency": 5, "unbreaking": 3, "mending": 1},
     "Golden Stool Warden Axe", "A chieftain's axe that doubles as a lumberjack's best friend.", 40),
    ("kente-longbow", "Kente Longbow", "BOW", {"power": 5, "punch": 2, "flame": 1, "unbreaking": 3, "infinity": 1},
     "Kente Longbow", "Woven strings, hunter's aim.", 40),
    ("adinkra-trident", "Adinkra Trident", "TRIDENT", {"impaling": 5, "loyalty": 3, "channeling": 1, "unbreaking": 3, "mending": 1},
     "Adinkra Trident", "Etched with Gye Nyame.", 25),
    ("sankofa-crossbow", "Sankofa Crossbow", "CROSSBOW", {"quick_charge": 3, "piercing": 4, "unbreaking": 3, "mending": 1},
     "Sankofa Crossbow", "Go back and get it - every bolt flies through.", 40),
    ("gye-nyame-shield", "Gye Nyame Shield", "SHIELD", {"unbreaking": 3, "mending": 1},
     "Gye Nyame Shield", "Except God. Nothing gets through.", 40),
]
for slug, nm, mat, e, iname, d, stock in EXCL:
    base = WBASE[mat][1]
    add("collection-" + slug, nm, d + " Exclusive GhanaRealms collection - limited stock, one per player.", "weapon", "LEGENDARY",
        r10((base + 0.8 * ench_cost(e)) * 1.5), None, [spec(mat, 1, e, name=iname)], stock=stock, limit=1)

# tools (mining packages)
TOOLS = {"IRON_PICKAXE": ("Iron Pickaxe", 280), "DIAMOND_PICKAXE": ("Diamond Pickaxe", 2000), "NETHERITE_PICKAXE": ("Netherite Pickaxe", 8500),
         "DIAMOND_SHOVEL": ("Diamond Shovel", 1000), "FISHING_ROD": ("Fishing Rod", 80)}
for mat, (nm, pr) in TOOLS.items():
    add("tool-" + mat.lower().replace("_", "-"), nm, f"Unenchanted {nm.lower()}.", "tool", "RARE" if "DIAMOND" in mat else "UNCOMMON" if "IRON" in mat else "EPIC" if "NETH" in mat else "BASIC",
        pr, None, [spec(mat)], limit=(1 if "NETHERITE" in mat else None), window=(7 if "NETHERITE" in mat else None))
MINING = {"Miner's": {"efficiency": 4, "unbreaking": 3}, "Prospector's (Fortune)": {"efficiency": 5, "fortune": 3, "unbreaking": 3, "mending": 1},
          "Collector's (Silk Touch)": {"efficiency": 5, "silk_touch": 1, "unbreaking": 3, "mending": 1}}
for label, e in MINING.items():
    for mat in ("DIAMOND_PICKAXE", "NETHERITE_PICKAXE"):
        nm, pr = TOOLS[mat]
        slug = "tool-" + mat.lower().replace("_", "-") + "-" + label.split(" ")[0].lower().replace("'s", "")
        add(slug, f"{label} {nm.split(' ')[0]} Pickaxe", ", ".join(f"{k.replace('_', ' ').title()} {ROMAN[v]}" if ENCH[k][0] > 1 else k.replace('_', ' ').title() for k, v in e.items()) + ".",
            "tool", "EPIC", r10(pr + 0.8 * ench_cost(e)), None, [spec(mat, 1, e)],
            limit=(1 if mat == "NETHERITE_PICKAXE" else None), window=(7 if mat == "NETHERITE_PICKAXE" else None))
add("tool-lucky-rod", "Lucky Fishing Rod", "Luck of the Sea III, Lure III, Unbreaking III, Mending.", "tool", "RARE",
    r10(80 + 0.8 * ench_cost({"luck_of_the_sea": 3, "lure": 3, "unbreaking": 3, "mending": 1})), None,
    [spec("FISHING_ROD", 1, {"luck_of_the_sea": 3, "lure": 3, "unbreaking": 3, "mending": 1})])

# ================================================================ ELYTRA & RARE
add("rare-elytra", "Elytra", "Glide the Ghanaian skies. One per player per 14 days.", "rare", "EPIC", 30000, None, [spec("ELYTRA")], limit=1, window=14)
add("rare-elytra-unbreaking", "Elytra - Unbreaking III", "Elytra with Unbreaking III.", "rare", "EPIC", r10(30000 + 0.8 * ench_cost({"unbreaking": 3})), None,
    [spec("ELYTRA", 1, {"unbreaking": 3})], limit=1, window=14)
add("rare-elytra-mending", "Elytra - Mending", "Elytra with Mending.", "rare", "EPIC", r10(30000 + 0.8 * ench_cost({"mending": 1})), None,
    [spec("ELYTRA", 1, {"mending": 1})], limit=1, window=14)
add("rare-elytra-premium", "Elytra - Premium", "Unbreaking III and Mending. The best wings on GhanaRealms.", "rare", "LEGENDARY",
    r10(30000 + 0.8 * ench_cost({"unbreaking": 3, "mending": 1})), None, [spec("ELYTRA", 1, {"unbreaking": 3, "mending": 1})], limit=1, window=14, stock=60)
add("rare-rockets-16", "Firework Rockets x16", "Flight duration 2 rockets.", "rare", "UNCOMMON", 160, None, [spec("FIREWORK_ROCKET", 16)])
add("rare-rockets-64", "Firework Rockets x64", "A stack of rockets.", "rare", "UNCOMMON", 600, None, [spec("FIREWORK_ROCKET", 64)])
add("rare-ender-pearls-16", "Ender Pearls x16", "Travel essentials. Limited to 2 bundles per player per day.", "rare", "UNCOMMON", 1100, None, [spec("ENDER_PEARL", 16)], limit=2, window=1)
add("rare-ender-pearls-bundle", "Ender Pearl Bundle x48", "Three stacks of 16. 1 per player per 3 days.", "rare", "RARE", 3000, None, [spec("ENDER_PEARL", 16)] * 3, icon="ENDER_PEARL", limit=1, window=3)
add("rare-shulker-shell", "Shulker Shell x2", "Enough for one shulker box.", "rare", "RARE", 2400, None, [spec("SHULKER_SHELL", 2)], limit=3, window=7)
add("rare-shulker-box", "Shulker Box", "Carry your whole market stall. 3 per player per week.", "rare", "RARE", 3000, None, [spec("SHULKER_BOX")], limit=3, window=7)
add("rare-totem", "Totem of Undying", "Cheat death once. 2 per player per week.", "rare", "EPIC", 4000, None, [spec("TOTEM_OF_UNDYING")], limit=2, window=7)
add("rare-nether-star", "Nether Star", "For beacons. Limited stock: restocked by admins only.", "rare", "LEGENDARY", 20000, None, [spec("NETHER_STAR")], stock=10, limit=1, window=7)
add("rare-wither-skull", "Wither Skeleton Skull", "Limited stock.", "rare", "EPIC", 6000, None, [spec("WITHER_SKELETON_SKULL")], stock=15, limit=2, window=7)
add("rare-dragon-breath", "Dragon's Breath x4", "Bottles for lingering potions.", "rare", "EPIC", 2400, None, [spec("DRAGON_BREATH", 4)], limit=2, window=7)
add("rare-dragon-head", "Dragon Head", "Trophy. Very limited stock.", "rare", "LEGENDARY", 30000, None, [spec("DRAGON_HEAD")], stock=3, limit=1)
add("rare-dragon-egg", "Dragon Egg", "Earned in gameplay only - not for sale.", "rare", "LEGENDARY", None, None, [], icon="DRAGON_EGG", gameplay_only=True)
add("rare-beacon", "Beacon", "Earned in gameplay only - build it from a Nether Star.", "rare", "EPIC", None, None, [], icon="BEACON", gameplay_only=True)
add("rare-explorer-kit", "Explorer's Kit", "Compass, spyglass, clock, 4 maps, 16 torches.", "rare", "UNCOMMON", 700, None,
    [spec("COMPASS"), spec("SPYGLASS"), spec("CLOCK"), spec("MAP", 4), spec("TORCH", 16)], icon="SPYGLASS")
add("rare-recovery-compass", "Recovery Compass", "Points to your last death. Needs 8 Echo Shards in crafting otherwise.", "rare", "RARE", 2500, None, [spec("RECOVERY_COMPASS")], limit=1, window=7)

# ================================================================ ENCHANTED BOOKS
BOOKS = {n: v for n, v in ENCH.items()}
for n, (mx, base, treasure) in sorted(BOOKS.items()):
    for lvl in range(1, mx + 1):
        add(f"book-{n.replace('_', '-')}-{lvl}", f"{n.replace('_', ' ').title()} {ROMAN[lvl]} Book",
            f"{ENCH_DESC[n]} {'Treasure enchantment. ' if treasure else ''}Max level {ROMAN[mx]}.",
            "enchantment", "EPIC" if treasure else ("RARE" if lvl == mx and mx > 1 else "UNCOMMON" if lvl > 1 else "BASIC"),
            book_price(n, lvl), None, [spec("ENCHANTED_BOOK", 1, {n: lvl})] if False else [f"ENCHANTED_BOOK 1 ench={n.upper()}:{lvl}"],
            icon="ENCHANTED_BOOK", limit=(1 if treasure else None), window=(7 if treasure else None))

BUNDLES_E = {
    "armour": ("Armour Enchantment Package", "Protection IV, Unbreaking III, Respiration III, Feather Falling IV books.",
               [("protection", 4), ("unbreaking", 3), ("respiration", 3), ("feather_falling", 4)]),
    "weapon": ("Weapon Enchantment Package", "Sharpness V, Looting III, Fire Aspect II, Sweeping Edge III books.",
               [("sharpness", 5), ("looting", 3), ("fire_aspect", 2), ("sweeping_edge", 3)]),
    "mining": ("Mining Enchantment Package", "Efficiency V, Fortune III, Unbreaking III books.",
               [("efficiency", 5), ("fortune", 3), ("unbreaking", 3)]),
    "premium": ("Premium Enchantment Collection", "Mending, Unbreaking III, Efficiency V, Protection IV, Sharpness V books.",
                [("mending", 1), ("unbreaking", 3), ("efficiency", 5), ("protection", 4), ("sharpness", 5)]),
    "popular": ("Popular Enchantments Bundle", "Mending, Unbreaking III, Efficiency IV books - what most players want first.",
                [("mending", 1), ("unbreaking", 3), ("efficiency", 4)]),
}
for k, (nm, d, items) in BUNDLES_E.items():
    price = int(0.9 * sum(book_price(n, l) for n, l in items))
    add(f"bundle-enchant-{k}", nm, d, "enchantment", "EPIC" if k == "premium" else "RARE", r10(price), None,
        [f"ENCHANTED_BOOK 1 ench={n.upper()}:{l}" for n, l in items], icon="ENCHANTED_BOOK", limit=1, window=7)

# ================================================================ GHANAIAN FOOD
# (slug, display name, vanilla base item, in-game price per 8 portions, description)
FOODS = [
    # meals
    ("jollof-rice", "Jollof Rice", "RABBIT_STEW", 120, "Party jollof - smoky, spicy, the only argument that matters."),
    ("waakye", "Waakye", "BEETROOT_SOUP", 90, "Rice and beans with all the trimmings."),
    ("banku-tilapia", "Banku & Tilapia", "COOKED_COD", 130, "Fermented corn-and-cassava dough with grilled tilapia."),
    ("kenkey-fish", "Kenkey & Fried Fish", "COOKED_SALMON", 110, "Ga kenkey with pepper sauce."),
    ("fufu-light-soup", "Fufu & Light Soup", "MUSHROOM_STEW", 130, "Pounded cassava and plantain with goat light soup."),
    ("fufu-groundnut-soup", "Fufu & Groundnut Soup", "MUSHROOM_STEW", 130, "Rich peanut soup."),
    ("fufu-palmnut-soup", "Fufu & Palm Nut Soup", "MUSHROOM_STEW", 140, "Palm nut soup with smoked fish."),
    ("ampesi", "Ampesi", "BAKED_POTATO", 80, "Boiled yam/plantain with kontomire stew."),
    ("rice-balls-omotuo", "Rice Balls (Omotuo)", "BREAD", 70, "Rice balls for soup."),
    ("fried-rice", "Fried Rice & Chicken", "COOKED_CHICKEN", 120, "Fried rice with grilled chicken."),
    ("red-red", "Red-Red", "BEETROOT_SOUP", 90, "Beans stew with fried plantain."),
    ("gari-and-beans", "Gari & Beans (Gob3)", "BEETROOT_SOUP", 80, "A student's favourite."),
    ("kontomire-stew", "Kontomire Stew", "RABBIT_STEW", 100, "Cocoyam leaf stew with egg."),
    ("yam-garden-egg-stew", "Yam & Garden Egg Stew", "BAKED_POTATO", 85, "Boiled yam, garden egg sauce."),
    # snacks & street food
    ("kelewele", "Kelewele", "GOLDEN_CARROT", 60, "Spiced fried plantain cubes."),
    ("bofrot", "Bofrot", "COOKIE", 40, "Puff-puff, hot from the pan."),
    ("kofi-brokeman", "Kofi Brokeman", "BAKED_POTATO", 40, "Roasted plantain and groundnuts."),
    ("nkate-cake", "Nkate Cake", "COOKIE", 45, "Groundnut brittle."),
    ("chinchinga", "Chinchinga Kebab", "COOKED_MUTTON", 70, "Spicy suya-style skewers."),
    ("roasted-plantain", "Roasted Plantain", "BAKED_POTATO", 40, "Charcoal roasted, served with groundnuts."),
    ("roasted-corn", "Roasted Corn", "BAKED_POTATO", 35, "Street-corner favourite."),
    ("meat-pie", "Meat Pie", "PUMPKIN_PIE", 60, "Flaky and filling."),
    ("spring-rolls", "Spring Rolls", "BREAD", 50, "Crispy, crunchy."),
    # ingredients & produce
    ("cassava", "Cassava", "POTATO", 40, "Staple root crop."),
    ("cocoyam", "Cocoyam", "POTATO", 40, "For ampesi and soup."),
    ("yam", "Yam", "POTATO", 40, "Puna yam."),
    ("plantain", "Plantain", "CARROT", 35, "Ripe or unripe."),
    ("maize", "Maize", "WHEAT", 30, "Corn for kenkey and banku."),
    ("beans", "Beans", "BEETROOT", 30, "Black-eyed peas."),
    ("groundnuts", "Groundnuts", "COCOA_BEANS", 30, "Peanuts."),
    ("palm-oil", "Palm Oil", "HONEY_BOTTLE", 60, "Red gold."),
    ("tomatoes", "Tomatoes", "APPLE", 30, "Fresh tomatoes."),
    ("pepper", "Pepper (Shito Base)", "SWEET_BERRIES", 25, "Hot."),
    ("onions", "Onions", "BEETROOT", 25, "Bulb onions."),
    ("garden-eggs", "Garden Eggs", "MELON_SLICE", 30, "Small white eggplants."),
    ("kontomire", "Kontomire", "KELP", 25, "Cocoyam leaves."),
    ("okra", "Okra", "KELP", 25, "Okro for soup."),
    ("fresh-fish", "Fresh Fish", "COD", 40, "Tilapia and mackerel."),
    ("chicken", "Chicken", "CHICKEN", 45, "Local chicken."),
    ("goat-meat", "Goat Meat", "MUTTON", 55, "Goat."),
    ("beef", "Beef", "BEEF", 50, "Beef."),
]
for slug, nm, base, price, d in FOODS:
    cat = "food"
    add("food-" + slug, f"{nm} x8", d + " (8 portions; hunger/saturation = the vanilla base item.)", cat, "BASIC",
        price, None, [spec(base, 8, name=nm)], icon=base)
BASKET_SPEC = [spec("RABBIT_STEW", 4, name="Jollof Rice"), spec("BEETROOT_SOUP", 4, name="Waakye"), spec("BAKED_POTATO", 8, name="Kelewele"),
               spec("COOKIE", 8, name="Bofrot"), spec("COOKED_CHICKEN", 8, name="Chicken"), spec("BREAD", 16, name="Kenkey")]
add("bundle-food-basket", "Ghanaian Food Basket", "Jollof, waakye, kelewele, bofrot, chicken and kenkey: a market-day basket. Real-money price in GH₵.",
    "bundle", "UNCOMMON", 900, 1200, BASKET_SPEC, icon="RABBIT_STEW", featured=True)
add("bundle-street-food-pack", "Street Food Snack Pack", "Bofrot, kelewele, roasted corn, meat pie and spring rolls.", "bundle", "BASIC", 300, 500,
    [spec("COOKIE", 8, name="Bofrot"), spec("GOLDEN_CARROT", 8, name="Kelewele"), spec("BAKED_POTATO", 8, name="Roasted Corn"),
     spec("PUMPKIN_PIE", 4, name="Meat Pie"), spec("BREAD", 8, name="Spring Rolls")], icon="COOKIE")
add("bundle-harvest-basket", "Ghanaian Produce Basket", "Yam, cassava, plantain, maize, tomatoes, garden eggs, fish, goat meat.", "bundle", "BASIC", 500, None,
    [spec("POTATO", 16, name="Yam"), spec("POTATO", 16, name="Cassava"), spec("CARROT", 16, name="Plantain"), spec("WHEAT", 16, name="Maize"),
     spec("APPLE", 8, name="Tomatoes"), spec("MELON_SLICE", 8, name="Garden Eggs"), spec("COD", 8, name="Fresh Fish"), spec("MUTTON", 8, name="Goat Meat")], icon="POTATO")
add("bundle-independence-feast", "Independence Day Feast Basket", "Seasonal: 6th March celebration spread - jollof, waakye, kelewele, bofrot, meat pies. Available 1-10 March.",
    "bundle", "RARE", 1100, 1500, BASKET_SPEC + [spec("PUMPKIN_PIE", 8, name="Meat Pie")], icon="RABBIT_STEW",
    season="Independence Day", avail=("2027-03-01 00:00+00", "2027-03-10 23:59:59+00"))
add("bundle-farmers-day-basket", "Farmers' Day Harvest Basket", "Seasonal: celebrate the farmers who feed Ghana. Available 1-8 December.", "bundle", "RARE", 800, 1000,
    [spec("POTATO", 32, name="Yam"), spec("POTATO", 32, name="Cassava"), spec("CARROT", 32, name="Plantain"), spec("WHEAT", 32, name="Maize"),
     spec("BEETROOT", 16, name="Beans")], icon="POTATO", season="Farmers' Day", avail=("2026-12-01 00:00+00", "2026-12-08 23:59:59+00"))

# ================================================================ POTIONS
POTS = [  # (key, label, normal, long, strong, price per potion)
    ("healing", "Healing", "HEALING", None, "STRONG_HEALING", 120),
    ("regeneration", "Regeneration", "REGENERATION", "LONG_REGENERATION", "STRONG_REGENERATION", 150),
    ("strength", "Strength", "STRENGTH", "LONG_STRENGTH", "STRONG_STRENGTH", 220),
    ("speed", "Speed (Swiftness)", "SWIFTNESS", "LONG_SWIFTNESS", "STRONG_SWIFTNESS", 150),
    ("fire-resistance", "Fire Resistance", "FIRE_RESISTANCE", "LONG_FIRE_RESISTANCE", None, 120),
    ("water-breathing", "Water Breathing", "WATER_BREATHING", "LONG_WATER_BREATHING", None, 110),
    ("night-vision", "Night Vision", "NIGHT_VISION", "LONG_NIGHT_VISION", None, 100),
    ("invisibility", "Invisibility", "INVISIBILITY", "LONG_INVISIBILITY", None, 130),
    ("slow-falling", "Slow Falling", "SLOW_FALLING", "LONG_SLOW_FALLING", None, 110),
    ("leaping", "Leaping", "LEAPING", "LONG_LEAPING", "STRONG_LEAPING", 90),
    ("turtle-master", "Turtle Master", "TURTLE_MASTER", "LONG_TURTLE_MASTER", "STRONG_TURTLE_MASTER", 140),
    ("poison", "Poison", "POISON", "LONG_POISON", "STRONG_POISON", 100),
    ("weakness", "Weakness", "WEAKNESS", "LONG_WEAKNESS", None, 100),
    ("slowness", "Slowness", "SLOWNESS", "LONG_SLOWNESS", "STRONG_SLOWNESS", 100),
    ("harming", "Harming", "HARMING", None, "STRONG_HARMING", 120),
]
for key, label, normal, long_, strong, pr in POTS:
    best = strong or long_ or normal
    add(f"potion-{key}-x4", f"{label} Potion x4", f"Four drinkable {label.lower()} potions ({normal.replace('_', ' ').title()}).", "potion", "UNCOMMON", pr * 4, None,
        [spec("POTION", 4, potion=normal)], icon="POTION")
    add(f"potion-{key}-best-x4", f"{label} Potion (Best) x4", f"Four of the strongest/longest variant: {best.replace('_', ' ').title()}.", "potion", "RARE",
        int(pr * 4 * 1.6), None, [spec("POTION", 4, potion=best)], icon="POTION")
    add(f"potion-{key}-splash-x4", f"Splash {label} x4", f"Four splash {label.lower()} potions.", "potion", "UNCOMMON", int(pr * 4 * 1.3), None,
        [spec("SPLASH_POTION", 4, potion=normal)], icon="SPLASH_POTION")
    add(f"potion-{key}-lingering-x2", f"Lingering {label} x2", f"Two lingering {label.lower()} potions.", "potion", "RARE", int(pr * 2 * 2.0), None,
        [spec("LINGERING_POTION", 2, potion=normal)], icon="LINGERING_POTION")
add("bundle-brewing-ingredients", "Brewing Ingredients Kit", "Nether wart, blaze powder, glistering melon, ghast tear, rabbit foot, phantom membrane, magma cream, glass bottles.", "potion", "UNCOMMON", 1200, None,
    [spec("NETHER_WART", 16), spec("BLAZE_POWDER", 8), spec("GLISTERING_MELON_SLICE", 4), spec("GHAST_TEAR", 2), spec("RABBIT_FOOT", 2),
     spec("PHANTOM_MEMBRANE", 2), spec("MAGMA_CREAM", 4), spec("GLASS_BOTTLE", 16), spec("BREWING_STAND", 1)], icon="BREWING_STAND")

# ================================================================ BUNDLES (gear)
IRON_FULL = [spec(f"IRON_{p.upper()}", 1, {"protection": 1, "unbreaking": 1}) for p in PIECES]
add("bundle-beginner-survival", "Beginner Survival Bundle", "Iron armour (Protection I, Unbreaking I), iron sword, iron pickaxe, food and torches. Real-money price in GH₵ - limited to 1 per player.",
    "bundle", "UNCOMMON", 2600, 2500, IRON_FULL + [spec("IRON_SWORD"), spec("IRON_PICKAXE"), spec("COOKED_BEEF", 32), spec("BREAD", 16), spec("TORCH", 64)],
    icon="IRON_CHESTPLATE", limit=1, featured=True)
DIA_FULL = [spec(f"DIAMOND_{p.upper()}", 1, {"protection": 2, "unbreaking": 2}) for p in PIECES]
add("bundle-diamond-explorer", "Diamond Explorer Bundle", "Diamond armour (Protection II, Unbreaking II), diamond sword and pickaxe, food, torches, ender pearls. Limited to 1 per player per 30 days.",
    "bundle", "RARE", 11000, 6000, DIA_FULL + [spec("DIAMOND_SWORD", 1, {"sharpness": 2, "unbreaking": 2}), spec("DIAMOND_PICKAXE", 1, {"efficiency": 3, "unbreaking": 2}),
    spec("COOKED_BEEF", 32), spec("BREAD", 16), spec("TORCH", 64), spec("ENDER_PEARL", 8)], icon="DIAMOND_CHESTPLATE", limit=1, window=30)
NETH_FULL = [spec(f"DIAMOND_{p.upper()}", 1, {"fire_protection": 4, "unbreaking": 3}) for p in PIECES]
add("bundle-nether-explorer", "Nether Explorer Bundle", "Diamond armour (Fire Protection IV, Unbreaking III), fire resistance potions, golden apples, gold ingots for piglins, nether supplies. Limit 2 per 30 days.",
    "bundle", "RARE", 14000, 4500, NETH_FULL + [spec("POTION", 3, potion="LONG_FIRE_RESISTANCE"), spec("GOLDEN_APPLE", 4), spec("GOLD_INGOT", 32),
    spec("COOKED_PORKCHOP", 32), spec("OBSIDIAN", 14), spec("FLINT_AND_STEEL"), spec("COOKED_BEEF", 16)], icon="GOLDEN_APPLE", limit=2, window=30)
add("bundle-elytra-travel", "Elytra Travel Bundle", "Elytra (Unbreaking III), 64 firework rockets, 16 ender pearls, food. In-game currency only: 1 per player per 14 days.",
    "bundle", "EPIC", 36000, None, [spec("ELYTRA", 1, {"unbreaking": 3}), spec("FIREWORK_ROCKET", 64), spec("ENDER_PEARL", 16), spec("COOKED_BEEF", 32)],
    icon="ELYTRA", limit=1, window=14)
add("bundle-potion-fighter", "Fighter's Potion Bundle", "Healing x4, Strength x2, Speed x2, Fire Resistance x2.", "bundle", "UNCOMMON", 1600, None,
    [spec("POTION", 4, potion="STRONG_HEALING"), spec("POTION", 2, potion="STRENGTH"), spec("POTION", 2, potion="SWIFTNESS"), spec("POTION", 2, potion="FIRE_RESISTANCE")], icon="POTION")

# ================================================================ COSMETICS
TITLES = [  # (id, label, desc, price pesewas)
    ("gye_nyame", "Gye Nyame", '"Except God" - a gold chat title.', 1000),
    ("sankofa", "Sankofa", '"Go back and get it" - a green chat title.', 1000),
    ("dwennimmen", "Dwennimmen", "Strength and humility - a red chat title.", 1000),
    ("funtunfunefu", "Funtunfunefu", "Unity in diversity.", 1000),
    ("nkyinkyim", "Nkyinkyim", "Initiative and resilience.", 1000),
    ("kente", "Kente", "A woven gold-and-green chat title.", 1500),
    ("black_star", "Black Star", "The star of Ghana's flag.", 1500),
    ("akwaaba", "Akwaaba", "Welcome, everyone.", 800),
    ("independence", "6th March", "For the Independence Day collection.", 1500),
]
for tid, nm, d, pr in TITLES:
    add("title-" + tid.replace("_", "-"), f"{nm} Title", d, "cosmetic", "RARE", None, pr, [], icon="NAME_TAG",
        commands=[f"adinkra grant {{player}} {tid}"], limit=1)
add("title-adinkra-collection", "Adinkra Collection (5 Titles)", "Gye Nyame, Sankofa, Dwennimmen, Funtunfunefu and Nkyinkyim - save GH₵10 vs buying separately.",
    "cosmetic", "EPIC", None, 4000, [], icon="NAME_TAG",
    commands=[f"adinkra grant {{player}} {t}" for t in ("gye_nyame", "sankofa", "dwennimmen", "funtunfunefu", "nkyinkyim")], limit=1, featured=True)

# ================================================================ OUTPUT
def sql_row(p):
    cols = ("name, slug, description, price_pesewas, ingame_price_minor, category, rarity, delivery_commands, item_specs, icon_material, "
            "stock, purchase_limit_per_player, limit_window_days, gameplay_only, season, available_from, available_until, featured, sort_order, active")
    return (f"({q(p['name'])},{q(p['slug'])},{q(p['desc'])},{p['cash'] if p['cash'] is not None else 'NULL'},"
            f"{p['ingame'] if p['ingame'] is not None else 'NULL'},{q(p['category'])},{q(p['rarity'])},{arr(p['cmds'])},{arr(p['specs'])},"
            f"{q(p['icon']) if p['icon'] else 'NULL'},{p['stock'] if p['stock'] is not None else 'NULL'},"
            f"{p['limit'] if p['limit'] is not None else 'NULL'},{p['window'] if p['window'] is not None else 'NULL'},"
            f"{'true' if p['gameplay_only'] else 'false'},{q(p['season']) if p['season'] else 'NULL'},"
            f"{q(p['avail'][0]) + '::timestamptz' if p['avail'] else 'NULL'},{q(p['avail'][1]) + '::timestamptz' if p['avail'] else 'NULL'},"
            f"{'true' if p['featured'] else 'false'},{{sort}},true)"), cols


out = ["-- GENERATED by scripts/generate_catalogue.py - edit the script, not this file.",
       "-- Re-running never overwrites admin edits (ON CONFLICT (slug) DO NOTHING).", "BEGIN;"]
cat_order = ["bundle", "armour", "weapon", "tool", "rare", "enchantment", "food", "potion", "cosmetic"]
sorted_p = sorted(PRODUCTS, key=lambda p: (cat_order.index(p["category"]), 0))
for i, p in enumerate(sorted_p):
    row, cols = sql_row(p)
    out.append(f"INSERT INTO products ({cols}) VALUES {row.replace('{sort}', str(100 + i))} ON CONFLICT (slug) DO NOTHING;")
out.append("COMMIT;")
sys.stdout.write("\n".join(out) + "\n")
sys.stderr.write(f"{len(PRODUCTS)} products generated\n")
