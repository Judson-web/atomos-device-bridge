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
  v_other_user_id uuid;
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
  if p_kind = 'direct' and v_member_count = 2 then
    v_other_user_id := case
      when v_members[1] = v_user_id then v_members[2]
      else v_members[1]
    end;
  end if;
  if v_member_count < 2 or v_member_count > 256 then
    raise exception 'conversation_member_count_invalid' using errcode = '22023';
  end if;
  if p_kind = 'direct' and v_member_count <> 2 then
    raise exception 'direct_conversation_requires_two_members' using errcode = '22023';
  end if;
  if p_kind = 'direct' and not exists (
    select 1
    from unnest(v_members) as participant(user_id)
    join messenger.contact_requests cr
      on cr.status = 'accepted'
     and (
       (cr.requester_id = v_user_id and cr.target_id = participant.user_id)
       or (cr.target_id = v_user_id and cr.requester_id = participant.user_id)
     )
    where participant.user_id <> v_user_id
  ) then
    raise exception 'accepted_contact_required_for_direct_conversation' using errcode = '42501';
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
revoke all on function messenger.create_conversation(text, uuid[]) from public;
grant execute on function messenger.create_conversation(text, uuid[]) to authenticated;
