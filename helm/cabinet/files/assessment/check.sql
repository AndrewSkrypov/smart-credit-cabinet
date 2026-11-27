select (
    current_database() = 'assessment' and current_user = 'assessment_app'
    and not (r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls)
    and not has_schema_privilege(current_user, 'public', 'create')
    and not has_schema_privilege(current_user, 'assessment_ops', 'usage')
    and not has_database_privilege(current_user, current_database(), 'create,temp')
    and not exists (select from pg_database d where d.datname <> current_database()
                    and has_database_privilege(current_user, d.oid, 'connect'))
    and not exists (select from pg_auth_members m where m.member=r.oid)
    and not exists (select from pg_class c where c.relowner=r.oid)
    and not has_table_privilege(current_user, 'flyway_schema_history', 'select,insert,update,delete')
    and has_table_privilege(current_user, 'assessment_execution', 'select')
    and has_table_privilege(current_user, 'assessment_execution', 'insert')
    and has_table_privilege(current_user, 'assessment_execution', 'update')
    and not has_table_privilege(current_user, 'assessment_execution', 'delete,truncate')
    and has_table_privilege(current_user, 'assessment_exchange_outbox', 'select')
    and has_table_privilege(current_user, 'assessment_exchange_outbox', 'insert')
    and has_table_privilege(current_user, 'assessment_exchange_outbox', 'update')
    and not has_table_privilege(current_user, 'assessment_exchange_outbox', 'delete,truncate')
)::int
from pg_roles r where r.rolname=current_user;
