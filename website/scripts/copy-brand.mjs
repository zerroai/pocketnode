// Copies the logo, icons, favicons and font from brand/ into the website. brand/ stays the single source (AGENTS.md rule 7).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const here = path.dirname(fileURLToPath(import.meta.url));
const web = path.resolve(here, "..");
const brand = path.resolve(web, "..", "brand");
const put = (from, to) => { fs.mkdirSync(path.dirname(to), { recursive: true }); fs.copyFileSync(from, to); };
for (const f of ["horizontal-black", "horizontal-on-dark", "mark-black", "mark-on-dark", "mark-silver"]) put(path.join(brand, `logo/svg/${f}.svg`), path.join(web, `public/brand/logo/${f}.svg`));
for (const f of ["favicon.svg", "favicon-16.png", "favicon-32.png", "favicon-180.png", "favicon-192.png", "favicon-512.png"]) put(path.join(brand, `app-icon/web/${f}`), path.join(web, "public", f));
put(path.join(brand, "app-icon/social/cover-black.png"), path.join(web, "public/brand/social.png"));
put(path.join(brand, "typography/fonts/Nunito-Variable.ttf"), path.join(web, "src/fonts/Nunito-Variable.ttf"));
put(path.join(brand, "color/tokens.css"), path.join(web, "src/styles/tokens.css"));
for (const t of fs.readdirSync(path.join(brand, "icons/svg/tile"))) put(path.join(brand, "icons/svg/tile", t), path.join(web, "public/brand/icons", t));
console.log("Brand files copied.");
