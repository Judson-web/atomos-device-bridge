-- Publish only the messenger tables required for authenticated inbox and contact updates.
do $$
declare
  v_table text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach v_table in array array[
      'conversations',
      'conversation_members',
      'contact_requests'
    ] loop
      if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'messenger'
          and tablename = v_table
      ) then
        execute format('alter publication supabase_realtime add table messenger.%I', v_table);
      end if;
    end loop;
  end if;
end
$$;
