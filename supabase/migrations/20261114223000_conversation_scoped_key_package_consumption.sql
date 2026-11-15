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

  if not found then
    raise exception 'no_key_package_available' using errcode = 'P0002';
  end if;
end;
$$;

revoke all on function messenger.consume_conversation_key_packages(uuid, uuid, uuid, text) from public;
grant execute on function messenger.consume_conversation_key_packages(uuid, uuid, uuid, text) to authenticated;
