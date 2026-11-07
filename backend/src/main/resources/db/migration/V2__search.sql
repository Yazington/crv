-- Full-text search over each variant (docs/data-and-search.md, "Search flow").
-- Weights: title and category path (A), colour and brand (B), the first feature lines (C).
-- The category path skips its first node: the root "Clothing, Shoes & Jewelry" would make every
-- record match "shoes".
create function variant_search_doc(title text, categories text[], color text, brand text, features text[])
returns tsvector
language sql
immutable
parallel safe
as $$
    select setweight(to_tsvector('english', coalesce(title, '')), 'A')
        || setweight(to_tsvector('english', coalesce(array_to_string(categories[2:], ' '), '')), 'A')
        || setweight(to_tsvector('english', coalesce(color, '') || ' ' || coalesce(brand, '')), 'B')
        || setweight(to_tsvector('english', coalesce(array_to_string(features[1:5], ' '), '')), 'C')
$$;

alter table variant
    add column search_doc tsvector
        generated always as (variant_search_doc(title, categories, color, brand, features)) stored;

create index variant_search_doc on variant using gin (search_doc);
