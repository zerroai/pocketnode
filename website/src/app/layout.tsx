import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Pocketnode: an assistant that acts on your phone",
  description: "Pocketnode is an Android assistant with public source. It turns on the flashlight, sets alarms, takes notes and answers on Telegram, with every model call going through Vultr Serverless Inference.",
  icons: { icon: "/favicon.svg" },
};
export const viewport: Viewport = { themeColor: "#F6F1E7", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
