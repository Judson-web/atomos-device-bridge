drop function if exists messenger.consume_key_package(text);

create or replace function messenger.consume_conversation_key_packages(
  p_conversation_id uuid,
  p_target_user_id uuid,
  p_exclude_device_id uuid,
  p_cipher_suite text
)
returns table(
  package_id uuid,
  user_id uuid,
  device_id uuid,
  package bytea,
  cipher_suite text
)
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
declare
  v_kind text;
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;

  select c.kind into v_kind
  from messenger.conversations c
  where c.id = p_conversation_id
    and messenger.is_member(c.id);

  if v_kind is null then
    raise exception 'conversation_membership_required' using errcode = '42501';
  end if;

  if p_target_user_id <> auth.uid() then
    if v_kind = 'direct' then
      if not exists (
        select 1 from messenger.conversation_members cm
        where cm.conversation_id = p_conversation_id
          and cm.user_id = p_target_user_id
          and cm.removed_at is null
      ) then
        raise exception 'direct_recipient_not_in_conversation' using errcode = '42501';
      end if;
      if not exists (
        select 1 from messenger.contact_requests cr
        where cr.status = 'accepted'
          and (
            (cr.requester_id = auth.uid() and cr.target_id = p_target_user_id)
            or (cr.target_id = auth.uid() and cr.requester_id = p_target_user_id)
          )
      ) then
        raise exception 'accepted_contact_required' using errcode = '42501';
      end if;
    elsif v_kind = 'group' then
      if not messenger.is_admin(p_conversation_id) then
        raise exception 'conversation_admin_required' using errcode = '42501';
      end if;
      if exists (
        select 1 from messenger.conversation_members cm
        where cm.conversation_id = p_conversation_id
          and cm.user_id = p_target_user_id
          and cm.removed_at is null
      ) then
        raise exception 'target_already_in_conversation' using errcode = '22023';
      end if;
      if exists (
        select 1
        from messenger.conversation_members cm
        join messenger.blocks b
          on (b.blocker_id = cm.user_id and b.blocked_id = p_target_user_id)
          or (b.blocker_id = p_target_user_id and b.blocked_id = cm.user_id)
        where cm.conversation_id = p_conversation_id
          and cm.removed_at is null
      ) then
        raise exception 'blocked_relationship' using errcode = '42501';
      end if;
    else
      raise exception 'unsupported_conversation_kind' using errcode = '22023';
    end if;
  end if;

  return query
  with selected as (
    select kp.id
    from messenger.key_packages kp
    join messenger.devices d on d.id = kp.device_id
    where d.user_id = p_target_user_id
      and d.revoked_at is null
      and kp.consumed_at is null
      and kp.expires_at > now()
      and kp.cipher_suite = p_cipher_suite
      and (p_exclude_device_id is null or kp.device_id <> p_exclude_device_id)
    order by kp.created_at
    for update of kp skip locked
  ),
  updated as (
    update messenger.key_packages kp
       set consumed_at = now()
      from selected s
     where kp.id = s.id
    returning kp.id, kp.device_id, kp.package, kp.cipher_suite
  )
  select u.id, d.user_id, u.device_id, u.package, u.cipher_suite
  from updated u
  join messenger.devices d on d.id = u.device_id;

  if not found and p_target_user_id <> auth.uid() then
    raise exception 'no_key_package_available' using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function messenger.consume_conversation_key_packages(uuid, uuid, uuid, text) from public;
grant execute on function messenger.consume_conversation_key_packages(uuid, uuid, uuid, text) to authenticated;


