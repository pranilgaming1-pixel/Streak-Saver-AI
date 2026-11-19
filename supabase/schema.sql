-- Ezone social backend migration.
-- Run this entire file once in Supabase Dashboard -> SQL Editor.
-- It keeps existing auth.users accounts and creates a profile for each one.

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    username text unique not null,
    display_name text not null,
    bio text not null default '',
    avatar_url text,
    is_private boolean not null default false,
    created_at timestamptz not null default now()
);

alter table public.profiles add column if not exists bio text not null default '';
alter table public.profiles add column if not exists avatar_url text;
alter table public.profiles add column if not exists is_private boolean not null default false;

create table if not exists public.posts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    body text not null default '',
    media_path text,
    media_type text check (media_type in ('image','video') or media_type is null),
    created_at timestamptz not null default now()
);

alter table public.posts add column if not exists body text not null default '';
alter table public.posts add column if not exists media_path text;
alter table public.posts add column if not exists media_type text;
alter table public.posts add column if not exists created_at timestamptz not null default now();

-- New user profiles are created automatically on email signup and Google OAuth.
create or replace function public.ezone_handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    base_handle text;
    chosen_handle text;
    chosen_name text;
begin
    chosen_name := coalesce(
        nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
        nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
        nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
        split_part(coalesce(new.email, 'member'), '@', 1)
    );
    base_handle := lower(regexp_replace(
        coalesce(
            nullif(new.raw_user_meta_data ->> 'username', ''),
            nullif(new.raw_user_meta_data ->> 'preferred_username', ''),
            split_part(coalesce(new.email, 'member'), '@', 1),
            'member'
        ),
        '[^a-z0-9_]+', '_', 'g'
    ));
    base_handle := trim(both '_' from base_handle);
    if length(base_handle) < 3 then
        base_handle := 'member';
    end if;
    chosen_handle := left(base_handle, 24);

    if exists (select 1 from public.profiles p where p.username = chosen_handle and p.id <> new.id) then
        chosen_handle := left(base_handle, 15) || '_' || substr(replace(new.id::text, '-', ''), 1, 8);
    end if;

    insert into public.profiles(id, username, display_name)
    values (new.id, chosen_handle, chosen_name)
    on conflict (id) do update
       set display_name = excluded.display_name;

    return new;
end;
$$;

drop trigger if exists ezone_auth_user_created on auth.users;
create trigger ezone_auth_user_created
after insert on auth.users
for each row execute procedure public.ezone_handle_new_user();

