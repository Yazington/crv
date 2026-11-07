import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { ProductPage } from "./ProductPage";
import { chat, demo, home, recordClick, search, similar, type HomeFeed, type Source, type ChatReply, type ConstraintLabel, type ResultCard, type SearchQuery } from "./api";

interface Message {
  role: "shopper" | "assistant";
  text: string;
  error?: boolean;
  retry?: string;
}

const NL = "Not listed";
const money = (v: number) => `$${v.toFixed(2)}`;

// Product ids in a reply ("[B07DG1QPYZ]") are shown as small links to their card.
function ReplyText({ text, onPick }: { text: string; onPick: (id: string) => void }) {
  const parts = text.split(/(\[[A-Z0-9]{10}\]|\*\*[^*]+\*\*)/g);
  return (
    <>
      {parts.map((p, i) => {
        const id = /^\[([A-Z0-9]{10})\]$/.exec(p)?.[1];
        if (id) return <button key={i} className="idref" onClick={() => onPick(id)} title="Show card">{id}</button>;
        if (p.startsWith("**") && p.endsWith("**")) return <b key={i}>{p.slice(2, -2)}</b>;
        return <span key={i}>{p}</span>;
      })}
    </>
  );
}

function pills(q: SearchQuery | null): { key: string; label: string; remove: (q: SearchQuery) => SearchQuery }[] {
  if (!q) return [];
  const f = q.filter ?? {};
  const out: { key: string; label: string; remove: (q: SearchQuery) => SearchQuery }[] = [];
  const dropFilter = (k: keyof NonNullable<SearchQuery["filter"]>) => (x: SearchQuery) => ({ ...x, filter: { ...x.filter, [k]: null } });
  if (q.text) out.push({ key: "text", label: q.text, remove: (x) => ({ ...x, text: null }) });
  if (f.priceBelow != null) out.push({ key: "pb", label: `under ${money(f.priceBelow)}`, remove: dropFilter("priceBelow") });
  if (f.priceAtMost != null) out.push({ key: "pm", label: `up to ${money(f.priceAtMost)}`, remove: dropFilter("priceAtMost") });
  if (f.priceAbove != null) out.push({ key: "pa", label: `over ${money(f.priceAbove)}`, remove: dropFilter("priceAbove") });
  if (f.priceAtLeast != null) out.push({ key: "pl", label: `from ${money(f.priceAtLeast)}`, remove: dropFilter("priceAtLeast") });
  if (f.minRating != null) out.push({ key: "r", label: `${f.minRating}★ and up`, remove: dropFilter("minRating") });
  if (f.brand) out.push({ key: "b", label: f.brand, remove: dropFilter("brand") });
  if (q.color) out.push({ key: "c", label: q.color, remove: (x) => ({ ...x, color: null }) });
  if (q.size) out.push({ key: "s", label: `size ${q.size}`, remove: (x) => ({ ...x, size: null }) });
  return out;
}

const LABEL_TEXT: Record<ConstraintLabel["match"], string> = { MATCH: "✓", PARTIAL: "≈", UNKNOWN: "?", NO: "✕" };