create table if not exists messenger.conversation_bootstrap (
  conversation_id uuid primary key references messenger.conversations(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'ready')),
  claimed_by_device_id uuid references messenger.devices(id) on delete set null,
  lease_until timestamptz,
  welcome_message_id uuid references messenger.messages(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table messenger.conversation_bootstrap enable row level security;

drop policy if exists conversation_bootstrap_read_members on messenger.conversation_bootstrap;
create policy conversation_bootstrap_read_members on messenger.conversation_bootstrap
  for select to authenticated
  using (messenger.is_member(conversation_id));

grant select on messenger.conversation_bootstrap to authenticated;

create or replace function messenger.claim_conversation_bootstrap(
  p_conversation_id uuid,
  p_device_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
declare
  v_kind text;
  v_status text;
  v_claimed_device uuid;
  v_lease_until timestamptz;
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;

  select c.kind into v_kind
  from messenger.conversations c
  where c.id = p_conversation_id
    and messenger.is_member(c.id);
  if v_kind is null then
    raise exception 'conversation_membership_required' using errcode = '42501';
  end if;
  if v_kind <> 'direct' then
    raise exception 'direct_conversation_required' using errcode = '22023';
  end if;
  if not exists (
    select 1 from messenger.devices d
    where d.id = p_device_id
      and d.user_id = auth.uid()
      and d.revoked_at is null
  ) then
    raise exception 'active_device_required' using errcode = '42501';
  end if;

  insert into messenger.conversation_bootstrap(conversation_id)
  values (p_conversation_id)
  on conflict (conversation_id) do nothing;

  select b.status, b.claimed_by_device_id, b.lease_until
    into v_status, v_claimed_device, v_lease_until
  from messenger.conversation_bootstrap b
  where b.conversation_id = p_conversation_id
  for update;

  if v_status = 'ready' then
    return false;
  end if;

  if v_lease_until > now() and v_claimed_device <> p_device_id then
    return false;
  end if;

  -- Recover a bootstrap whose owner crashed after publishing the Welcome but
  -- before acknowledging it. While pending, the only allowed message is Welcome.
  if v_claimed_device is not null
     and v_lease_until <= now()
     and exists (
       select 1 from messenger.messages m
       where m.conversation_id = p_conversation_id
         and m.sender_device_id = v_claimed_device
     ) then
    update messenger.conversation_bootstrap
       set status = 'ready',
           lease_until = null,
           updated_at = now()
     where conversation_id = p_conversation_id;
    return false;
  end if;

  update messenger.conversation_bootstrap
     set status = 'pending',
         claimed_by_device_id = p_device_id,
         lease_until = now() + interval '2 minutes',
         updated_at = now()
   where conversation_id = p_conversation_id;
  return true;
end;
$$;

create or replace function messenger.complete_conversation_bootstrap(
  p_conversation_id uuid,
  p_device_id uuid,
  p_welcome_message_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;

  update messenger.conversation_bootstrap b
     set status = 'ready',
         welcome_message_id = p_welcome_message_id,
         lease_until = null,
         updated_at = now()
   where b.conversation_id = p_conversation_id
     and b.status = 'pending'
     and b.claimed_by_device_id = p_device_id
     and exists (
       select 1 from messenger.devices d
       where d.id = p_device_id
         and d.user_id = auth.uid()
         and d.revoked_at is null
     )
     and exists (
       select 1 from messenger.messages m
       where m.id = p_welcome_message_id
         and m.conversation_id = p_conversation_id
         and m.sender_user_id = auth.uid()
         and m.sender_device_id = p_device_id
     );

  if not found then
    raise exception 'bootstrap_completion_not_authorized' using errcode = '42501';
  end if;
  return true;
end;
$$;

create or replace function messenger.release_conversation_bootstrap(
  p_conversation_id uuid,
  p_device_id uuid
)
returns void
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
begin
  if auth.uid() is null then
    raise exception 'authentication_required' using errcode = '28000';
  end if;
  update messenger.conversation_bootstrap
     set lease_until = now(),
         updated_at = now()
   where conversation_id = p_conversation_id
     and status = 'pending'
     and claimed_by_device_id = p_device_id
     and exists (
       select 1 from messenger.devices d
       where d.id = p_device_id and d.user_id = auth.uid()
     );
end;
$$;

revoke all on function messenger.claim_conversation_bootstrap(uuid, uuid) from public;
revoke all on function messenger.complete_conversation_bootstrap(uuid, uuid, uuid) from public;
revoke all on function messenger.release_conversation_bootstrap(uuid, uuid) from public;
grant execute on function messenger.claim_conversation_bootstrap(uuid, uuid) to authenticated;
grant execute on function messenger.complete_conversation_bootstrap(uuid, uuid, uuid) to authenticated;
grant execute on function messenger.release_conversation_bootstrap(uuid, uuid) to authenticated;

do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (
       select 1 from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'messenger'
         and tablename = 'conversation_bootstrap'
     ) then
    alter publication supabase_realtime add table messenger.conversation_bootstrap;
  end if;
end
$$;
