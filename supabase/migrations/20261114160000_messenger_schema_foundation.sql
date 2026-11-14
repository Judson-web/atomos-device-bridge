-- Atomos Messenger schema foundation.
-- This file is intentionally not deployed by this change. Apply only after reviewing
-- the target Supabase project, auth provider configuration, storage policies and RLS.
-- All message and attachment payload columns contain protocol ciphertext only.

create schema if not exists messenger;
revoke all on schema messenger from public, anon;
grant usage on schema messenger to authenticated;

create table if not exists messenger.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  username text not null,
  display_name text not null default '',
  avatar_object_key text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint messenger_profiles_username_format check (
    username = lower(username)
    and username ~ '^[a-z0-9_]{3,32}$'
  ),
  constraint messenger_profiles_display_name_length check (char_length(display_name) <= 80)
);
create unique index if not exists messenger_profiles_username_unique
  on messenger.profiles (username);

create table if not exists messenger.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  label text not null default 'Android device',
  credential bytea not null,
  signature_public_key bytea not null,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz,
  constraint messenger_device_label_length check (char_length(label) between 1 and 80),
  constraint messenger_device_credential_size check (octet_length(credential) between 1 and 8192),
  constraint messenger_device_public_key_size check (octet_length(signature_public_key) between 1 and 4096)
);
create index if not exists messenger_devices_user_active
  on messenger.devices (user_id) where revoked_at is null;

create table if not exists messenger.key_packages (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references messenger.devices(id) on delete cascade,
  package bytea not null,
  cipher_suite text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  constraint messenger_key_package_size check (octet_length(package) between 1 and 65536),
  constraint messenger_key_package_suite_length check (char_length(cipher_suite) between 1 and 96),
  constraint messenger_key_package_expiry check (expires_at > created_at)
);
create index if not exists messenger_key_packages_available
  on messenger.key_packages (expires_at, created_at)
  where consumed_at is null;

create table if not exists messenger.conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('direct', 'group')),
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists messenger.conversation_members (
  conversation_id uuid not null references messenger.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'admin', 'member')),
  added_by uuid not null references auth.users(id) on delete restrict,
  joined_at timestamptz not null default now(),
  removed_at timestamptz,
  primary key (conversation_id, user_id)
);
create index if not exists messenger_members_user_active
  on messenger.conversation_members (user_id, conversation_id)
  where removed_at is null;

create or replace function messenger.is_member(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, messenger
as $$
  select exists (
    select 1
    from messenger.conversation_members cm
    where cm.conversation_id = p_conversation_id
      and cm.user_id = auth.uid()
      and cm.removed_at is null
  );
$$;

create or replace function messenger.is_admin(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, messenger
as $$
  select exists (
    select 1
    from messenger.conversation_members cm
    where cm.conversation_id = p_conversation_id
      and cm.user_id = auth.uid()
      and cm.removed_at is null
      and cm.role in ('owner', 'admin')
  );
$$;

revoke all on function messenger.is_member(uuid) from public;
revoke all on function messenger.is_admin(uuid) from public;
grant execute on function messenger.is_member(uuid) to authenticated;
grant execute on function messenger.is_admin(uuid) to authenticated;

create table if not exists messenger.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references messenger.conversations(id) on delete cascade,
  sender_user_id uuid not null references auth.users(id) on delete restrict,
  sender_device_id uuid not null references messenger.devices(id) on delete restrict,
  protocol_version integer not null check (protocol_version > 0),
  ciphertext bytea not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  constraint messenger_message_ciphertext_size check (
    octet_length(ciphertext) between 1 and 262144
  )
);
create index if not exists messenger_messages_timeline
  on messenger.messages (conversation_id, created_at desc, id desc);

create table if not exists messenger.message_receipts (
  message_id uuid not null references messenger.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  delivered_at timestamptz,
  read_at timestamptz,
  primary key (message_id, user_id)
);

create table if not exists messenger.attachments (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references messenger.conversations(id) on delete cascade,
  object_key text not null unique,
  ciphertext_size_bytes bigint not null check (ciphertext_size_bytes > 0),
  chunk_size_bytes integer not null check (chunk_size_bytes between 16384 and 4194304),
  chunk_count integer not null check (chunk_count > 0),
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now()
);
-- Filename, MIME type, captions, thumbnails, content keys and chunk manifests belong
-- inside the encrypted MLS application message, never in this table.

create table if not exists messenger.contact_requests (
  id uuid primary key default gen_random_uuid(),
  requester_id uuid not null references auth.users(id) on delete cascade,
  target_id uuid not null references auth.users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined', 'cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (requester_id, target_id),
  check (requester_id <> target_id)
);

create table if not exists messenger.blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);

