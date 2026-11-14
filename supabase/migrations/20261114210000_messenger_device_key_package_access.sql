-- Limit device and one-time key-package discovery to the owner and accepted contacts.
drop policy if exists devices_read_authenticated on messenger.devices;
create policy devices_read_authenticated on messenger.devices
  for select to authenticated using (
    user_id = auth.uid()
    or (
      revoked_at is null
      and exists (
        select 1 from messenger.contact_requests cr
        where cr.status = 'accepted'
          and (
            (cr.requester_id = auth.uid() and cr.target_id = user_id)
            or (cr.target_id = auth.uid() and cr.requester_id = user_id)
          )
      )
    )
  );

drop policy if exists key_packages_read_authenticated on messenger.key_packages;
create policy key_packages_read_own on messenger.key_packages
  for select to authenticated using (
    exists (
      select 1 from messenger.devices d
      where d.id = device_id and d.user_id = auth.uid()
    )
  );

create or replace function messenger.consume_key_package(p_cipher_suite text)
returns table(package_id uuid, device_id uuid, package bytea, cipher_suite text)
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
    select kp.id, kp.device_id, kp.package, kp.cipher_suite
    from messenger.key_packages kp
    join messenger.devices d on d.id = kp.device_id
    where kp.consumed_at is null
      and kp.expires_at > now()
      and kp.cipher_suite = p_cipher_suite
      and d.user_id <> auth.uid()
      and d.revoked_at is null
      and exists (
        select 1 from messenger.contact_requests cr
        where cr.status = 'accepted'
          and (
            (cr.requester_id = auth.uid() and cr.target_id = d.user_id)
            or (cr.target_id = auth.uid() and cr.requester_id = d.user_id)
          )
      )
      and not exists (
        select 1 from messenger.blocks b
        where (b.blocker_id = auth.uid() and b.blocked_id = d.user_id)
           or (b.blocker_id = d.user_id and b.blocked_id = auth.uid())
      )
    order by kp.created_at
    for update of kp skip locked
    limit 1
  )
  update messenger.key_packages kp
     set consumed_at = now()
    from selected s
   where kp.id = s.id
  returning kp.id, kp.device_id, kp.package, kp.cipher_suite;
end;
$$;

revoke all on function messenger.consume_key_package(text) from public;
grant execute on function messenger.consume_key_package(text) to authenticated;
