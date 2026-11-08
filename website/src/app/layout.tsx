import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import "../styles/tokens.css";
import "./globals.css";
import { config, origin } from "@/lib/config";
import { Header } from "@/components/Header";
import { Footer } from "@/components/Footer";
import { Search } from "@/components/Search";
import { ScrollBar } from "@/components/ScrollBar";

const nunito = localFont({ src: "../fonts/Nunito-Variable.ttf", variable: "--font-nunito", weight: "200 1000", display: "swap" });

export const metadata: Metadata = {
  metadataBase: origin ? new URL(origin) : undefined,
  title: { default: `${config.name}: ${config.tagline}`, template: `%s | ${config.name}` },
  description: "An assistant that lives on your Android phone. Ask in plain words or by voice. Your chats and files stay on the phone, you choose the model, and it asks before it touches anything private.",
  icons: { icon: [{ url: "/favicon.svg", type: "image/svg+xml" }, { url: "/favicon-32.png", sizes: "32x32", type: "image/png" }], apple: "/favicon-180.png" },
  openGraph: { type: "website", siteName: config.name, ...(origin ? { images: [{ url: "/brand/social.png", width: 1200, height: 630 }] } : {}) },
  twitter: { card: "summary_large_image" },
};
export const viewport: Viewport = { themeColor: "#0A0A0C", width: "device-width", initialScale: 1 };

// Runs first, so the page never flashes the wrong colors. The choice is only a convenience; the page works without storage.
const THEME = `try{var t=localStorage.getItem("mc-theme");if(t==="light"||t==="dark")document.documentElement.setAttribute("data-theme",t)}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={nunito.variable} suppressHydrationWarning>
      <head><script dangerouslySetInnerHTML={{ __html: THEME }} /></head>
      <body>
        <a className="skip" href="#main">Skip to the content</a>
        <ScrollBar />
        <Header />
        <main id="main">{children}</main>
        <Footer />
        <Search />
      </body>
    </html>
  );
}
