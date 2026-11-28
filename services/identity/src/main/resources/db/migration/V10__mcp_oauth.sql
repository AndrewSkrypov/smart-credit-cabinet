create table mcp_oauth_flows (
    flow_hash varchar(64) primary key,
    csrf_hash varchar(64) not null,
    client_id varchar(255) not null,
    redirect_uri varchar(1000) not null,
    resource varchar(300) not null,
    scope varchar(64) not null,
    oauth_state varchar(512) not null,
    pkce_challenge varchar(43) not null,
    expires_at timestamptz not null,
    code_hash varchar(64) unique,
    user_id uuid references users(id) on delete cascade,
    credentials_version integer,
    check (
        (code_hash is null and user_id is null and credentials_version is null)
        or (code_hash is not null and user_id is not null and credentials_version is not null)
    )
);
create index idx_mcp_oauth_flows_expiry on mcp_oauth_flows(expires_at);

create table mcp_oauth_access (
    token_hash varchar(64) primary key,
    client_id varchar(255) not null,
    resource varchar(300) not null,
    scope varchar(64) not null,
    user_id uuid not null references users(id) on delete cascade,
    credentials_version integer not null,
    expires_at timestamptz not null
);
create index idx_mcp_oauth_access_expiry on mcp_oauth_access(expires_at);

create table mcp_oauth_assertions (
    assertion_hash varchar(64) primary key,
    access_hash varchar(64) not null references mcp_oauth_access(token_hash) on delete cascade,
    expires_at timestamptz not null
);
create index idx_mcp_oauth_assertions_access on mcp_oauth_assertions(access_hash);
create index idx_mcp_oauth_assertions_expiry on mcp_oauth_assertions(expires_at);
