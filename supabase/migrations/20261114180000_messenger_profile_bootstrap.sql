-- Create a messenger profile when Supabase Auth creates an account.
-- Profile names are display metadata; no Discord tokens or private account secrets are stored.
create or replace function messenger.bootstrap_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, messenger
as $$
declare
  v_display_name text;
  v_base_username text;
  v_candidate text;
  v_suffix integer := 0;
begin
  v_display_name := left(
    coalesce(
      nullif(new.raw_user_meta_data ->> 'global_name', ''),
      nullif(new.raw_user_meta_data ->> 'username', ''),
      'Atomos user'
    ),
    80
  );

  v_base_username := lower(
    regexp_replace(
      coalesce(
        new.raw_user_meta_data ->> 'username',
        new.raw_user_meta_data ->> 'global_name',
        'user'
      ),
      '[^a-zA-Z0-9_]',
      '',
      'g'
    )
  );

  if char_length(v_base_username) < 3 then
    v_base_username := 'user_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;

  v_candidate := left(v_base_username, 32);
  loop
    exit when not exists (
      select 1 from messenger.profiles p
      where p.username = v_candidate and p.user_id <> new.id
    );
    v_suffix := v_suffix + 1;
    v_candidate := left(v_base_username, 31 - char_length(v_suffix::text))
      || '_' || v_suffix::text;
  end loop;

  insert into messenger.profiles(user_id, username, display_name)
  values (new.id, v_candidate, v_display_name)
  on conflict (user_id) do update
    set display_name = excluded.display_name,
        updated_at = now();

  return new;
end;
$$;

revoke all on function messenger.bootstrap_profile_for_auth_user() from public;

drop trigger if exists messenger_profile_on_auth_user_created on auth.users;
create trigger messenger_profile_on_auth_user_created
after insert on auth.users
for each row execute function messenger.bootstrap_profile_for_auth_user();

-- Backfill existing authenticated accounts in the dedicated Atomos project.
do $$
declare
  account record;
begin
  for account in
    select id, raw_user_meta_data
    from auth.users
    where id not in (select user_id from messenger.profiles)
  loop
    insert into messenger.profiles(user_id, username, display_name)
    values (
      account.id,
      'user_' || substr(replace(account.id::text, '-', ''), 1, 12),
      left(coalesce(
        nullif(account.raw_user_meta_data ->> 'global_name', ''),
        nullif(account.raw_user_meta_data ->> 'username', ''),
        'Atomos user'
      ), 80)
    )
    on conflict (user_id) do nothing;
  end loop;
end
$$;
