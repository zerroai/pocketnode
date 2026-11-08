// Serves website/out the way a web host would, so the finished site can be looked at: node scripts/preview.mjs [port]
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "out");
const port = Number(process.argv[2]) || 3001;
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".ttf": "font/ttf", ".woff2": "font/woff2", ".txt": "text/plain", ".xml": "application/xml", ".apk": "application/vnd.android.package-archive" };
http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, "http://x").pathname);
  let file = path.join(out, p);
  if (!file.startsWith(out)) { res.writeHead(403); return res.end(); }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, "index.html");
  if (!fs.existsSync(file) && fs.existsSync(file + ".html")) file += ".html";
  if (!fs.existsSync(file)) { res.writeHead(404, { "content-type": types[".html"] }); return res.end(fs.existsSync(path.join(out, "404.html")) ? fs.readFileSync(path.join(out, "404.html")) : "Not found"); }
  res.writeHead(200, { "content-type": types[path.extname(file)] || "application/octet-stream" });
  fs.createReadStream(file).pipe(res);
}).listen(port, () => console.log(`Serving website/out at http://localhost:${port}`));
