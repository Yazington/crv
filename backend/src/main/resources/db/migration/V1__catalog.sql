-- A product groups the variants (catalog records) that belong together.
-- Its id is the smallest parent_asin in the group, so it stays stable across re-ingests.
create table product (
    id            varchar(16) primary key,
    variant_count integer not null check (variant_count > 0)
);

-- A variant is one catalog record: one colour, one size, its own parent_asin.
create table variant (
    asin              varchar(16) primary key,
    product_id        varchar(16) not null references product (id),
    title             text not null,
    brand             text,
    color             text,
    size              text,
    price             numeric(10, 2) not null,
    list_price        numeric(10, 2),
    list_price_status varchar(8),
    shared_photo      boolean not null,
    average_rating    numeric(2, 1),
    rating_count      integer,
    categories        text[] not null,
    department        text,
    main_category     text,
    features          text[] not null,
    description       text[] not null,
    details           jsonb not null,
    specs             jsonb not null,
    images            jsonb not null,
    first_available   date,
    quality           double precision not null,
    popularity        double precision not null,
    constraint list_price_status_known check (list_price_status in ('TRUSTED', 'SUSPECT')),
    constraint list_price_has_status check ((list_price is null) = (list_price_status is null))
);

create index variant_product_id on variant (product_id);
create index variant_price on variant (price);
create index variant_brand on variant (lower(brand));
create index variant_categories on variant using gin (categories);
