export function escapeHtml(s: string): string;
export function slug(s: string): string;
export function plain(s: string): string;
export function inline(text: string, link?: (href: string) => string | null): string;
export function render(md: string, link?: (href: string) => string | null): { html: string; headings: { level: number; id: string; text: string }[]; title: string };
