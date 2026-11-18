-- Ezone starter schema. Run in Supabase SQL Editor.
create table if not exists public.profiles (
 id uuid primary key references auth.users(id) on delete cascade,
 username text unique not null,
 display_name text not null,
 bio text not null default '',
 avatar_url text,
 is_private boolean not null default false,
 created_at timestamptz not null default now()
);
create table if not exists public.posts (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 body text not null default '',
 media_path text,
 media_type text check (media_type in ('image','video') or media_type is null),
 created_at timestamptz not null default now()
);
alter table public.profiles enable row level security;
alter table public.posts enable row level security;
create policy "Authenticated users can view profiles" on public.profiles for select to authenticated using (true);
create policy "Users create own profile" on public.profiles for insert to authenticated with check (auth.uid() = id);
create policy "Users update own profile" on public.profiles for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);
create policy "Authenticated users can view posts" on public.posts for select to authenticated using (true);
create policy "Users create own posts" on public.posts for insert to authenticated with check (auth.uid() = user_id);
create policy "Users update own posts" on public.posts for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "Users delete own posts" on public.posts for delete to authenticated using (auth.uid() = user_id);
-- This starter policy does not yet enforce private-account follower approvals.
