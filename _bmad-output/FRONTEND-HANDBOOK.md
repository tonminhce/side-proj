---
audience: frontend-dev
project: side-project
date: 2026-07-06
how-to-use: Next.js 15 + shadcn + TanStack Query + Stripe Elements. Per-Sprint frontend reference.
---

# Frontend Handbook — side-project

> **Stack (binding, per ADR-10):**
> - Next.js 15 (App Router + Server Components + Server Actions)
> - TypeScript strict mode
> - Tailwind CSS + shadcn/ui primitives
> - TanStack Query for server state
> - React Hook Form + Zod for forms
> - next-intl with `vi` default + `en` fallback
> - Stripe Elements iframe (PCI scope per ADR-23)
> - OpenTelemetry browser SDK → LGTM

---

## 1. Project structure

```
frontend/
├── storefront/                      # Customer-facing (UJ-2 UJ-3 UJ-4)
│   ├── package.json
│   ├── next.config.mjs
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   ├── .env.example
│   └── src/
│       ├── app/                     # App Router
│       │   ├── layout.tsx
│       │   ├── page.tsx             # Home
│       │   ├── products/[slug]/page.tsx
│       │   ├── cart/page.tsx
│       │   ├── checkout/page.tsx
│       │   └── account/
│       ├── components/
│       │   ├── ui/                  # shadcn primitives
│       │   ├── cart/
│       │   ├── checkout/
│       │   ├── product/
│       │   └── search/
│       ├── lib/                     # BFF client, Stripe.js, OTel
│       │   ├── server-client.ts
│       │   ├── stripe.ts
│       │   └── telemetry.ts
│       ├── hooks/                    # useCart, useStripePayment
│       ├── i18n/                    # vi.json, en.json
│       ├── types/                   # shared TS types
│       └── public/
├── admin/                           # Staff-facing (UJ-2)
│   └── (similar structure; role-gated /admin/* routes)
└── packages/                        # Shared frontend packages
    ├── ui/                          # cross-app components
    ├── types/                       # cross-app types (mirrors Avro schemas)
    └── eslint-config/
```

---

## 2. Next.js 15 conventions (binding)

### App Router structure

```typescript
// app/products/[slug]/page.tsx
import { serverClient } from '@/lib/server-client';
import { ProductGallery } from '@/components/product/ProductGallery';

export default async function ProductPage({ 
  params }: { params: Promise<{ slug: string }> }  // Next.js 15: params is a Promise
) {
  const { slug } = await params;
  const product = await serverClient.catalog.getBySlug(slug);
  return <ProductGallery product={product} />;
}
```

> **Next.js 15 breaking change:** `params` and `searchParams` are now `Promise`s. Always `await` them.

### Server Components (default) + Client Components (explicit)

```typescript
// Server Component (default — runs on server, no JS shipped to client)
export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
    const { slug } = await params;
    const product = await serverClient.catalog.getBySlug(slug);
    return <ProductGallery product={product} />;
}

// Client Component (use 'use client' directive)
'use client';
import { useState } from 'react';

export function AddToCartButton({ variantId }: { variantId: number }) {
    const [loading, setLoading] = useState(false);
    // ... interactive logic
}
```

### Server Actions for forms

```typescript
// app/cart/actions.ts
'use server';

import { serverClient } from '@/lib/server-client';
import { revalidatePath } from 'next/cache';

export async function addToCart(variantId: number, qty: number) {
    const cart = await serverClient.cart.addLine({ variantId, qty });
    revalidatePath('/cart');
    return cart;
}

// app/cart/page.tsx
import { addToCart } from './actions';

export default function CartPage() {
    return (
        <form action={async (formData) => {
            'use server';
            await addToCart(Number(formData.get('variantId')), Number(formData.get('qty')));
        }}>
            ...
        </form>
    );
}
```

---

## 3. shadcn/ui setup

### Initialize

```bash
cd frontend/storefront
npx shadcn-ui@latest init

# Add components as needed
npx shadcn-ui@latest add button card input label dialog dropdown-menu
```

### Config

```json
// components.json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "default",
  "rsc": true,
  "tsx": true,
  "tailwind": {
    "config": "tailwind.config.ts",
    "css": "src/app/globals.css",
    "baseColor": "slate",
    "cssVariables": true
  },
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/utils"
  }
}
```

### Example: ProductCard

