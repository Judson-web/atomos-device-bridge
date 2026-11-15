begin;

-- Keep revoked devices hidden from other users, but let the owning account
-- inspect its own revoked row so the Android client can fail closed.
drop policy if exists devices_read_authenticated on messenger.devices;

create policy devices_read_authenticated on messenger.devices
  for select to authenticated
  using (revoked_at is null or user_id = auth.uid());

commit;
