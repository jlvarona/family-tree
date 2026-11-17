-- FT-010: grants can use SIDE now that its depth rule is decided (decisions table,
-- "Side branches": depth caps only the ascent; every descendant of the anchor and of
-- those ancestors is included). A whole-tree grant ignores depth like the :core Branch
-- does, so V7's "no depth for a whole tree" check, a rule the concept never stated, is
-- dropped. Existing rows satisfy both new constraints.

alter table tree_grant
    drop constraint tree_grant_direction_check,
    add constraint tree_grant_direction_check
        check (direction in ('ANCESTORS', 'DESCENDANTS', 'SIDE', 'WHOLE_TREE')),
    drop constraint tree_grant_whole_tree_depth_check;
