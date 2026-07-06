import './globals.css';
import type { ReactNode } from 'react';

// read-first (FR-62); writes are deferred to Sprint 8 / Story 8.1.
export const metadata = {
  title: 'Admin — Catalog',
  description: 'Staff catalog read view (Story 1.4).',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}