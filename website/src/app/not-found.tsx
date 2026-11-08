import Link from "next/link";
import { Reveal } from "@/components/Reveal";

export default function NotFound() {
  return (
    <div className="wrap"><div className="page-head"><Reveal><h1>That page is not here</h1><p className="lead">It may have moved. Try the <Link href="/">home page</Link> or the <Link href="/docs/">documentation</Link>.</p></Reveal></div></div>
  );
}
