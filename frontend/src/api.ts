// API types mirror the backend DTOs in backend/src/main/java/crv/dto (feat/assistant).
// TODO: generate these from the OpenAPI spec at /v3/api-docs, as docs/code-standards.md requires.

export type ConstraintMatch = "MATCH" | "PARTIAL" | "UNKNOWN" | "NO";
export type PhotoMatch = "YES" | "PARTLY" | "NO" | "NOT_CHECKED";

export interface VariantSummary {
  asin: string;
  title: string;
  brand: string | null;
  color: string | null;
  size: string | null;
  price: number;
  averageRating: number | null;
  ratingCount: number | null;
  imageUrl: string | null;
}

export interface ProductCard {
  productId: string;
  lead: VariantSummary;
  minPrice: number;
  maxPrice: number;
  colors: string[];
  sizes: string[];
  variantCount: number;
  matchingVariantCount: number;
}

export interface ConstraintLabel {
  constraint: string;
  wanted: string;
  match: ConstraintMatch;
  value: string | null;
}

export interface ResultCard {
  product: ProductCard;
  labels: ConstraintLabel[];
  photo: PhotoMatch;
}

export interface CatalogFilter {
  priceBelow?: number | null;
  priceAtMost?: number | null;
  priceAbove?: number | null;
  priceAtLeast?: number | null;
  minRating?: number | null;
  brand?: string | null;
  categories?: string[] | null;
}

export interface SearchQuery {
  text?: string | null;
  filter?: CatalogFilter | null;
  color?: string | null;
  size?: string | null;
}

export interface ChatReply {
  conversationId: string;
  reply: string;
  products: ResultCard[] | null;
  quickReplies: string[];
  constraints: SearchQuery | null;
  millis: number;
  error: string | null;
}

export interface SearchResult {
  query: SearchQuery;
  items: ResultCard[];
  totalProducts: number;
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as T;
}

export async function similar(productId: string): Promise<SearchResult> {
  if (demo) return { query: {}, items: DEMO_CARDS, totalProducts: DEMO_CARDS.length };
  return get<SearchResult>(`/api/products/${encodeURIComponent(productId)}/similar`);
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as T;
}

export const demo = new URLSearchParams(location.search).has("demo");

export async function chat(message: string, conversationId: string | null): Promise<ChatReply> {
  if (demo) return demoReply(message, conversationId);
  return post<ChatReply>("/api/chat", { conversationId, message });
}

export async function search(query: SearchQuery, conversationId: string | null): Promise<SearchResult> {
  const qs = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : "";
  if (demo) return { query, items: DEMO_CARDS, totalProducts: DEMO_CARDS.length };
  return post<SearchResult>(`/api/search${qs}`, query);
}

// Demo data: real catalog rows, used with ?demo so the UI can be checked without the backend.
const card = (id: string, title: string, brand: string, color: string, size: string, price: number,
  rating: number, count: number, img: string, colors: string[], labels: ConstraintLabel[] = []): ResultCard => ({
  product: {
    productId: id, lead: { asin: id, title, brand, color, size, price, averageRating: rating, ratingCount: count, imageUrl: img },
    minPrice: price, maxPrice: price, colors, sizes: [size], variantCount: colors.length, matchingVariantCount: 1,
  },
  labels, photo: labels.length ? "YES" : "NOT_CHECKED",
});

const DEMO_CARDS: ResultCard[] = [
  card("B07DG1QPYZ", "VIIOO Men's 2-in-1 Running Workout Training Basketball Athletic Shorts", "VIIOO", "Orange", "Small", 29.5, 3.7, 31,
    "https://m.media-amazon.com/images/I/51zfFjUXRUL._AC_.jpg", ["Black02", "Black01", "Orange", "Black"],
    [{ constraint: "color", wanted: "orange", match: "MATCH", value: "Orange" }, { constraint: "size", wanted: "small", match: "MATCH", value: "Small" }]),
  card("B01IPP5VF0", "Sub Sports Mens Compression Shorts Running Base Layer Sweat Wicking", "Sub Sports", "Orange/Black", "Small", 24.76, 4.1, 109,
    "https://m.media-amazon.com/images/I/41t1xu-SeaL._AC_.jpg", ["Orange/Black", "Black"],
    [{ constraint: "color", wanted: "orange", match: "PARTIAL", value: "Orange/Black" }, { constraint: "size", wanted: "small", match: "MATCH", value: "Small" }]),
  card("B01ELC565Q", "ARSUXEO Men's Sports Pockets Running Shorts", "ARSUXEO", "Orange", "Small", 37.13, 3.0, 12,
    "https://m.media-amazon.com/images/I/418CtZ9xdmL._AC_.jpg", ["Orange", "Blue", "Black"],
    [{ constraint: "color", wanted: "orange", match: "MATCH", value: "Orange" }, { constraint: "size", wanted: "small", match: "UNKNOWN", value: null }]),
];

function demoReply(message: string, conversationId: string | null): Promise<ChatReply> {
  const broad = /^i need shoes$/i.test(message.trim());
  return new Promise((resolve) =>
    setTimeout(() => resolve({
      conversationId: conversationId ?? "demo",
      reply: broad
        ? "What kind of shoes are you after?"
        : "Here are orange running shorts in size small. The Sub Sports pair is listed as Orange/Black, so it's only a partial colour match.",
      products: broad ? null : DEMO_CARDS,
      quickReplies: broad ? ["Sneakers", "Running", "Boots", "Sandals"] : ["Anything cheaper?", "Only show blue ones"],
      constraints: broad ? null : { text: "running shorts", color: "orange", size: "small", filter: { priceBelow: 40 } },
      millis: 1800,
      error: null,
    }), 400),
  );
}

// ---- Homepage feed, clicks and product pages (feat/assistant c38c307)

export interface ImageDto { large: string; hiRes: string | null; thumb: string | null; variant: string | null }
export interface VariantDetail {
  asin: string; title: string; brand: string | null; color: string | null; size: string | null; price: number;
  averageRating: number | null; ratingCount: number | null; features: string[]; description: string[];
  details: Record<string, string>; specs: Record<string, string>; images: ImageDto[];
}
export interface ProductDetail {
  productId: string; minPrice: number; maxPrice: number; colors: string[]; sizes: string[];
  images: ImageDto[]; variants: VariantDetail[];
}
export interface HomeFeed { basis: "RANDOM" | "HISTORY"; basedOn: string[]; items: ResultCard[] }
export type Source = "HOME" | "CHAT" | "SEARCH" | "SIMILAR";

export const shopperId: string = (() => {
  const key = "crv.shopperId";
  const existing = localStorage.getItem(key);
  if (existing) return existing;
  const id = crypto.randomUUID();
  localStorage.setItem(key, id);
  return id;
})();

export async function home(): Promise<HomeFeed> {
  if (demo) return { basis: "RANDOM", basedOn: [], items: DEMO_CARDS };
  return get<HomeFeed>(`/api/home?shopperId=${encodeURIComponent(shopperId)}&size=12`);
}

export async function product(productId: string): Promise<ProductDetail> {
  return get<ProductDetail>(`/api/products/${encodeURIComponent(productId)}`);
}

export async function recordClick(productId: string, source: Source): Promise<void> {
  if (demo) return;
  try {
    await fetch("/api/interactions", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ shopperId, productId, source }),
    });
  } catch {
    // A lost click only weakens recommendations; never block opening the product.
  }
}