create table if not exists messenger.push_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id uuid not null references messenger.devices(id) on delete cascade,
  fcm_token text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (device_id),
  constraint messenger_fcm_token_length check (char_length(fcm_token) between 20 and 4096)
);

-- Conversation creation and membership changes are RPC-only. Clients cannot self-join
-- arbitrary conversations by inserting membership rows directly.
create or replace function messenger.create_conversation(
  p_kind text,
  p_member_ids uuid[]
)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
declare
  v_user_id uuid := auth.uid();
  v_conversation_id uuid;
  v_members uuid[];
  v_member_count integer;
begin
  if v_user_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if p_kind not in ('direct', 'group') then
    raise exception 'invalid_conversation_kind' using errcode = '22023';
  end if;
  if p_member_ids is null then
    raise exception 'members_required' using errcode = '22023';
  end if;

  select array_agg(x order by x)
    into v_members
  from (
    select distinct unnest(p_member_ids || array[v_user_id]) as x
  ) members;

  v_member_count := coalesce(cardinality(v_members), 0);
  if v_member_count < 2 or v_member_count > 256 then
    raise exception 'conversation_member_count_invalid' using errcode = '22023';
  end if;
  if p_kind = 'direct' and v_member_count <> 2 then
    raise exception 'direct_conversation_requires_two_members' using errcode = '22023';
  end if;

  if exists (
    select 1
    from unnest(v_members) as participant(user_id)
    where participant.user_id <> v_user_id
      and (
        exists (select 1 from messenger.blocks b where b.blocker_id = v_user_id and b.blocked_id = participant.user_id)
        or exists (select 1 from messenger.blocks b where b.blocker_id = participant.user_id and b.blocked_id = v_user_id)
      )
  ) then
    raise exception 'blocked_relationship' using errcode = '42501';
  end if;

  if exists (
    select 1 from unnest(v_members) as participant(user_id)
    left join auth.users u on u.id = participant.user_id
    where u.id is null
  ) then
    raise exception 'unknown_member' using errcode = '22023';
  end if;

  insert into messenger.conversations(kind, created_by)
  values (p_kind, v_user_id)
  returning id into v_conversation_id;

  insert into messenger.conversation_members(conversation_id, user_id, role, added_by)
  select v_conversation_id, participant.user_id,
         case when participant.user_id = v_user_id then 'owner' else 'member' end,
         v_user_id
  from unnest(v_members) as participant(user_id);

  return v_conversation_id;
end;
$$;

