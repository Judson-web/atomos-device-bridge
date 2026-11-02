-- Atomos Device Bridge: secure backend foundation
-- Apply only to the dedicated atomos-device-bridge Supabase project.

create schema if not exists private;

create table private.device_registry (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_name text not null check (char_length(device_name) between 1 and 80),
  platform text not null default 'android' check (platform in ('android')),
  app_version text,
  public_key text,
  status text not null default 'active' check (status in ('active', 'revoked')),
  paired_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((status = 'revoked') = (revoked_at is not null))
);
create index device_registry_owner_idx on private.device_registry(owner_id, created_at desc);
create index device_registry_active_idx on private.device_registry(owner_id, status) where status = 'active';

create table private.pairing_challenges (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  code_hash bytea not null unique,
  device_name text not null check (char_length(device_name) between 1 and 80),
  platform text not null default 'android' check (platform in ('android')),
  app_version text,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  failed_attempts integer not null default 0 check (failed_attempts between 0 and 10),
  created_at timestamptz not null default now(),
  check (expires_at > created_at)
);
create index pairing_challenges_owner_idx on private.pairing_challenges(owner_id, created_at desc);
create index pairing_challenges_expiry_idx on private.pairing_challenges(expires_at) where consumed_at is null;

create table private.device_sessions (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references private.device_registry(id) on delete cascade,
  token_hash bytea not null unique,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  revoked_reason text,
  check (expires_at > created_at),
  check (revoked_reason is null or revoked_at is not null)
);
create index device_sessions_device_idx on private.device_sessions(device_id, created_at desc);
create index device_sessions_live_idx on private.device_sessions(device_id, expires_at) where revoked_at is null;

create table private.command_audit (
  id bigint generated always as identity primary key,
  device_id uuid not null references private.device_registry(id) on delete cascade,
  session_id uuid references private.device_sessions(id) on delete set null,
  command_id uuid not null,
  action text not null check (action in (
    'get_status', 'get_ui_tree', 'tap', 'swipe', 'press_back', 'press_home',
    'open_notifications', 'type_text', 'capture_screen', 'revoke_session'
  )),
  outcome text not null check (outcome in ('accepted', 'succeeded', 'rejected', 'failed')),
  request_digest bytea,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique(device_id, command_id),
  check (pg_column_size(metadata) <= 2048)
);
create index command_audit_device_created_idx on private.command_audit(device_id, created_at desc);
create index command_audit_created_idx on private.command_audit(created_at desc);

alter table private.device_registry enable row level security;
alter table private.pairing_challenges enable row level security;
alter table private.device_sessions enable row level security;
alter table private.command_audit enable row level security;

-- No client-facing policies are created. The schema is intentionally not exposed
-- through the Data API; trusted Edge Functions use the server-side service role.
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;
revoke all on all tables in schema private from public, anon, authenticated;
grant select, insert, update, delete on all tables in schema private to service_role;
grant usage, select on all sequences in schema private to service_role;
alter default privileges in schema private revoke all on tables from public, anon, authenticated;
alter default privileges in schema private grant select, insert, update, delete on tables to service_role;
alter default privileges in schema private revoke all on sequences from public, anon, authenticated;
alter default privileges in schema private grant usage, select on sequences to service_role;