```typescript
// components/product/ProductCard.tsx
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { formatVnd } from '@/lib/format';

export function ProductCard({ product }: { product: Product }) {
    return (
        <Card>
            <CardHeader>
                <img src={product.imageUrl} alt={product.name} />
                <CardTitle>{product.name}</CardTitle>
            </CardHeader>
            <CardContent>
                <p className="text-2xl font-bold">{formatVnd(product.priceList)}</p>
                <Button>Add to Cart</Button>
            </CardContent>
        </Card>
    );
}
```

---

## 4. TanStack Query patterns

### QueryClient setup (per Next.js best practice)

```typescript
// app/providers.tsx
'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';

export function Providers({ children }: { children: React.ReactNode }) {
    const [queryClient] = useState(() => new QueryClient({
        defaultOptions: {
            queries: {
                staleTime: 60 * 1000,  // 1 minute
                retry: 1,
            },
        },
    }));

    return (
        <QueryClientProvider client={queryClient}>
            {children}
        </QueryClientProvider>
    );
}

// app/layout.tsx
import { Providers } from './providers';

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return (
        <html>
            <body>
                <Providers>{children}</Providers>
            </body>
        </html>
    );
}
```

### Server-side fetch (preferred over client-side when possible)

```typescript
// app/products/page.tsx
import { serverClient } from '@/lib/server-client';
import { dehydrate, HydrationBoundary, QueryClient } from '@tanstack/react-query';

const queryClient = new QueryClient();
await queryClient.prefetchQuery({
    queryKey: ['products', page],
    queryFn: () => serverClient.catalog.list({ page }),
});

return (
    <HydrationBoundary state={dehydrate(queryClient)}>
        <ProductList />
    </HydrationBoundary>
);
```

### Client-side with useQuery (when interactivity needed)

```typescript
'use client';

import { useQuery } from '@tanstack/react-query';

export function CartIndicator() {
    const { data, isLoading, error } = useQuery({
        queryKey: ['cart'],
        queryFn: () => fetch('/api/cart').then(r => r.json()),
        refetchInterval: 30_000,
    });

    if (isLoading) return <Skeleton />;
    if (error) return <ErrorBanner />;
    return <Badge>{data.lineCount}</Badge>;
}
```

---

## 5. Stripe Elements (PCI scope per ADR-23)

### Critical: NEVER touch card data

```typescript
// ✅ CORRECT: Stripe Elements iframe collects card data
'use client';
import { Elements, PaymentElement } from '@stripe/react-stripe-js';
import { loadStripe } from '@stripe/stripe-js';

const stripePromise = loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY!);

export function CheckoutForm() {
    return (
        <Elements stripe={stripePromise}>
            <PaymentElement />  {/* Stripe's iframe, NOT our code */}
            <button>Pay</button>
        </Elements>
    );
}

// ❌ WRONG: Never handle raw card data in our code
function handleCardData(cardNumber: string, cvv: string) {  // FORBIDDEN
    // ...
}
```

### Payment intent creation

```typescript
// app/checkout/actions.ts
'use server';

import Stripe from 'stripe';
import { serverClient } from '@/lib/server-client';

const stripe = new Stripe(process.env.STRIPE_SECRET_KEY!);

export async function startCheckout(cartId: string) {
    const cart = await serverClient.cart.get(cartId);

    // Create payment intent on server; client never sees secret
    const paymentIntent = await stripe.paymentIntents.create({
        amount: cart.totalCents,
        currency: 'vnd',
        metadata: {
            cart_id: cartId,
            saga_step: 'payment.authorize',  // idempotency key ingredient
        },
    });

    return { clientSecret: paymentIntent.client_secret };
}
```

---

## 6. Vietnamese i18n (next-intl)

### Setup

```bash
npm install next-intl
```

### Config

```typescript
// i18n/request.ts
import { getRequestConfig } from 'next-intl/server';

export default getRequestConfig(async ({ requestLocale }) => {
    const locale = await requestLocale ?? 'vi';
    return {
        locale,
        messages: (await import(`./messages/${locale}.json`)).default,
    };
});

// next.config.mjs
import createNextIntlPlugin from 'next-intl/plugin';
const withNextIntl = createNextIntlPlugin('./i18n/request.ts');
export default withNextIntl({});
```

### Usage