create or replace function messenger.add_conversation_member(
  p_conversation_id uuid,
  p_user_id uuid
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
declare
  v_actor uuid := auth.uid();
  v_kind text;
begin
  if v_actor is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if not messenger.is_admin(p_conversation_id) then
    raise exception 'admin_required' using errcode = '42501';
  end if;
  select kind into v_kind from messenger.conversations where id = p_conversation_id;
  if v_kind is null or v_kind <> 'group' then
    raise exception 'group_conversation_required' using errcode = '22023';
  end if;
  if p_user_id = v_actor then
    raise exception 'cannot_add_self' using errcode = '22023';
  end if;
  if exists (
    select 1 from messenger.blocks b
    where (b.blocker_id = v_actor and b.blocked_id = p_user_id)
       or (b.blocker_id = p_user_id and b.blocked_id = v_actor)
  ) then
    raise exception 'blocked_relationship' using errcode = '42501';
  end if;
  if not exists (select 1 from auth.users where id = p_user_id) then
    raise exception 'unknown_member' using errcode = '22023';
  end if;

  insert into messenger.conversation_members(conversation_id, user_id, role, added_by)
  values (p_conversation_id, p_user_id, 'member', v_actor)
  on conflict (conversation_id, user_id)
  do update set role = 'member', added_by = excluded.added_by,
                joined_at = now(), removed_at = null
  where messenger.conversation_members.removed_at is not null;
end;
$$;

create or replace function messenger.respond_contact_request(
  p_request_id uuid,
  p_status text
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  if p_status not in ('accepted', 'declined') then
    raise exception 'invalid_contact_request_status' using errcode = '22023';
  end if;
  update messenger.contact_requests
     set status = p_status, updated_at = now()
   where id = p_request_id
     and target_id = auth.uid()
     and status = 'pending';
  if not found then
    raise exception 'contact_request_not_found_or_already_resolved' using errcode = 'P0002';
  end if;
end;
$;

create or replace function messenger.leave_conversation(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $
declare
  v_user_id uuid := auth.uid();
  v_role text;
  v_active_members integer;
begin
  if v_user_id is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  select role into v_role
    from messenger.conversation_members
   where conversation_id = p_conversation_id
     and user_id = v_user_id
     and removed_at is null
   for update;
  if v_role is null then
    raise exception 'active_membership_required' using errcode = '42501';
  end if;
  select count(*) into v_active_members
    from messenger.conversation_members
   where conversation_id = p_conversation_id and removed_at is null;
  if v_role = 'owner' and v_active_members > 1 then
    raise exception 'owner_must_transfer_ownership_before_leaving' using errcode = '22023';
  end if;
  update messenger.conversation_members
     set removed_at = now()
   where conversation_id = p_conversation_id and user_id = v_user_id;
end;
$;

create or replace function messenger.consume_key_package(p_cipher_suite text)
returns table (
  package_id uuid,
  device_id uuid,
  package bytea,
  cipher_suite text
)
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  return query
  with selected as (
    select kp.id
    from messenger.key_packages kp
    join messenger.devices d on d.id = kp.device_id
    where kp.consumed_at is null
      and kp.expires_at > now()
      and kp.cipher_suite = p_cipher_suite
      and d.revoked_at is null
      and d.user_id <> auth.uid()
      and not exists (
        select 1 from messenger.blocks b
        where (b.blocker_id = auth.uid() and b.blocked_id = d.user_id)
           or (b.blocker_id = d.user_id and b.blocked_id = auth.uid())
      )
    order by kp.created_at
    for update of kp skip locked
    limit 1
  ),
  consumed as (
    update messenger.key_packages kp
       set consumed_at = now()
      from selected s
     where kp.id = s.id
    returning kp.id, kp.device_id, kp.package, kp.cipher_suite
  )
  select c.id, c.device_id, c.package, c.cipher_suite from consumed c;
end;
$$;

-- RLS helpers are SECURITY DEFINER and use a fixed search_path. All exposed tables
-- still enforce membership or owner checks at the row level.
alter table messenger.profiles enable row level security;
alter table messenger.devices enable row level security;
alter table messenger.key_packages enable row level security;
alter table messenger.conversations enable row level security;
alter table messenger.conversation_members enable row level security;
alter table messenger.messages enable row level security;
alter table messenger.message_receipts enable row level security;
alter table messenger.attachments enable row level security;
alter table messenger.contact_requests enable row level security;
alter table messenger.blocks enable row level security;
alter table messenger.push_devices enable row level security;

create policy profiles_read_authenticated on messenger.profiles
  for select to authenticated using (true);
create policy profiles_insert_self on messenger.profiles
  for insert to authenticated with check (user_id = auth.uid());
create policy profiles_update_self on messenger.profiles
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy devices_read_authenticated on messenger.devices
  for select to authenticated using (revoked_at is null);
create policy devices_insert_self on messenger.devices
  for insert to authenticated with check (user_id = auth.uid());
create policy devices_update_self on messenger.devices
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy devices_delete_self on messenger.devices
  for delete to authenticated using (user_id = auth.uid());

create policy key_packages_read_authenticated on messenger.key_packages
  for select to authenticated using (
    consumed_at is null and expires_at > now()
    and exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.revoked_at is null
    )
  );
create policy key_packages_insert_own_device on messenger.key_packages
  for insert to authenticated with check (
    exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.user_id = auth.uid() and d.revoked_at is null
    )
  );
create policy key_packages_delete_own_device on messenger.key_packages
  for delete to authenticated using (
    exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.user_id = auth.uid()
    )
  );

create policy conversations_read_members on messenger.conversations
  for select to authenticated using (messenger.is_member(id));

create policy members_read_members on messenger.conversation_members
  for select to authenticated using (messenger.is_member(conversation_id));

create policy messages_read_members on messenger.messages
  for select to authenticated using (messenger.is_member(conversation_id));
