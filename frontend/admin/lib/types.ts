// Shared types between Server Component (page.tsx) and Client Component (table).
// Mirrors vn.vnpt.catalog.application.query.ProductSummary / VariantSummary on the BFF side.
export type VariantSummary = {
  variantUuid: number;
  sku: string;
  attributes: Record<string, string>;
  priceCents: number;
  currency: string;
};

export type ProductSummary = {
  productUuid: number;
  sku: string;
  name: string;
  brand: string | null;
  description: string | null;
  createdAt: string;
  variants: VariantSummary[];
};

export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};