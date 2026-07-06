import { trace } from '@opentelemetry/api';
import { CatalogProductsTable } from '@/components/catalog-products-table';
import type { Page, ProductSummary } from '@/lib/types';

const BFF = process.env.BFF_BASE_URL ?? 'http://localhost:8082';

// read-first (FR-62); writes are deferred to Sprint 8 / Story 8.1.
export default async function CatalogPage() {
  const tracer = trace.getTracer('admin-frontend');
  return tracer.startActiveSpan('admin.catalog.view', async (span) => {
    // role + tenant attribute pulled from request context (Story 5.5 wires this from JWT).
    // ponytail: bounded cardinality — role (2 values), tenant (1 in v1), page_size (4 values).
    span.setAttribute('admin.role', 'staff');
    span.setAttribute('admin.tenant', 'default');
    span.setAttribute('admin.page_size', 20);
    try {
      // ponytail: server-side fetch + pass to client component. `cache: 'no-store'` per Next.js
      // 15 default for live data — admin view shows live catalog.
      const res = await fetch(`${BFF}/bff/admin/catalog/products?page=0&size=20`, {
        cache: 'no-store',
      });
      if (!res.ok) {
        throw new Error(`BFF returned ${res.status}`);
      }
      const data = (await res.json()) as Page<ProductSummary>;
      return <CatalogProductsTable initial={data} />;
    } finally {
      span.end();
    }
  });
}