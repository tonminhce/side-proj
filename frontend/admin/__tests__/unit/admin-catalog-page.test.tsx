import { describe, it, expect, vi, beforeEach } from 'vitest';

// ponytail: vi.hoisted keeps the mock references reachable inside the vi.mock factory,
// which Vitest hoists above all imports.
const { mockSpan, mockStartActiveSpan, mockGetTracer } = vi.hoisted(() => {
  const span = { setAttribute: vi.fn(), end: vi.fn() };
  const startActiveSpan = vi.fn((_name: string, fn: (s: typeof span) => unknown) =>
    Promise.resolve(fn(span)),
  );
  const getTracer = vi.fn(() => ({ startActiveSpan }));
  return { mockSpan: span, mockStartActiveSpan: startActiveSpan, mockGetTracer: getTracer };
});

vi.mock('@opentelemetry/api', () => ({
  trace: { getTracer: mockGetTracer },
}));

/**
 * Story 1.4 / AC #9 — the page emits one OTel span per load with bounded-cardinality
 * attributes ({@code admin.role}, {@code admin.tenant}, {@code admin.page_size}). NFR-OBS-3
 * forbids unbounded-cardinality labels (no {@code admin.user_id}, no {@code
 * admin.product_uuid}).
 */
describe('CatalogPage OTel span', () => {
  beforeEach(() => {
    mockGetTracer.mockClear();
    mockStartActiveSpan.mockClear();
    mockSpan.setAttribute.mockClear();
    mockSpan.end.mockClear();
    global.fetch = vi.fn(() =>
      Promise.resolve({
        ok: true,
        json: () =>
          Promise.resolve({
            content: [],
            page: 0,
            size: 20,
            totalElements: 0,
            totalPages: 0,
          }),
      } as Response),
    ) as unknown as typeof fetch;
  });

  it('emits admin.catalog.view span with role + tenant + page_size attributes', async () => {
    const mod = await import('@/app/admin/catalog/page');
    await mod.default();

    expect(mockGetTracer).toHaveBeenCalledWith('admin-frontend');
    expect(mockStartActiveSpan).toHaveBeenCalledWith('admin.catalog.view', expect.any(Function));
    expect(mockSpan.setAttribute).toHaveBeenCalledWith('admin.role', 'staff');
    expect(mockSpan.setAttribute).toHaveBeenCalledWith('admin.tenant', 'default');
    expect(mockSpan.setAttribute).toHaveBeenCalledWith('admin.page_size', 20);
    expect(mockSpan.end).toHaveBeenCalled();
  });

  it('does NOT carry unbounded-cardinality attributes (NFR-OBS-3)', async () => {
    const mod = await import('@/app/admin/catalog/page');
    await mod.default();

    const attributeKeys = mockSpan.setAttribute.mock.calls.map((c) => c[0]);
    // regression guard: Prometheus / OTel collector reject unbounded-cardinality labels.
    expect(attributeKeys).not.toContain('admin.user_id');
    expect(attributeKeys).not.toContain('admin.product_uuid');
    expect(attributeKeys).not.toContain('admin.session_id');
  });

  it('calls the BFF endpoint and returns the products table', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch');
    const mod = await import('@/app/admin/catalog/page');
    const element = await mod.default();
    // ponytail: the page returns a React element; assert the type signature without rendering.
    expect(element).toBeTruthy();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const calledUrl = String((fetchSpy.mock.calls[0]?.[0] as string | URL | Request) ?? '');
    expect(calledUrl).toContain('/bff/admin/catalog/products');
    expect(calledUrl).toContain('page=0');
    expect(calledUrl).toContain('size=20');
  });
});