create policy messages_insert_own_member_device on messenger.messages
  for insert to authenticated with check (
    sender_user_id = auth.uid()
    and messenger.is_member(conversation_id)
    and exists (
      select 1 from messenger.devices d
      where d.id = sender_device_id
        and d.user_id = auth.uid()
        and d.revoked_at is null
    )
  );

create policy receipts_read_conversation_members on messenger.message_receipts
  for select to authenticated using (
    exists (
      select 1 from messenger.messages m
      where m.id = message_id and messenger.is_member(m.conversation_id)
    )
  );
create policy receipts_insert_self on messenger.message_receipts
  for insert to authenticated with check (
    user_id = auth.uid()
    and exists (
      select 1 from messenger.messages m
      where m.id = message_id and messenger.is_member(m.conversation_id)
    )
  );
create policy receipts_update_self on messenger.message_receipts
  for update to authenticated using (user_id = auth.uid())
  with check (
    user_id = auth.uid()
    and exists (
      select 1 from messenger.messages m
      where m.id = message_id and messenger.is_member(m.conversation_id)
    )
  );

create policy attachments_read_members on messenger.attachments
  for select to authenticated using (messenger.is_member(conversation_id));
create policy attachments_insert_own_member on messenger.attachments
  for insert to authenticated with check (
    created_by = auth.uid() and messenger.is_member(conversation_id)
  );

create policy contact_requests_read_participants on messenger.contact_requests
  for select to authenticated using (requester_id = auth.uid() or target_id = auth.uid());
create policy contact_requests_insert_self on messenger.contact_requests
  for insert to authenticated with check (
    requester_id = auth.uid()
    and not exists (
      select 1 from messenger.blocks b
      where (b.blocker_id = auth.uid() and b.blocked_id = target_id)
         or (b.blocker_id = target_id and b.blocked_id = auth.uid())
    )
  );
-- Status changes are RPC-only so a recipient cannot rewrite the requester or target.


create policy blocks_read_self on messenger.blocks
  for select to authenticated using (blocker_id = auth.uid());
create policy blocks_insert_self on messenger.blocks
  for insert to authenticated with check (blocker_id = auth.uid());
create policy blocks_delete_self on messenger.blocks
  for delete to authenticated using (blocker_id = auth.uid());

create policy push_devices_read_self on messenger.push_devices
  for select to authenticated using (user_id = auth.uid());
create policy push_devices_insert_self on messenger.push_devices
  for insert to authenticated with check (
    user_id = auth.uid()
    and exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.user_id = auth.uid() and d.revoked_at is null
    )
  );
create policy push_devices_update_self on messenger.push_devices
  for update to authenticated using (user_id = auth.uid())
  with check (
    user_id = auth.uid()
    and exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.user_id = auth.uid() and d.revoked_at is null
    )
  );
create policy push_devices_delete_self on messenger.push_devices
  for delete to authenticated using (user_id = auth.uid());

grant select, insert, update on messenger.profiles to authenticated;
grant select, insert, update, delete on messenger.devices to authenticated;
grant select, insert, delete on messenger.key_packages to authenticated;
grant select on messenger.conversations, messenger.conversation_members to authenticated;
grant select, insert on messenger.messages to authenticated;
grant select, insert, update on messenger.message_receipts to authenticated;
grant select, insert on messenger.attachments to authenticated;
grant select, insert on messenger.contact_requests to authenticated;
grant select, insert, delete on messenger.blocks to authenticated;
grant select, insert, update, delete on messenger.push_devices to authenticated;

revoke all on function messenger.create_conversation(text, uuid[]) from public;
revoke all on function messenger.add_conversation_member(uuid, uuid) from public;
revoke all on function messenger.respond_contact_request(uuid, text) from public;
revoke all on function messenger.leave_conversation(uuid) from public;
revoke all on function messenger.consume_key_package(text) from public;
grant execute on function messenger.create_conversation(text, uuid[]) to authenticated;
grant execute on function messenger.add_conversation_member(uuid, uuid) to authenticated;
grant execute on function messenger.respond_contact_request(uuid, text) to authenticated;
grant execute on function messenger.leave_conversation(uuid) to authenticated;
grant execute on function messenger.consume_key_package(text) to authenticated;

comment on schema messenger is
  'Atomos Messenger. Payloads and attachment bytes are ciphertext only; protocol and key lifecycle are client-owned.';
