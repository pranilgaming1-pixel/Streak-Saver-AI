-- Ezone direct-message read receipts.
-- Safe to apply more than once. Recipients may update only the seen_at column.
alter table public.direct_messages
  add column if not exists seen_at timestamptz;

create index if not exists direct_messages_unread_idx
  on public.direct_messages(receiver_id, sender_id, seen_at)
  where seen_at is null;

alter table public.direct_messages enable row level security;
drop policy if exists "Ezone receiver marks messages read" on public.direct_messages;
create policy "Ezone receiver marks messages read"
  on public.direct_messages for update to authenticated
  using (auth.uid() = receiver_id)
  with check (auth.uid() = receiver_id);

revoke update on public.direct_messages from authenticated;
grant update (seen_at) on public.direct_messages to authenticated;
