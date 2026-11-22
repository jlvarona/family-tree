-- FT-014: change proposals.
--
-- A proposal is a graph write a member couldn't make live (outside their branches, or a
-- change that would widen their own reach or reveal a hidden living person), kept for the
-- tree owner or the branch owner of every affected person to review. A pending proposal
-- is not a graph write: nothing in the graph changes until a reviewer approves it, and
-- approval replays the same versioned write (FT-001, FT-012) in the reviewer's own
-- transaction.
--
-- * payload: the write's request body as JSON (without its version), the proposer's own
--   input. Never returned to anyone but the proposer and the reviewers.
-- * target_id: the record the write's path names (person, union or event ID,
--   'unionId:childId' for a child link, the union of a new child link); null for creates.
-- * base_version: the record's version the proposal was made against (updates and
--   deletes). Approval sends it, so a record that changed since is a stale 409.
-- * subject_ids: the people the write's authorization checks touched (before and after
--   the change). They decide who may review and list it. Deliberately not foreign keys:
--   a pending proposal never blocks deleting or merging a person; approval re-resolves
--   everything in the tree and a missing record is the usual 404.
-- * Rows are never deleted; decided proposals keep their payload for the activity feed.

create table change_proposal (
    id                   uuid        primary key,
    tree_id              uuid        not null references tree (id),
    proposer_account_id  uuid        not null,
    operation            text        not null
        constraint change_proposal_operation_check check (operation in (
            'UPDATE_PERSON', 'DELETE_PERSON',
            'CREATE_UNION', 'UPDATE_UNION', 'DELETE_UNION',
            'ADD_CHILD', 'UPDATE_CHILD', 'DELETE_CHILD',
            'CREATE_EVENT', 'UPDATE_EVENT', 'DELETE_EVENT',
            'CREATE_CITATION')),
    target_id            text,
    base_version         bigint,
    payload              text        not null,
    subject_ids          uuid[]      not null
        constraint change_proposal_subjects_check check (cardinality(subject_ids) > 0),
    reason               text        not null
        constraint change_proposal_reason_check check (reason in ('OUTSIDE_BRANCH', 'WIDENS_REACH', 'REVEALS_PRIVATE')),
    status               text        not null default 'PENDING'
        constraint change_proposal_status_check check (status in ('PENDING', 'APPLIED', 'REJECTED', 'WITHDRAWN')),
    reviewer_account_id  uuid,
    -- JSON list of ProposalField names, once applied.
    approved_fields      text,
    created_at           timestamptz not null default now(),
    decided_at           timestamptz,
    version              bigint      not null default 1,
    constraint change_proposal_decided_check check ((status = 'PENDING') = (decided_at is null)),
    constraint change_proposal_reviewer_check check (
        (status in ('APPLIED', 'REJECTED')) = (reviewer_account_id is not null)),
    constraint change_proposal_approved_check check ((status = 'APPLIED') = (approved_fields is not null)),
    -- Proposer and reviewer are members of the proposal's tree (same-tree references).
    constraint change_proposal_proposer_member_fkey
        foreign key (tree_id, proposer_account_id) references membership (tree_id, account_id),
    constraint change_proposal_reviewer_member_fkey
        foreign key (tree_id, reviewer_account_id) references membership (tree_id, account_id)
);

create index change_proposal_tree_status_idx on change_proposal (tree_id, status, created_at desc);
create index change_proposal_tree_proposer_idx on change_proposal (tree_id, proposer_account_id);
