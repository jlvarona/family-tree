-- FT-011: invitations and account-to-person linking.
--
-- * invitation: a pending invitation to one tree. The secret token is never stored;
--   only its SHA-256 digest (32 bytes), which is how a redemption finds the row. A row
--   exists only while the invitation is pending: accepting or revoking it deletes it
--   (the audit log keeps the history), so a token works at most once.
-- * invitation_grant: grants the owner proposes, copied to tree_grant (FT-010) when
--   the invitation is accepted.
-- * membership: gets a version (linking and unlinking a person are versioned writes)
--   and a person can be linked to at most one membership of its tree.
--
-- Every person reference here is a real, same-tree reference like tree_grant's anchor:
-- a person named by a pending invitation can't be deleted (FT-001 409) or merged away
-- (FT-007 409) until the owner revokes the invitation.

-- 1. Preflight: no person may already be linked to two memberships of one tree. -------
-- Phase 1 never linked any person, so this can only fail on hand-edited data.

do $$
declare
    bad text;
begin
    select string_agg(tree_id || ':' || person_id, ', ')
      into bad
      from (
        select tree_id, person_id
          from membership
         where person_id is not null
         group by tree_id, person_id
        having count(*) > 1
      ) dup;
    if bad is not null then
        raise exception 'V9: people linked to more than one membership (tree:person): %. Unlink all but one first.', bad;
    end if;
end
$$;

-- 2. Membership ------------------------------------------------------------------------

alter table membership
    add column version bigint not null default 1;

create unique index membership_tree_person_key on membership (tree_id, person_id) where person_id is not null;

-- 3. Invitations -----------------------------------------------------------------------

create table invitation (
    id                    uuid        primary key,
    tree_id               uuid        not null references tree (id),
    token_digest          bytea       not null
        constraint invitation_token_digest_check check (octet_length(token_digest) = 32),
    -- Delivery address only (FT-003: email never identifies an account). Null for links.
    email                 text
        constraint invitation_email_check check (email is null or (length(email) between 3 and 254)),
    person_id             uuid,
    expires_at            timestamptz not null,
    created_by_account_id uuid        not null references account (id),
    created_at            timestamptz not null default now(),
    version               bigint      not null default 1,
    constraint invitation_expiry_check check (expires_at > created_at),
    constraint invitation_token_digest_key unique (token_digest),
    constraint invitation_id_tree_id_key unique (id, tree_id),
    constraint invitation_person_same_tree_fkey
        foreign key (person_id, tree_id) references person (id, tree_id)
);

-- One pending invitation per person: the person can be linked to one member only.
create unique index invitation_tree_person_key on invitation (tree_id, person_id) where person_id is not null;
create index invitation_tree_id_idx on invitation (tree_id);

create table invitation_grant (
    id                uuid    primary key,
    invitation_id     uuid    not null,
    tree_id           uuid    not null,
    ordinal           integer not null
        constraint invitation_grant_ordinal_check check (ordinal >= 0),
    role              text    not null
        constraint invitation_grant_role_check check (role in ('ADMIN', 'BRANCH_OWNER', 'CONTRIBUTOR', 'VIEWER')),
    anchor_person_id  uuid    not null,
    direction         text    not null
        constraint invitation_grant_direction_check check (direction in ('ANCESTORS', 'DESCENDANTS', 'SIDE', 'WHOLE_TREE')),
    depth             integer
        constraint invitation_grant_depth_check check (depth is null or depth > 0),
    include_partners  boolean not null,
    constraint invitation_grant_ordinal_key unique (invitation_id, ordinal),
    -- Proposed grants go with their invitation when it is accepted or revoked.
    constraint invitation_grant_invitation_same_tree_fkey
        foreign key (invitation_id, tree_id) references invitation (id, tree_id) on delete cascade,
    constraint invitation_grant_anchor_same_tree_fkey
        foreign key (anchor_person_id, tree_id) references person (id, tree_id)
);

create index invitation_grant_anchor_person_id_idx on invitation_grant (anchor_person_id);
