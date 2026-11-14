create or replace function messenger.touch_conversation_activity()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
begin
  update messenger.conversations
     set updated_at = now()
   where id = new.conversation_id;
  return new;
end;
$$;

revoke all on function messenger.touch_conversation_activity() from public;

drop trigger if exists messenger_touch_conversation_after_message on messenger.messages;
create trigger messenger_touch_conversation_after_message
after insert on messenger.messages
for each row execute function messenger.touch_conversation_activity();

create or replace view messenger.conversation_summaries
with (security_invoker = true)
as
select
  c.id,
  c.kind,
  c.created_at,
  c.updated_at,
  coalesce((
    select string_agg(p.display_name, ', ' order by p.display_name)
    from messenger.conversation_members cm
    join messenger.profiles p on p.user_id = cm.user_id
    where cm.conversation_id = c.id
      and cm.user_id <> auth.uid()
      and cm.removed_at is null
  ), '') as participant_names,
  (
    select count(*)::integer
    from messenger.messages m
    left join messenger.message_receipts r
      on r.message_id = m.id
     and r.user_id = auth.uid()
    where m.conversation_id = c.id
      and m.sender_user_id <> auth.uid()
      and r.read_at is null
  ) as unread_count
from messenger.conversations c
where messenger.is_member(c.id);

grant select on messenger.conversation_summaries to authenticated;
