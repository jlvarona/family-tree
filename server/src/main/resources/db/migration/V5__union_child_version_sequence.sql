-- FT-001 review: a child link is identified by (union_id, child_id), so a link that
-- is deleted and created again has the same key. With a per-row counter both
-- incarnations started at version 1, and a delayed DELETE or PUT carrying the first
-- link's version changed the replacement.
--
-- Child-link versions now come from one sequence, for every insert and every update,
-- so a value is never handed out twice for any key. They are opaque change tokens:
-- they increase, but can skip numbers. Existing rows keep their versions; the
-- sequence starts above the highest one, so no existing value can be issued again.
-- Person, union, event, place and source keep their per-record increment (their IDs
-- are never reused).

create sequence union_child_version_seq as bigint minvalue 1 owned by union_child.version;

-- Set the default first so the table lock covers seeding from existing versions.
alter table union_child alter column version set default nextval('union_child_version_seq');

select setval('union_child_version_seq', coalesce((select max(version) from union_child), 0) + 1, false);