```typescript
// components/CartIndicator.tsx
import { useTranslations } from 'next-intl';

export function CartIndicator() {
    const t = useTranslations('Cart');
    return <span>{t('items', { count: 3 })}</span>;
}

// i18n/messages/vi.json
{
    "Cart": {
        "items": "{count, plural, =0 {không có sản phẩm} one {1 sản phẩm} other {# sản phẩm}}"
    }
}

// i18n/messages/en.json
{
    "Cart": {
        "items": "{count, plural, =0 {no items} one {1 item} other {# items}}"
    }
}
```

### Locale routing

```typescript
// app/[locale]/layout.tsx
import { redirect } from 'next/navigation';
import { hasLocale } from 'next-intl';
import { routing } from '@/i18n/routing';

export default async function LocaleLayout({ children, params }: { params: Promise<{ locale: string }> }) {
    const { locale } = await params;
    if (!hasLocale(routing.locales, locale)) {
        redirect(routing.defaultLocale);
    }
    // ... rest of layout
}
```

---

## 7. OTel browser SDK

### Setup (per ADR-16)

```typescript
// lib/telemetry.ts (runs in browser)
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';

const provider = new WebTracerProvider({
    resource: new Resource({
        [SemanticResourceAttributes.SERVICE_NAME]: 'side-project-storefront',
    }),
});

provider.addSpanProcessor(
    new BatchSpanProcessor(
        new OTLPTraceExporter({
            url: process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT!,
        })
    )
);

provider.register();
```

### Init in root layout

```typescript
// app/layout.tsx
import './telemetry-init';  // side-effect import

export default function RootLayout({ children }: { children: React.ReactNode }) {
    return ( /* ... */ );
}

// app/telemetry-init.ts
if (typeof window !== 'undefined') {
    import('./telemetry').then((m) => m.initTelemetry());
}
```

### Span wrapping (use sparingly)

```typescript
import { trace } from '@opentelemetry/api';

const tracer = trace.getTracer('storefront');

export async function fetchProducts() {
    return tracer.startActiveSpan('fetch-products', async (span) => {
        try {
            const result = await fetch('/api/products');
            span.setStatus({ code: SpanStatusCode.OK });
            return result;
        } catch (e) {
            span.recordException(e as Exception);
            span.setStatus({ code: SpanStatusCode.ERROR });
            throw e;
        } finally {
            span.end();
        }
    });
}
```

---

## 8. Server-side BFF client

### Server-only data fetching

```typescript
// lib/server-client.ts
import 'server-only';

const BFF_BASE = process.env.BFF_INTERNAL_URL || 'http://bff-storefront:8080';

export const serverClient = {
    catalog: {
        list: (params: { page: number }) =>
            fetch(`${BFF_BASE}/api/catalog/products?page=${params.page}`).then(r => r.json()),
        getBySlug: (slug: string) =>
            fetch(`${BFF_BASE}/api/catalog/products/${slug}`).then(r => r.json()),
    },
    cart: {
        get: (cartId: string) =>
            fetch(`${BFF_BASE}/api/cart/${cartId}`, { headers: { 'cookie': `cart-id=${cartId}` }}).then(r => r.json()),
        addLine: (params: { cartId: string; variantId: number; qty: number }) =>
            fetch(`${BFF_BASE}/api/cart/${params.cartId}/lines`, {
                method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({ variantId: params.variantId, qty: params.qty }),
            }).then(r => r.json()),
    },
    // ... etc
};
```

> **`'server-only'` import:** throws at build time if accidentally imported into a client component. Use this for any code that touches secrets or DB connections.

### RBAC for admin routes

```typescript
// app/admin/layout.tsx
import { redirect } from 'next/navigation';
import { getSession } from '@/lib/auth';

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
    const session = await getSession();
    if (!session || !['staff', 'admin'].includes(session.role)) {
        redirect('/');
    }
    return <>{children}</>;
}
```

---

## 9. Form validation with Zod

### Shared schema (mirror Avro from backend)

```typescript
// packages/types/checkout.ts
import { z } from 'zod';

export const checkoutFormSchema = z.object({
    email: z.string().email(),
    address: z.object({
        province: z.string().min(1, 'Province is required'),
        district: z.string().min(1),
        commune: z.string().min(1),
        street: z.string().min(1),
    }),
    // ... matches backend
});

export type CheckoutFormData = z.infer<typeof checkoutFormSchema>;
```

### React Hook Form integration

