-- FT-001: optimistic concurrency for place and union_child updates.
--
-- person, union_record, event and source have carried a version since V1; place
-- and union_child had none, so their updates couldn't detect a stale write.
-- Every existing row starts at version 1, the same value a new row gets, so
-- clients that read a place or child link before this migration and send
-- version 1 update it normally. An update succeeds only when the version it
-- sends matches the stored one, and then increments it, in the same statement.

alter table place       add column version bigint not null default 1;
alter table union_child add column version bigint not null default 1;