function Card({ item, n, active, onSimilar, onOpen }: { item: ResultCard; n: number; active: boolean; onSimilar: (id: string) => void; onOpen: (id: string) => void }) {
  const { product: p, labels } = item;
  const v = p.lead;
  const others = p.colors.filter((c) => c !== v.color);
  return (
    <article className={`card${active ? " active" : ""}`} id={`card-${p.productId}`}>
      <button className="img" onClick={() => onOpen(p.productId)} aria-label={`Open ${v.title}`}>
        <span className="num">{n}</span>
        {v.imageUrl ? <img src={v.imageUrl} alt="" loading="lazy" /> : <span className="muted">No photo</span>}
      </button>
      <div className="body">
        <button className="title linkish" title={v.title} onClick={() => onOpen(p.productId)}>{v.title}</button>
        <div className="row">
          <span className="price">{money(v.price)}</span>
          {p.maxPrice > p.minPrice && <span className="muted" title="Price range across variants">{money(p.minPrice)}–{money(p.maxPrice)}</span>}
        </div>
        <div className="row muted">
          <span title="Rating and review count">{v.averageRating != null && v.ratingCount != null ? `★ ${v.averageRating.toFixed(1)} (${v.ratingCount})` : `Rating ${NL.toLowerCase()}`}</span>
          <span className="brand">{v.brand ?? NL}</span>
        </div>
        <div className="row small">
          <span title="Colour of this listing">{v.color ?? NL}</span>
          <span>·</span>
          <span title="Size of this listing">{v.size ?? NL}</span>
        </div>
        {others.length > 0 && (
          <div className="seen small muted" title={p.colors.join(", ")}>Seen in: {p.colors.slice(0, 5).join(", ")}{p.colors.length > 5 ? "…" : ""}</div>
        )}
        {p.sizes.length > 1 && (
          <div className="seen small muted" title={p.sizes.join(", ")}>Sizes: {p.sizes.slice(0, 6).join(", ")}{p.sizes.length > 6 ? "…" : ""}</div>
        )}
        {labels.length > 0 && (
          <div className="labels">
            {labels.map((l) => (
              <span key={l.constraint} className={`lab ${l.match.toLowerCase()}`}
                title={`${l.constraint} "${l.wanted}": ${l.match.toLowerCase()} (catalog: ${l.value ?? NL})`}>
                {LABEL_TEXT[l.match]} {l.constraint}
              </span>
            ))}
          </div>
        )}
        <button className="more" onClick={() => onSimilar(p.productId)} title="Show similar products">More like this</button>
      </div>
    </article>
  );
}

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [cards, setCards] = useState<ResultCard[]>([]);
  const [constraints, setConstraints] = useState<SearchQuery | null>(null);
  const [quick, setQuick] = useState<string[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [millis, setMillis] = useState<number | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const [route, setRoute] = useState(location.pathname);
  const [source, setSource] = useState<Source>("CHAT");
  const [feed, setFeed] = useState<HomeFeed | null>(null);
  const productId = /^\/product\/([^/]+)/.exec(route)?.[1] ?? null;

  const navigate = useCallback((path: string) => {
    history.pushState(null, "", path + location.search);
    setRoute(path);
  }, []);
  useEffect(() => {
    const onPop = () => setRoute(location.pathname);
    addEventListener("popstate", onPop);
    return () => removeEventListener("popstate", onPop);
  }, []);
  useEffect(() => {
    if (productId == null) home().then(setFeed).catch(() => setFeed(null));
  }, [productId]);

  const open = async (id: string, from: Source) => {
    await recordClick(id, from);
    navigate(`/product/${id}`);
  };

  const apply = (r: ChatReply, sent: string) => {
    setConversationId(r.conversationId);
    setMessages((m) => [...m, r.error != null
      ? { role: "assistant", text: r.reply || "Something went wrong.", error: true, retry: sent }
      : { role: "assistant", text: r.reply }]);
    if (r.products) { setCards(r.products); setSource("CHAT"); if (productId) navigate("/"); }
    if (r.constraints) setConstraints(r.constraints);
    setQuick(r.quickReplies);
    setMillis(r.millis);
  };

  const send = async (text: string) => {
    const msg = text.trim();
    if (!msg || busy) return;
    setInput("");
    setQuick([]);
    setMessages((m) => [...m, { role: "shopper", text: msg }]);
    setBusy(true);
    try {
      apply(await chat(msg, conversationId), msg);
    } catch (e: unknown) {
      setMessages((m) => [...m, { role: "assistant", text: `Couldn't reach the assistant (${e instanceof Error ? e.message : "error"}).`, error: true, retry: msg }]);
    } finally {
      setBusy(false);
      setTimeout(() => endRef.current?.scrollIntoView({ behavior: "smooth" }), 0);
    }
  };

  const removePill = async (remove: (q: SearchQuery) => SearchQuery) => {
    if (!constraints) return;
    const next = remove(constraints);
    setConstraints(next);
    setBusy(true);
    try {
      const r = await search(next, conversationId);
      setCards(r.items);
      setSource("SEARCH");
    } catch {
      setMessages((m) => [...m, { role: "assistant", text: "Couldn't rerun the search.", error: true }]);
    } finally {
      setBusy(false);
    }
  };

  const showSimilar = async (id: string) => {
    setBusy(true);
    try {
      const r = await similar(id);
      setCards(r.items);
      setSource("SIMILAR");
      if (productId) navigate("/");
      setActive(null);
    } catch {
      setMessages((m) => [...m, { role: "assistant", text: "Couldn't load similar products.", error: true }]);
    } finally {
      setBusy(false);
    }
  };

  const pick = (id: string) => {
    setActive(id);
    document.getElementById(`card-${id}`)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void send(input);
  };

  return (
    <div className="app">
      <section className="chat" aria-label="Chat">
        <header className="bar">
          <b>crv</b>
          <span className="muted">Amazon Fashion assistant{demo ? " · demo data" : ""}</span>
        </header>
        <div className="log" aria-live="polite">
          {messages.length === 0 && (
            <div className="empty">
              <div className="muted small">Try:</div>
              {["Show me running shoes under $120.", "I'm looking for orange running shorts in size small.", "How many Nike products do you have?"].map((s) => (
                <button key={s} className="chip" onClick={() => void send(s)}>{s}</button>
              ))}
            </div>
          )}
          {messages.map((m, i) => (
            <div key={i} className={`msg ${m.role}${m.error ? " err" : ""}`}>
              {m.role === "assistant" ? <ReplyText text={m.text} onPick={pick} /> : m.text}
              {m.retry != null && i === messages.length - 1 && (
                <button className="chip retry" onClick={() => { const t = m.retry ?? ""; setMessages((x) => x.slice(0, -2)); void send(t); }} disabled={busy}>Retry</button>
              )}
            </div>
          ))}
          {busy && <div className="msg assistant muted" role="status">Searching the catalog…</div>}
          <div ref={endRef} />
        </div>
        {quick.length > 0 && (
          <div className="quick">
            {quick.map((q) => <button key={q} className="chip" onClick={() => void send(q)} disabled={busy}>{q}</button>)}
          </div>
        )}
        <form className="input" onSubmit={onSubmit}>
          <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask about the catalog…" aria-label="Message" maxLength={2000} />
          <button type="submit" disabled={busy || !input.trim()}>Send</button>
        </form>
      </section>
      <section className="results" aria-label="Products found">
        <header className="bar">
          <span>{productId != null ? <b>Product</b> : cards.length > 0 ? <><b>{cards.length}</b> products</> : <b>Home</b>}</span>
          <div className="pills">
            {pills(constraints).map((p) => (
              <span key={p.key} className="pill">
                {p.label}
                <button aria-label={`Remove ${p.label}`} onClick={() => void removePill(p.remove)} disabled={busy}>×</button>
              </span>
            ))}
          </div>
          {millis != null && <span className="muted small">{(millis / 1000).toFixed(1)}s</span>}
        </header>
        {productId != null ? (
          <div className="scroll">
            <ProductPage productId={productId} onBack={() => navigate("/")} renderCards={(items) => (
              <div className="grid">
            {items.map((c, i) => <Card key={c.product.productId} item={c} n={i + 1} active={active === c.product.productId} onSimilar={(id) => void showSimilar(id)} onOpen={(id) => void open(id, "SIMILAR")} />)}
          </div>
            )} />
          </div>
        ) : cards.length > 0 ? (
          <div className="grid">
            {cards.map((c, i) => <Card key={c.product.productId} item={c} n={i + 1} active={active === c.product.productId} onSimilar={(id) => void showSimilar(id)} onOpen={(id) => void open(id, source)} />)}
          </div>
        ) : constraints ? (
          <div className="empty muted small">No products match all of these. Remove a pill to widen the search.</div>
        ) : feed && feed.items.length > 0 ? (
          <div className="scroll">
            <h2 className="pad">{feed.basis === "HISTORY" ? "Picked for you" : "Explore"}</h2>
            <div className="grid">
            {feed.items.map((c, i) => <Card key={c.product.productId} item={c} n={i + 1} active={active === c.product.productId} onSimilar={(id) => void showSimilar(id)} onOpen={(id) => void open(id, "HOME")} />)}
          </div>
          </div>
        ) : (
          <div className="empty muted small">Products the assistant finds show up here.</div>
        )}
        <footer className="note muted small">Prices are sample data. Stock and delivery aren't in the catalog.</footer>
      </section>
    </div>
  );
}
