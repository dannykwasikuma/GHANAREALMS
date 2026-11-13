// DB stores BASIC..LEGENDARY; the storefront shows Ghanaian tier names.
export const RARITY_ORDER = ["BASIC", "UNCOMMON", "RARE", "EPIC", "LEGENDARY"] as const;
export const RARITY_NAME: Record<string, string> = {
  BASIC: "Fugu",
  UNCOMMON: "Kente",
  RARE: "Adinkra",
  EPIC: "Asante Gold",
  LEGENDARY: "Golden Stool",
};
export const RARITY_STYLE: Record<string, string> = {
  BASIC: "text-foreground/60",
  UNCOMMON: "text-green-500",
  RARE: "text-blue-400",
  EPIC: "text-purple-400",
  LEGENDARY: "text-gr-gold",
};

export const CATEGORY_ORDER = ["bundle", "rank", "package", "armour", "weapon", "tool", "rare", "enchantment", "food", "potion", "building", "cosmetic", "item"];
export const CATEGORY_TITLE: Record<string, string> = {
  bundle: "Bundles - Best Value", rank: "Ranks", package: "Packages", armour: "Kumasi Armour House",
  weapon: "Weapons & Combat", tool: "Tools & Mining", rare: "The Elytra Centre & Rare Items",
  enchantment: "The Enchantment House", food: "Kejetia Food Market", potion: "Potions & Brewing", building: "Tema Building Materials Market",
  cosmetic: "Adinkra Cosmetics", item: "Items",
};
export const fmtCedi = (pesewas: number) => `GH₵${(pesewas / 100).toFixed(2)}`;
export const fmtCoins = (n: number) => `${n.toLocaleString("en-GH")} coins`;
