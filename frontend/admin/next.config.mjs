/** @type {import('next').NextConfig} */
const nextConfig = {
  experimental: { typedRoutes: true },
  env: { BFF_BASE_URL: process.env.BFF_BASE_URL ?? 'http://localhost:8082' },
};

export default nextConfig;