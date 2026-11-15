-- FT-003: identify accounts by the verified OIDC issuer and subject, not by email.
--
-- Until now an account was found by the token's email claim. Email is mutable
-- profile data that an identity provider can reassign, so it must not decide
-- who owns an account. This migration:
--   1. adds (oidc_issuer, oidc_subject) to account, unique as a pair and
--      immutable once set;
--   2. drops the unique constraint on email, so a reclaimed address can sign in
--      as a new identity without touching the old account;
--   3. leaves every existing row unbound. An unbound account can't be reached
--      through the API until an operator binds it from an authoritative mapping
--      with resolve_legacy_account (README "Resolving legacy accounts").
--      Nothing here, or in the server, links an account by email.
-- Account IDs are unchanged, so trees, memberships, people and audit entries
-- keep pointing at the same rows.

-- 1. Identity columns -----------------------------------------------------------

alter table account
    add column oidc_issuer  text,
    add column oidc_subject text,
    add constraint account_oidc_identity_complete
        check ((oidc_issuer is null) = (oidc_subject is null)),
    add constraint account_oidc_identity_not_blank
        check (btrim(oidc_issuer) <> '' and btrim(oidc_subject) <> ''),
    add constraint account_oidc_identity_key unique (oidc_issuer, oidc_subject);

-- A bound identity never moves to another person: re-pointing an account would
-- hand its trees to whoever holds the new identity.
create function account_identity_immutable() returns trigger
language plpgsql as $$
begin
    if old.oidc_subject is not null
       and (new.oidc_issuer is distinct from old.oidc_issuer
            or new.oidc_subject is distinct from old.oidc_subject) then
        raise exception 'FT-003: account % is already bound to an OIDC identity; the binding cannot be changed', old.id;
    end if;
    return new;
end
$$;

create trigger account_identity_immutable
    before update of oidc_issuer, oidc_subject on account
    for each row execute function account_identity_immutable();

-- 2. Email is profile data ------------------------------------------------------

alter table account drop constraint account_email_key;

-- 3. Legacy accounts ------------------------------------------------------------

-- What an operator resolved, from which evidence, and which empty account (if
-- any) was replaced. Not tenant-scoped, like account itself, so it can't live in
-- audit_log (which requires a tree).
create table account_identity_resolution (
    id                   uuid primary key default gen_random_uuid(),
    account_id           uuid not null references account (id),
    oidc_issuer          text not null,
    oidc_subject         text not null,
    evidence             text not null check (btrim(evidence) <> ''),
    replaced_account_id  uuid,
    resolved_by          text not null default current_user,
    resolved_at          timestamptz not null default now()
);

create index account_identity_resolution_account_id_idx on account_identity_resolution (account_id);

-- Accounts created before FT-003 that no identity can reach yet, with what
-- depends on them. The email is only a hint for finding the owner's records; it
-- is not evidence of who the owner is.
create view unbound_legacy_account as
select a.id,
       a.email,
       a.display_name,
       a.created_at,
       (select count(*) from tree t where t.owner_account_id = a.id)         as owned_trees,
       (select count(*) from membership m where m.account_id = a.id)         as memberships,
       (select count(*) from person p where p.created_by_account_id = a.id)  as people_created,
       (select count(*) from audit_log l where l.actor_account_id = a.id)    as audit_entries
from account a
where a.oidc_subject is null;

-- Binds one unbound account to the identity an authoritative mapping names.
--
-- Fails, changing nothing, when: an argument is blank; the account doesn't
-- exist; the account is already bound to a different identity; or the identity
-- already belongs to another account. Binding an account to the identity it
-- already has is a no-op, so a mapping file can be re-run.
--
-- If that identity signed in after the upgrade, it got a new empty account. Pass
-- p_replace_unused_account => true to delete that account and bind the legacy
-- one instead (false or NULL refuses); this still fails if the new account owns a tree, has a
-- membership, created a person, wrote an audit entry or was itself resolved.
create function resolve_legacy_account(
    p_account_id             uuid,
    p_issuer                 text,
    p_subject                text,
    p_evidence               text,
    p_replace_unused_account boolean default false
) returns void
language plpgsql as $$
declare
    v_account     account%rowtype;
    v_holder      uuid;
    v_holder_refs bigint;
begin
    if p_account_id is null or coalesce(btrim(p_issuer), '') = '' or coalesce(btrim(p_subject), '') = '' then
        raise exception 'FT-003: account ID, issuer and subject are required';
    end if;
    if coalesce(btrim(p_evidence), '') = '' then
        raise exception 'FT-003: evidence must name the authoritative source of the mapping';
    end if;

    select * into v_account from account where id = p_account_id for update;
    if not found then
        raise exception 'FT-003: account % does not exist', p_account_id;
    end if;

    if v_account.oidc_subject is not null then
        if v_account.oidc_issuer = p_issuer and v_account.oidc_subject = p_subject then
            return;
        end if;
        raise exception 'FT-003: account % is already bound to a different identity; nothing changed', p_account_id;
    end if;

    select id into v_holder from account
    where oidc_issuer = p_issuer and oidc_subject = p_subject
    for update;

    if found then
        select (select count(*) from tree where owner_account_id = v_holder)
             + (select count(*) from membership where account_id = v_holder)
             + (select count(*) from person where created_by_account_id = v_holder)
             + (select count(*) from audit_log where actor_account_id = v_holder)
             + (select count(*) from account_identity_resolution where account_id = v_holder)
          into v_holder_refs;
        -- IS NOT TRUE, not NOT: a NULL flag must refuse, never replace.
        if p_replace_unused_account is not true or v_holder_refs > 0 then
            raise exception 'FT-003: identity (%, %) already belongs to account % (% references); nothing changed',
                p_issuer, p_subject, v_holder, v_holder_refs;
        end if;
        delete from account where id = v_holder;
    end if;

    -- A login that creates an account for this identity between the check above
    -- and this update makes it fail on account_oidc_identity_key, rolling back.
    update account set oidc_issuer = p_issuer, oidc_subject = p_subject where id = p_account_id;

    insert into account_identity_resolution (account_id, oidc_issuer, oidc_subject, evidence, replaced_account_id)
    values (p_account_id, p_issuer, p_subject, p_evidence, v_holder);
end
$$;

revoke all on function resolve_legacy_account(uuid, text, text, text, boolean) from public;
