\getenv generation ASSESSMENT_GENERATION

create schema if not exists assessment_ops authorization assessment_owner;
create table if not exists assessment_ops.identity (
    singleton boolean primary key default true check (singleton),
    generation text not null check (generation ~ '^g[0-9a-f]{16}$')
);
insert into assessment_ops.identity (generation) values (:'generation') on conflict do nothing;
select generation from assessment_ops.identity;