-- Backfill profiles for accounts created before this migration.
insert into public.profiles(id, username, display_name)
select
    u.id,
    'member_' || substr(replace(u.id::text, '-', ''), 1, 12),
    coalesce(
        nullif(btrim(u.raw_user_meta_data ->> 'display_name'), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
        nullif(btrim(u.raw_user_meta_data ->> 'name'), ''),
        split_part(coalesce(u.email, 'member'), '@', 1)
    )
from auth.users u
where not exists (select 1 from public.profiles p where p.id = u.id)
on conflict (id) do nothing;

-- Ensure PostgREST can embed each post author's profile.
alter table public.posts drop constraint if exists posts_user_id_fkey;
alter table public.posts drop constraint if exists posts_user_id_profiles_fkey;
alter table public.posts
    add constraint posts_user_id_profiles_fkey
    foreign key (user_id) references public.profiles(id) on delete cascade;

create table if not exists public.follows (
    id uuid primary key default gen_random_uuid(),
    follower_id uuid not null references public.profiles(id) on delete cascade,
    following_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint follows_no_self_follow check (follower_id <> following_id),
    constraint follows_unique_pair unique (follower_id, following_id)
);

create table if not exists public.post_likes (
    id uuid primary key default gen_random_uuid(),
    post_id uuid not null references public.posts(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint post_likes_unique_pair unique (post_id, user_id)
);

create table if not exists public.comments (
    id uuid primary key default gen_random_uuid(),
    post_id uuid not null references public.posts(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    body text not null check (length(btrim(body)) > 0 and length(body) <= 2000),
    created_at timestamptz not null default now()
);

create table if not exists public.notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    actor_id uuid references public.profiles(id) on delete set null,
    post_id uuid references public.posts(id) on delete cascade,
    type text not null check (type in ('follow','like','comment')),
    message text not null,
    is_read boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists posts_created_at_idx on public.posts(created_at desc);
create index if not exists posts_user_created_idx on public.posts(user_id, created_at desc);
create index if not exists follows_follower_idx on public.follows(follower_id);
create index if not exists follows_following_idx on public.follows(following_id);
create index if not exists post_likes_post_idx on public.post_likes(post_id);
create index if not exists comments_post_idx on public.comments(post_id, created_at);
create index if not exists notifications_user_created_idx on public.notifications(user_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.posts enable row level security;
alter table public.follows enable row level security;
alter table public.post_likes enable row level security;
alter table public.comments enable row level security;
alter table public.notifications enable row level security;

drop policy if exists "Authenticated users can view profiles" on public.profiles;
drop policy if exists "Users create own profile" on public.profiles;
drop policy if exists "Users update own profile" on public.profiles;
create policy "Ezone profiles visible to members"
on public.profiles for select to authenticated using (true);
create policy "Ezone users create own profile"
on public.profiles for insert to authenticated with check (auth.uid() = id);
create policy "Ezone users update own profile"
on public.profiles for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

drop policy if exists "Authenticated users can view posts" on public.posts;
drop policy if exists "Users create own posts" on public.posts;
drop policy if exists "Users update own posts" on public.posts;
drop policy if exists "Users delete own posts" on public.posts;
create policy "Ezone members view public or followed posts"
on public.posts for select to authenticated
using (
    user_id = auth.uid()
    or exists (
        select 1 from public.profiles p
        where p.id = posts.user_id
          and (
              p.is_private = false
              or exists (
                  select 1 from public.follows f
                  where f.follower_id = auth.uid()
                    and f.following_id = posts.user_id
              )
          )
    )
);
create policy "Ezone users create own posts"
on public.posts for insert to authenticated with check (auth.uid() = user_id);
create policy "Ezone users update own posts"
on public.posts for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "Ezone users delete own posts"
on public.posts for delete to authenticated using (auth.uid() = user_id);

drop policy if exists "Ezone follows readable by members" on public.follows;
drop policy if exists "Ezone users follow themselves" on public.follows;
drop policy if exists "Ezone users unfollow themselves" on public.follows;
create policy "Ezone follows readable by members"
on public.follows for select to authenticated using (true);
create policy "Ezone users follow"
on public.follows for insert to authenticated with check (auth.uid() = follower_id and follower_id <> following_id);
create policy "Ezone users unfollow"
on public.follows for delete to authenticated using (auth.uid() = follower_id);

drop policy if exists "Ezone likes readable by members" on public.post_likes;
drop policy if exists "Ezone users like posts" on public.post_likes;
drop policy if exists "Ezone users remove own likes" on public.post_likes;
create policy "Ezone likes readable by members"
on public.post_likes for select to authenticated using (true);
create policy "Ezone users like posts"
on public.post_likes for insert to authenticated with check (auth.uid() = user_id);
create policy "Ezone users remove own likes"
on public.post_likes for delete to authenticated using (auth.uid() = user_id);

drop policy if exists "Ezone comments readable by members" on public.comments;
drop policy if exists "Ezone users comment as themselves" on public.comments;
drop policy if exists "Ezone users delete own comments" on public.comments;
create policy "Ezone comments readable by members"
on public.comments for select to authenticated using (true);
create policy "Ezone users comment as themselves"
on public.comments for insert to authenticated with check (auth.uid() = user_id);
create policy "Ezone users delete own comments"
on public.comments for delete to authenticated using (auth.uid() = user_id);

drop policy if exists "Ezone users view own notifications" on public.notifications;
drop policy if exists "Ezone users update own notifications" on public.notifications;
create policy "Ezone users view own notifications"
on public.notifications for select to authenticated using (auth.uid() = user_id);
create policy "Ezone users update own notifications"
on public.notifications for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Generate activity entries for likes, comments, and new followers.
create or replace function public.ezone_notify_follow()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
    insert into public.notifications(user_id, actor_id, type, message)
    values (new.following_id, new.follower_id, 'follow', 'Someone started following you');
    return new;
end;
$$;

create or replace function public.ezone_notify_like()
returns trigger language plpgsql security definer set search_path = public
as $$
declare
    owner_id uuid;
begin
    select p.user_id into owner_id from public.posts p where p.id = new.post_id;
    if owner_id is not null and owner_id <> new.user_id then
        insert into public.notifications(user_id, actor_id, post_id, type, message)
        values (owner_id, new.user_id, new.post_id, 'like', 'Someone liked your post');
    end if;
    return new;
end;
$$;

create or replace function public.ezone_notify_comment()
returns trigger language plpgsql security definer set search_path = public
as $$
declare
    owner_id uuid;
begin
    select p.user_id into owner_id from public.posts p where p.id = new.post_id;
    if owner_id is not null and owner_id <> new.user_id then
        insert into public.notifications(user_id, actor_id, post_id, type, message)
        values (owner_id, new.user_id, new.post_id, 'comment', 'Someone commented on your post');
    end if;
    return new;
end;
$$;

drop trigger if exists ezone_follow_notification on public.follows;
create trigger ezone_follow_notification after insert on public.follows
for each row execute procedure public.ezone_notify_follow();

drop trigger if exists ezone_like_notification on public.post_likes;
create trigger ezone_like_notification after insert on public.post_likes
for each row execute procedure public.ezone_notify_like();

drop trigger if exists ezone_comment_notification on public.comments;
create trigger ezone_comment_notification after insert on public.comments
for each row execute procedure public.ezone_notify_comment();

-- Public read media; authenticated uploads are limited to the current user's folder.
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values (
    'ezone-media',
    'ezone-media',
    true,
    104857600,
    array['image/jpeg','image/png','image/webp','image/heic','video/mp4','video/quicktime','video/webm']
)
on conflict (id) do update set
    public = true,
    file_size_limit = 104857600,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Ezone media is publicly readable" on storage.objects;
drop policy if exists "Ezone members upload their own media" on storage.objects;
drop policy if exists "Ezone members update their own media" on storage.objects;
drop policy if exists "Ezone members delete their own media" on storage.objects;

create policy "Ezone media is publicly readable"
on storage.objects for select to public
using (bucket_id = 'ezone-media');

create policy "Ezone members upload their own media"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'ezone-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "Ezone members update their own media"
on storage.objects for update to authenticated
using (
    bucket_id = 'ezone-media'
    and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
    bucket_id = 'ezone-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "Ezone members delete their own media"
on storage.objects for delete to authenticated
using (
    bucket_id = 'ezone-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

-- Security note: do not put the Supabase service_role key in this Android app.


-- Private direct messaging: only sender and receiver can read a message.
create table if not exists public.direct_messages (
    id uuid primary key default gen_random_uuid(),
    sender_id uuid not null references public.profiles(id) on delete cascade,
    receiver_id uuid not null references public.profiles(id) on delete cascade,
    body text not null check (length(btrim(body)) > 0 and length(body) <= 2000),
    created_at timestamptz not null default now(),
    constraint direct_messages_no_self check (sender_id <> receiver_id)
);
create index if not exists direct_messages_sender_created_idx on public.direct_messages(sender_id, created_at desc);
create index if not exists direct_messages_receiver_created_idx on public.direct_messages(receiver_id, created_at desc);
alter table public.direct_messages enable row level security;
drop policy if exists "Ezone participants read their messages" on public.direct_messages;
drop policy if exists "Ezone sender sends direct message" on public.direct_messages;
create policy "Ezone participants read their messages"
on public.direct_messages for select to authenticated
using (auth.uid() = sender_id or auth.uid() = receiver_id);
create policy "Ezone sender sends direct message"
on public.direct_messages for insert to authenticated
with check (auth.uid() = sender_id and sender_id <> receiver_id);

alter table public.notifications drop constraint if exists notifications_type_check;
alter table public.notifications add constraint notifications_type_check
check (type in ('follow','like','comment','message'));

create or replace function public.ezone_notify_message()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
    insert into public.notifications(user_id, actor_id, type, message)
    values (new.receiver_id, new.sender_id, 'message', 'You received a new message');
    return new;
end;
$$;
drop trigger if exists ezone_message_notification on public.direct_messages;
create trigger ezone_message_notification after insert on public.direct_messages
for each row execute procedure public.ezone_notify_message();
