-- FT-010: branch grants and their cached, resolved person sets.
--
-- A grant gives one membership (account in a tree) a role over a branch: an anchor
-- person, a direction, an optional depth and whether direct partners are included
-- (Branch in :core). Grants are stored and resolved here; FT-012 to FT-014 decide
-- what they allow. Nothing in V1-V6 stored grants, so there is no existing data to
-- check: the tables start empty and only the per-tree generation rows are backfilled.
--
-- Two kinds of rows, deliberately treated differently:
--   * tree_grant is real data. Its anchor is a reference like any other: a person
--     anchoring a grant can't be deleted (FT-001 409) or merged away (FT-007 409).
--   * grant_scope and grant_scope_state are derived from tree_grant and the graph.
--     They never block a delete: removing a person or a grant removes their cache
--     rows (on delete cascade), and the cache is rebuilt on the next read.
--
-- Staleness: tree_graph_generation.generation advances in the same transaction as
-- every write that can change a branch (people created or deleted, unions, child
-- links, merges). A cached scope is used only while its recorded generation and
-- grant version are still the current ones; otherwise it is rebuilt.

-- 1. Grants ----------------------------------------------------------------------

create table tree_grant (
    id                    uuid        primary key,
    tree_id               uuid        not null references tree (id),
    account_id            uuid        not null,
    role                  text        not null
        constraint tree_grant_role_check check (role in ('ADMIN', 'BRANCH_OWNER', 'CONTRIBUTOR', 'VIEWER')),
    anchor_person_id      uuid        not null,
    -- SIDE is a valid branch direction but has no agreed resolution yet (FT-010 /
    -- FT-015 side-depth decision); a later migration adds it with its resolver.
    direction             text        not null
        constraint tree_grant_direction_check check (direction in ('ANCESTORS', 'DESCENDANTS', 'WHOLE_TREE')),
    -- Null: unlimited. Generations from the anchor (parents or children = 1).
    depth                 integer
        constraint tree_grant_depth_check check (depth is null or depth > 0),
    include_partners      boolean     not null,
    version               bigint      not null default 1,
    created_by_account_id uuid        not null references account (id),
    created_at            timestamptz not null default now(),
    constraint tree_grant_whole_tree_depth_check check (direction <> 'WHOLE_TREE' or depth is null),
    constraint tree_grant_id_tree_id_key unique (id, tree_id),
    -- Same-tree by construction: membership's key is (tree_id, account_id).
    constraint tree_grant_membership_same_tree_fkey
        foreign key (tree_id, account_id) references membership (tree_id, account_id),
    constraint tree_grant_anchor_same_tree_fkey
        foreign key (anchor_person_id, tree_id) references person (id, tree_id)
);

create index tree_grant_membership_idx on tree_grant (tree_id, account_id);
create index tree_grant_anchor_person_id_idx on tree_grant (anchor_person_id);

-- 2. Graph generation per tree ---------------------------------------------------
-- One row per tree. Graph writers advance it as their first statement (after the
-- FT-007 advisory lock where they take it), which also serializes them per tree;
-- a scope rebuild holds it FOR SHARE, so no graph write can commit in the middle.

create table tree_graph_generation (
    tree_id    uuid   primary key references tree (id),
    generation bigint not null default 0
        constraint tree_graph_generation_generation_check check (generation >= 0)
);

insert into tree_graph_generation (tree_id) select id from tree;

-- 3. Derived scope cache ---------------------------------------------------------

create table grant_scope_state (
    grant_id         uuid        primary key,
    tree_id          uuid        not null,
    grant_version    bigint      not null,
    graph_generation bigint      not null,
    built_at         timestamptz not null default now(),
    constraint grant_scope_state_grant_same_tree_fkey
        foreign key (grant_id, tree_id) references tree_grant (id, tree_id) on delete cascade
);

create table grant_scope (
    grant_id  uuid not null,
    tree_id   uuid not null,
    person_id uuid not null,
    primary key (grant_id, person_id),
    constraint grant_scope_grant_same_tree_fkey
        foreign key (grant_id, tree_id) references tree_grant (id, tree_id) on delete cascade,
    constraint grant_scope_person_same_tree_fkey
        foreign key (person_id, tree_id) references person (id, tree_id) on delete cascade
);

create index grant_scope_person_id_idx on grant_scope (person_id);
create index grant_scope_tree_id_idx on grant_scope (tree_id);
