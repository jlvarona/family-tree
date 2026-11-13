-- Phase 1 (Foundations): accounts, trees, people, unions, events, sources.
-- Every tenant-owned table carries tree_id (AGENTS.md "Tenant isolation").
-- Relationships go through union_record/union_child, never direct
-- parent/child columns on person (AGENTS.md "Union model").

create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

-- Not tenant-scoped: one account can belong to many trees.
create table account (
    id                uuid primary key default gen_random_uuid(),
    email             text not null unique,
    display_name      text not null,
    locale            text not null default 'en' check (locale in ('en', 'es')),
    created_at        timestamptz not null default now()
);

create table tree (
    id                uuid primary key default gen_random_uuid(),
    name              text not null,
    owner_account_id  uuid not null references account (id),
    version           bigint not null default 1,
    created_at        timestamptz not null default now()
);

create table person (
    id                      uuid primary key default gen_random_uuid(),
    tree_id                 uuid not null references tree (id),
    given_names             text not null,
    surname_paternal        text,
    surname_maternal        text,
    sex                     text not null check (sex in ('FEMALE', 'MALE', 'UNKNOWN')),
    is_living               boolean not null default true,
    profile_media_id        uuid,
    bio                     text,
    created_by_account_id   uuid references account (id),
    version                 bigint not null default 1,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);

create index person_tree_id_idx on person (tree_id);
-- Backs the duplicate-person fuzzy name check (concept-architecture.md).
create index person_given_names_trgm_idx on person using gin (given_names gin_trgm_ops);
create index person_surname_paternal_trgm_idx on person using gin (surname_paternal gin_trgm_ops);
create index person_surname_maternal_trgm_idx on person using gin (surname_maternal gin_trgm_ops);

create table place (
    id          uuid primary key default gen_random_uuid(),
    tree_id     uuid not null references tree (id),
    name        text not null,
    hierarchy   text,
    latitude    double precision,
    longitude   double precision
);

create index place_tree_id_idx on place (tree_id);

-- Named union_record, not "union": union is a reserved SQL keyword.
create table union_record (
    id                  uuid primary key default gen_random_uuid(),
    tree_id             uuid not null references tree (id),
    partner1_id         uuid not null references person (id),
    partner2_id         uuid references person (id),
    type                text not null check (type in ('MARRIED', 'DIVORCED', 'PARTNERS', 'UNKNOWN')),
    start_date_text     text,
    start_date_earliest date,
    start_date_latest   date,
    end_date_text       text,
    end_date_earliest   date,
    end_date_latest     date,
    version             bigint not null default 1,
    check (partner1_id <> partner2_id)
);

create index union_record_tree_id_idx on union_record (tree_id);
create index union_record_partner1_id_idx on union_record (partner1_id);
create index union_record_partner2_id_idx on union_record (partner2_id);

create table union_child (
    union_id    uuid not null references union_record (id),
    child_id    uuid not null references person (id),
    relation    text not null check (relation in ('BIRTH', 'ADOPTED', 'STEP', 'FOSTER')),
    primary key (union_id, child_id)
);

create index union_child_child_id_idx on union_child (child_id);

create table event (
    id              uuid primary key default gen_random_uuid(),
    tree_id         uuid not null references tree (id),
    person_id       uuid references person (id),
    union_id        uuid references union_record (id),
    type            text not null check (type in (
        'BIRTH', 'DEATH', 'MARRIAGE', 'DIVORCE', 'BAPTISM', 'BURIAL',
        'IMMIGRATION', 'EMIGRATION', 'RESIDENCE', 'OCCUPATION', 'OTHER'
    )),
    date_text       text,
    date_earliest   date,
    date_latest     date,
    place_id        uuid references place (id),
    notes           text,
    version         bigint not null default 1,
    check ((person_id is null) <> (union_id is null))
);

create index event_tree_id_idx on event (tree_id);
create index event_person_id_idx on event (person_id);
create index event_union_id_idx on event (union_id);

create table source (
    id              uuid primary key default gen_random_uuid(),
    tree_id         uuid not null references tree (id),
    title           text not null,
    type            text not null check (type in (
        'VITAL_RECORD', 'CENSUS', 'CHURCH_RECORD', 'NEWSPAPER',
        'FAMILY_BIBLE', 'ORAL_HISTORY', 'OTHER'
    )),
    author          text,
    publisher       text,
    repository      text,
    url             text,
    date_text       text,
    date_earliest   date,
    date_latest     date,
    version         bigint not null default 1
);

create index source_tree_id_idx on source (tree_id);

create table citation (
    id              uuid primary key default gen_random_uuid(),
    source_id       uuid not null references source (id),
    target_type     text not null check (target_type in ('PERSON', 'EVENT', 'UNION')),
    target_id       uuid not null,
    page            text,
    confidence      smallint not null check (confidence between 0 and 3),
    note            text,
    media_id        uuid
);

create index citation_source_id_idx on citation (source_id);
create index citation_target_idx on citation (target_type, target_id);

-- Links an account to a tree and, once linked, to that member's own person
-- (drives the home branch). Not yet enforced server-side until Phase 2.
create table membership (
    tree_id     uuid not null references tree (id),
    account_id  uuid not null references account (id),
    person_id   uuid references person (id),
    status      text not null check (status in ('INVITED', 'ACTIVE')),
    primary key (tree_id, account_id)
);

create index membership_person_id_idx on membership (person_id);

-- Immutable, append-only. Written in the same transaction as the mutation
-- it describes (AGENTS.md "Every write is audited"). Never updated/deleted.
-- before_json/after_json are JSON-encoded text, not the jsonb type: nothing
-- queries into them yet, so plain text avoids JDBC jsonb binding friction;
-- revisit via a new migration if/when audit entries need JSON querying.
create table audit_log (
    id                  uuid primary key default gen_random_uuid(),
    tree_id             uuid not null references tree (id),
    actor_account_id    uuid not null references account (id),
    entity_type         text not null,
    entity_id           text not null,
    action              text not null check (action in ('CREATE', 'UPDATE', 'DELETE')),
    before_json         text,
    after_json          text,
    at                  timestamptz not null default now()
);

create index audit_log_tree_id_idx on audit_log (tree_id);
create index audit_log_entity_idx on audit_log (entity_type, entity_id);
