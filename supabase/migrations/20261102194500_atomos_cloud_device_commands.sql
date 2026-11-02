create table if not exists private.device_commands (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null references private.device_registry(id) on delete cascade,
  command_type text not null check (command_type in ('inspect','tap','swipe','back','home','notifications','type')),
  payload jsonb not null default '{}'::jsonb check (octet_length(payload::text) <= 8192),
  status text not null default 'queued' check (status in ('queued','claimed','completed','failed','expired','cancelled')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '60 seconds'),
  claimed_at timestamptz, completed_at timestamptz, result jsonb, error text,
  requested_by uuid not null references auth.users(id) on delete cascade,
  constraint device_commands_expiry_check check (expires_at > created_at)
);
create index if not exists device_commands_device_queue_idx on private.device_commands(device_id,status,created_at) where status in ('queued','claimed');
create index if not exists device_commands_owner_created_idx on private.device_commands(owner_id,created_at desc);
alter table private.device_commands enable row level security;
revoke all on private.device_commands from public,anon,authenticated;
grant usage on schema private to service_role;
grant select,insert,update,delete on private.device_commands to service_role;