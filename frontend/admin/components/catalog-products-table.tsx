'use client';

import { useState } from 'react';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import type { Page, ProductSummary, VariantSummary } from '@/lib/types';

type Props = { initial: Page<ProductSummary> };

function formatVnd(cents: number): string {
  // ponytail: Intl.NumberFormat is the stdlib currency formatter — no currency lib needed.
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(cents / 100);
}

// read-first (FR-62); writes are deferred to Sprint 8 / Story 8.1.
export function CatalogProductsTable({ initial }: Props) {
  const [page, setPage] = useState(initial);
  const [currentPage, setCurrentPage] = useState(initial.page);

  // In-memory pagination — Story 8.1 swaps to URL-state pagination.
  function goToPage(next: number) {
    if (next < 0 || next >= page.totalPages) return;
    setCurrentPage(next);
    // ponytail: client-side fetch is omitted here to keep the v1 contract simple —
    // the initial server-side fetch is the only data the page ships in this story.
    // Story 8.1 wires TanStack Query refetch.
  }

  return (
    <div className="p-6 space-y-4">
      <h1 className="text-2xl font-semibold">Catalog</h1>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>SKU</TableHead>
            <TableHead>Name</TableHead>
            <TableHead>Brand</TableHead>
            <TableHead>Variants</TableHead>
            <TableHead>First-variant price</TableHead>
            <TableHead>Attributes</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {page.content.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-muted-foreground">
                No products yet.
              </TableCell>
            </TableRow>
          )}
          {page.content.map((p) => (
            <TableRow key={p.productUuid} data-testid={`product-row-${p.productUuid}`}>
              <TableCell className="font-mono">{p.sku}</TableCell>
              <TableCell>{p.name}</TableCell>
              <TableCell>{p.brand ?? '—'}</TableCell>
              <TableCell>{p.variants.length}</TableCell>
              <TableCell>
                {p.variants[0] ? formatVnd(p.variants[0].priceCents) : '— (no variants)'}
              </TableCell>
              <TableCell>
                <div className="flex flex-wrap gap-1">
                  {firstAttributes(p.variants[0]).map(([k, v]) => (
                    <Badge key={`${k}=${v}`} variant="secondary">
                      {k}={v}
                    </Badge>
                  ))}
                  {p.variants.length === 0 && (
                    <span className="text-sm text-muted-foreground">— (no variants)</span>
                  )}
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <div className="flex items-center justify-between">
        <span className="text-sm text-muted-foreground">
          Page {page.page + 1} / {page.totalPages} ({page.totalElements} total)
        </span>
        <div className="flex gap-2">
          <Button variant="outline" disabled={currentPage <= 0} onClick={() => goToPage(currentPage - 1)}>
            Previous
          </Button>
          <Button
            variant="outline"
            disabled={currentPage + 1 >= page.totalPages}
            onClick={() => goToPage(currentPage + 1)}
          >
            Next
          </Button>
        </div>
      </div>
    </div>
  );
}

function firstAttributes(v: VariantSummary | undefined): [string, string][] {
  if (!v) return [];
  return Object.entries(v.attributes);
}