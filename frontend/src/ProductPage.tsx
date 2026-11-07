import { useEffect, useMemo, useState } from "react";
import { product, similar, type ProductDetail, type ResultCard } from "./api";

const NL = "Not listed";
const money = (v: number) => `$${v.toFixed(2)}`;

export function ProductPage({ productId, onBack, renderCards }: {
  productId: string;
  onBack: () => void;
  renderCards: (cards: ResultCard[]) => React.ReactNode;
}) {
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [vi, setVi] = useState(0);
  const [photo, setPhoto] = useState(0);
  const [more, setMore] = useState<ResultCard[]>([]);

  useEffect(() => {
    let live = true;
    setDetail(null); setError(null); setVi(0); setPhoto(0); setMore([]);
    product(productId).then((d) => { if (live) setDetail(d); }).catch(() => { if (live) setError("Couldn't load this product."); });
    similar(productId).then((r) => { if (live) setMore(r.items); }).catch(() => undefined);
    return () => { live = false; };
  }, [productId]);

  const v = detail?.variants[vi];
  const images = useMemo(() => (v && v.images.length > 0 ? v.images : detail?.images ?? []), [v, detail]);
  if (error) return <div className="empty small"><button className="chip" onClick={onBack}>← Back</button>{error}</div>;
  if (!detail || !v) return <div className="empty muted small" role="status">Loading product…</div>;

  const colors = [...new Set(detail.variants.map((x) => x.color ?? NL))];
  const sizes = [...new Set(detail.variants.filter((x) => (x.color ?? NL) === (v.color ?? NL)).map((x) => x.size ?? NL))];
  const choose = (color: string, size: string) => {
    const i = detail.variants.findIndex((x) => (x.color ?? NL) === color && (x.size ?? NL) === size);
    const j = i >= 0 ? i : detail.variants.findIndex((x) => (x.color ?? NL) === color);
    if (j >= 0) { setVi(j); setPhoto(0); }
  };
  const main = images[photo] ?? images[0];
  const facts = { ...v.specs, ...v.details };

  return (
    <div className="pdp">
      <div className="bar"><button className="chip" onClick={onBack}>← Back</button><span className="muted small">{detail.variants.length} variants</span></div>
      <div className="pdp-top">
        <div className="gallery">
          <div className="main">{main ? <img src={main.hiRes ?? main.large} alt={v.title} /> : <span className="muted">No photo</span>}</div>
          <div className="thumbs">
            {images.slice(0, 10).map((im, i) => (
              <button key={im.large} className={i === photo ? "on" : ""} onClick={() => setPhoto(i)} aria-label={`Photo ${i + 1}`}>
                <img src={im.thumb ?? im.large} alt="" />
              </button>
            ))}
          </div>
        </div>
        <div className="info">
          <h1>{v.title}</h1>
          <div className="row muted"><span>{v.brand ?? NL}</span></div>
          <div className="row"><span className="price big">{money(v.price)}</span>
            {detail.maxPrice > detail.minPrice && <span className="muted small">all variants {money(detail.minPrice)}–{money(detail.maxPrice)}</span>}</div>
          <div className="row muted small">{v.averageRating != null && v.ratingCount != null ? `★ ${v.averageRating.toFixed(1)} (${v.ratingCount} reviews)` : `Rating ${NL.toLowerCase()}`}</div>
          <label className="pick">Colour
            <select value={v.color ?? NL} onChange={(e) => choose(e.target.value, v.size ?? NL)}>
              {colors.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label className="pick">Size
            <select value={v.size ?? NL} onChange={(e) => choose(v.color ?? NL, e.target.value)}>
              {sizes.map((s) => <option key={s}>{s}</option>)}
            </select>
          </label>
          <h2>Features</h2>
          {v.features.length ? <ul>{v.features.map((f, i) => <li key={i}>{f}</li>)}</ul> : <p className="muted">{NL}</p>}
          <h2>Description</h2>
          {v.description.length ? v.description.map((d, i) => <p key={i}>{d}</p>) : <p className="muted">{NL}</p>}
          <h2>Details</h2>
          {Object.keys(facts).length ? (
            <table><tbody>{Object.entries(facts).map(([k, val]) => <tr key={k}><th>{k}</th><td>{val || NL}</td></tr>)}</tbody></table>
          ) : <p className="muted">{NL}</p>}
        </div>
      </div>
      <h2 className="pad">More like this</h2>
      {more.length ? renderCards(more) : <div className="empty muted small">No similar products found.</div>}
    </div>
  );
}
