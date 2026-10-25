-- Historical rows retain unknown names. New revisions capture the authorized actor at save time.
alter table rule_sets add column author_name varchar(255);
alter table rule_sets add column author_role varchar(40);
alter table product_versions add column author_name varchar(255);
alter table product_versions add column author_role varchar(40);
