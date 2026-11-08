"use client";

export function SearchButton() {
  return <button className="btn secondary" type="button" onClick={() => window.dispatchEvent(new Event("mc-search"))}>Search the documents</button>;
}
