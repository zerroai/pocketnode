// A static export: `next build` writes plain files to website/out that any web host can serve.
const basePath = process.env.NEXT_PUBLIC_BASE_PATH || "";
/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "export",
  trailingSlash: true,
  basePath,
  images: { unoptimized: true },
  reactStrictMode: true,
};
export default nextConfig;
