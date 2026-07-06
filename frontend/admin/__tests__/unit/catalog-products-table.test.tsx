import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { CatalogProductsTable } from '@/components/catalog-products-table';
import type { Page, ProductSummary } from '@/lib/types';

const sampleProduct: ProductSummary = {
  productUuid: 1,
  sku: 'red-shirt',
  name: 'Red Shirt',
  brand: 'Acme',
  description: 'A red shirt',
  createdAt: '2026-07-07T01:00:00Z',
  variants: [
    {
      variantUuid: 11,
      sku: 'red-shirt-M-red',
      attributes: { color: 'red', size: 'M' },
      priceCents: 24900000,
      currency: 'VND',
    },
  ],
};

const samplePage: Page<ProductSummary> = {
  content: [sampleProduct],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

describe('<CatalogProductsTable>', () => {
  it('renders a row per product with formatted VND price', () => {
    render(<CatalogProductsTable initial={samplePage} />);
    expect(screen.getByText('red-shirt')).toBeInTheDocument();
    expect(screen.getByText('Red Shirt')).toBeInTheDocument();
    expect(screen.getByText('Acme')).toBeInTheDocument();
    // Intl.NumberFormat vi-VN VND: 24900000 cents = 249000₫
    expect(screen.getByText(/249\.000\s*₫/)).toBeInTheDocument();
  });

  it('does NOT render any edit/delete buttons (read-first FR-62 regression guard)', () => {
    render(<CatalogProductsTable initial={samplePage} />);
    expect(screen.queryByRole('button', { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
  });

  it('renders "no variants" placeholder for orphan products', () => {
    const orphanPage: Page<ProductSummary> = {
      content: [{ ...sampleProduct, variants: [] }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    };
    render(<CatalogProductsTable initial={orphanPage} />);
    // ponytail: both the price column and the attributes column render the placeholder
    // for orphan products — getAllByText is the right assertion.
    expect(screen.getAllByText('— (no variants)').length).toBeGreaterThanOrEqual(1);
  });
});