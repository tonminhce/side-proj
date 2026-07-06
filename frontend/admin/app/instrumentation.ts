import { registerOTel } from '@vercel/otel';

// ponytail: OTel SDK only initializes in Node.js / Edge runtime — Next.js calls register()
// automatically per its instrumentation hook contract. Story 1.4 wires one span
// `admin.catalog.view`; Story 10.1 expands to the LGTM dashboard surface.
export function register() {
  registerOTel({ serviceName: 'admin-frontend' });
}