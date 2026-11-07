-- What each anonymous shopper clicked, from the homepage, a search or the chat. It shapes their next homepage.
-- There is no foreign key to product: an ingest replaces the catalog, and product ids are stable across ingests.
create table interaction (
    id         bigserial primary key,
    shopper_id varchar(64) not null,
    product_id varchar(16) not null,
    source     varchar(16) not null,
    created_at timestamptz not null default now(),
    constraint interaction_source_known check (source in ('HOME', 'CHAT', 'SEARCH', 'SIMILAR'))
);

create index interaction_shopper_recent on interaction (shopper_id, created_at desc);