```typescript
'use client';

import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { checkoutFormSchema, CheckoutFormData } from 'types/checkout';

export function CheckoutForm() {
    const { register, handleSubmit, formState: { errors } } = useForm<CheckoutFormData>({
        resolver: zodResolver(checkoutFormSchema),
    });

    const onSubmit = handleSubmit(async (data) => {
        // 1. Server action: create Stripe payment intent
        // 2. Stripe Elements confirms payment
        // 3. Redirect to confirmation
    });

    return (
        <form onSubmit={onSubmit}>
            <input {...register('email')} />
            {errors.email && <span>{errors.email.message}</span>}
            ...
        </form>
    );
}
```

---

## 10. Money / locale formatting

```typescript
// lib/format.ts
export function formatVnd(cents: number, locale: string = 'vi-VN'): string {
    return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency: 'VND',
        maximumFractionDigits: 0,  // VND has no minor units
    }).format(cents);
}

export function formatDate(date: Date, locale: string = 'vi-VN'): string {
    return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        timeZone: 'Asia/Ho_Chi_Minh',
    }).format(date);
}
```

---

## 11. Tailwind config (Vietnamese-friendly)

```typescript
// tailwind.config.ts
import type { Config } from 'tailwindcss';

const config: Config = {
    content: [
        './src/**/*.{ts,tsx}',
    ],
    theme: {
        extend: {
            fontFamily: {
                sans: ['var(--font-inter)', 'system-ui'],
                vietnamese: ['var(--font-be-vietnam-pro)', 'system-ui'],  // optional VN-specific font
            },
            colors: {
                primary: {
                    50: 'hsl(var(--primary-50))',
                    // ... full scale
                },
            },
        },
    },
};

export default config;
```

---

## 12. Quick reference

```bash
# Local dev
cd frontend/storefront
npm install
npm run dev  # http://localhost:3000

# Build
npm run build
npm run start  # production mode

# Test
npm run test       # unit
npm run test:e2e  # Playwright e2e

# Lint + format
npm run lint
npm run format  # Prettier

# Type check
npm run type-check
```

---

## 13. Hard rules (DO NOT VIOLATE)

1. **NEVER** handle raw card data. Stripe Elements iframe only. (R-15)
2. **NEVER** log PAN-shaped fields. OTel log redaction is mandatory. (R-15)
3. **NEVER** call CatalogService / CartService / etc. directly from the browser. Always go through BFF. (security + audit)
4. **ALWAYS** use `'use client'` directive on Client Components, `'server-only'` import on server-side code that touches secrets.
5. **ALWAYS** route `/admin/*` through RBAC server-side check.
6. **ALWAYS** use `next-intl` for user-facing strings. No hard-coded English.
7. **ALWAYS** use the Zod schema that mirrors the backend's Avro. Don't redefine field names client-side.
8. **ALWAYS** await `params` and `searchParams` in Next.js 15. (They're Promises now.)
9. **NEVER** use `localStorage` for auth tokens. Use httpOnly secure cookies. (PCI compliance)
10. **NEVER** disable CSP. The Stripe Elements iframe requires it; we configure for it. (security)

---

## 14. Sprint-by-sprint frontend work

| Sprint | Frontend work |
|---|---|
| 1 | Next.js monorepo bootstrap; admin shell + read-only `/admin/catalog` (Story 1.4) |
| 2 | Storefront shell; cart + checkout flow (Stories 2.1..2.5) |
| 3 | Stripe Elements integration (Story 3.3) — full PCI scope compliance |
| 4 | Order tracking UI; shipment timeline (Story 4.3) |
| 5 | Customer dashboard; PDPD export UI (Stories 5.1, 5.2) |
| 6 | Search UI with VN diacritic tolerance (Stories 6.1, 6.2) |
| 7 | Returns/RMA UI (Story 7.1) |
| 8 | Admin write/edit; review moderation (Story 8.1) |
| 9 | Notification preferences UI (Story 9.1) |
| 10 | Dashboards (LGTM); chaos drill UI for on-call |

---

## 15. Useful resources

- [Next.js 15 docs](https://nextjs.org/docs)
- [Stripe Elements React](https://stripe.com/docs/payments/quickstart)
- [shadcn/ui](https://ui.shadcn.com)
- [TanStack Query](https://tanstack.com/query)
- [next-intl](https://next-intl-docs.vercel.app)
- [OpenTelemetry JS](https://opentelemetry.io/docs/languages/js/)

Read `architecture-detail.md` §"Detail: ADR-10" for the full frontend rationale.
