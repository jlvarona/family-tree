-- FT-065: enforce tenant isolation in the database, not only in the service layer.
--
-- Every reference between tenant-owned rows must stay inside one tree. V1 only
-- had single-column foreign keys, so a union could name a partner from another
-- tree, an event could point at another tree's person or place, and so on.
-- This migration:
--   1. stops with diagnostics if existing rows already break that rule
--      (no silent fixing: someone has to decide what the right data is);
--   2. adds tree_id to union_child and citation, backfilled from their parent;
--   3. adds composite (id, tree_id) foreign keys so a cross-tree reference
--      fails at insert time.
-- The polymorphic citation target (target_type/target_id) cannot have a foreign
-- key; it is checked here and validated by the application in FT-002.

-- 1. Preflight -----------------------------------------------------------------

do $$
declare
    problems text := '';
    bad      text;
begin
    select string_agg(ur.id::text, ', ') into bad from (
        select ur.id from union_record ur
        join person p on p.id = ur.partner1_id
        where p.tree_id <> ur.tree_id
        union
        select ur.id from union_record ur
        join person p on p.id = ur.partner2_id
        where p.tree_id <> ur.tree_id
        order by 1 limit 20
    ) ur;
    if bad is not null then
        problems := problems || E'\n  union_record with a partner from another tree: ' || bad;
    end if;

    select string_agg(x.k, ', ') into bad from (
        select uc.union_id::text || ':' || uc.child_id::text as k
        from union_child uc
        join union_record ur on ur.id = uc.union_id
        join person p on p.id = uc.child_id
        where p.tree_id <> ur.tree_id
        order by 1 limit 20
    ) x;
    if bad is not null then
        problems := problems || E'\n  union_child (union:child) with a child from another tree: ' || bad;
    end if;

    select string_agg(x.id::text, ', ') into bad from (
        select e.id from event e
        left join person p on p.id = e.person_id
        left join union_record ur on ur.id = e.union_id
        left join place pl on pl.id = e.place_id
        where p.tree_id <> e.tree_id
           or ur.tree_id <> e.tree_id
           or pl.tree_id <> e.tree_id
        order by 1 limit 20
    ) x;
    if bad is not null then
        problems := problems || E'\n  event whose person, union or place is in another tree: ' || bad;
    end if;

    select string_agg(x.k, ', ') into bad from (
        select m.tree_id::text || ':' || m.account_id::text as k
        from membership m
        join person p on p.id = m.person_id
        where p.tree_id <> m.tree_id
        order by 1 limit 20
    ) x;
    if bad is not null then
        problems := problems || E'\n  membership (tree:account) linked to a person in another tree: ' || bad;
    end if;

    select string_agg(x.id::text, ', ') into bad from (
        select c.id from citation c
        join source s on s.id = c.source_id
        left join person p on c.target_type = 'PERSON' and p.id = c.target_id
        left join event e on c.target_type = 'EVENT' and e.id = c.target_id
        left join union_record ur on c.target_type = 'UNION' and ur.id = c.target_id
        where coalesce(p.tree_id, e.tree_id, ur.tree_id) is null
           or coalesce(p.tree_id, e.tree_id, ur.tree_id) <> s.tree_id
        order by 1 limit 20
    ) x;
    if bad is not null then
        problems := problems || E'\n  citation whose target is missing or in another tree than its source: ' || bad;
    end if;

    if problems <> '' then
        raise exception 'FT-065 preflight found cross-tree references (up to 20 ids per check):%', problems
            using hint = 'Fix or delete these rows explicitly, then restart so Flyway re-runs V2. '
                      || 'Nothing was changed: the migration rolled back.';
    end if;
end
$$;

-- 2. tree_id on union_child and citation ---------------------------------------

alter table union_child add column tree_id uuid;
update union_child uc set tree_id = ur.tree_id from union_record ur where ur.id = uc.union_id;
alter table union_child alter column tree_id set not null;
alter table union_child add constraint union_child_tree_id_fkey foreign key (tree_id) references tree (id);
create index union_child_tree_id_idx on union_child (tree_id);

alter table citation add column tree_id uuid;
update citation c set tree_id = s.tree_id from source s where s.id = c.source_id;
alter table citation alter column tree_id set not null;
alter table citation add constraint citation_tree_id_fkey foreign key (tree_id) references tree (id);
create index citation_tree_id_idx on citation (tree_id);

-- 3. Same-tree composite foreign keys -------------------------------------------
-- A composite FK needs a unique key on the referenced (id, tree_id) pair. id is
-- already the primary key, so these are trivially unique; they exist only as
-- FK targets. Composite FKs use MATCH SIMPLE: a null reference column (an
-- optional partner, place or linked person) skips the check, as intended.

alter table person       add constraint person_id_tree_id_key       unique (id, tree_id);
alter table union_record add constraint union_record_id_tree_id_key unique (id, tree_id);
alter table place        add constraint place_id_tree_id_key        unique (id, tree_id);
alter table source       add constraint source_id_tree_id_key       unique (id, tree_id);

alter table union_record
    add constraint union_record_partner1_same_tree_fkey
        foreign key (partner1_id, tree_id) references person (id, tree_id),
    add constraint union_record_partner2_same_tree_fkey
        foreign key (partner2_id, tree_id) references person (id, tree_id);

alter table union_child
    add constraint union_child_union_same_tree_fkey
        foreign key (union_id, tree_id) references union_record (id, tree_id),
    add constraint union_child_child_same_tree_fkey
        foreign key (child_id, tree_id) references person (id, tree_id);

alter table event
    add constraint event_person_same_tree_fkey
        foreign key (person_id, tree_id) references person (id, tree_id),
    add constraint event_union_same_tree_fkey
        foreign key (union_id, tree_id) references union_record (id, tree_id),
    add constraint event_place_same_tree_fkey
        foreign key (place_id, tree_id) references place (id, tree_id);

alter table membership
    add constraint membership_person_same_tree_fkey
        foreign key (person_id, tree_id) references person (id, tree_id);

alter table citation
    add constraint citation_source_same_tree_fkey
        foreign key (source_id, tree_id) references source (id, tree_id);
