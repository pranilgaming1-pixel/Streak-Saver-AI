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
alter table public.profiles add column if not exists website text not null default '';
alter table public.profiles add column if not exists location text not null default '';
alter table public.profiles add column if not exists pronouns text not null default '';
alter table public.profiles add column if not exists accent_color text not null default '#ED233C';

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
-- Upgrade existing installations too: these fields are used by the current Android composer/feed.
alter table public.posts add column if not exists music_path text;
alter table public.posts add column if not exists music_title text;
alter table public.posts add column if not exists music_artist text;

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
    array['image/jpeg','image/png','image/webp','image/heic','video/mp4','video/quicktime','video/webm','audio/mpeg','audio/mp4','audio/aac','audio/wav','audio/ogg','audio/webm','audio/x-m4a']
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
    seen_at timestamptz,
    constraint direct_messages_no_self check (sender_id <> receiver_id)
);
-- CREATE TABLE IF NOT EXISTS does not add columns to an existing inbox table.
alter table public.direct_messages add column if not exists seen_at timestamptz;
alter table public.direct_messages add column if not exists created_at timestamptz not null default now();
create index if not exists direct_messages_sender_created_idx on public.direct_messages(sender_id, created_at desc);
create index if not exists direct_messages_receiver_created_idx on public.direct_messages(receiver_id, created_at desc);
alter table public.direct_messages enable row level security;
drop policy if exists "Ezone participants read their messages" on public.direct_messages;
drop policy if exists "Ezone sender sends direct message" on public.direct_messages;
drop policy if exists "Ezone receiver marks messages seen" on public.direct_messages;
create policy "Ezone participants read their messages"
on public.direct_messages for select to authenticated
using (auth.uid() = sender_id or auth.uid() = receiver_id);
create policy "Ezone sender sends direct message"
on public.direct_messages for insert to authenticated
with check (auth.uid() = sender_id and sender_id <> receiver_id);
create policy "Ezone receiver marks messages seen"
on public.direct_messages for update to authenticated
using (auth.uid() = receiver_id)
with check (auth.uid() = receiver_id);

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


-- Ezone social feature pack: Stories, Highlights, saved posts, reposts,
-- Notes, hashtags, polls, blocking and moderation reports.
create table if not exists public.stories (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    media_path text not null,
    media_type text not null check (media_type in ('image','video')),
    caption text not null default '',
    audience text not null default 'followers' check (audience in ('followers','close_friends','public')),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '24 hours')
);
-- Upgrade existing Stories tables instead of assuming they were freshly created.
alter table public.stories add column if not exists caption text not null default '';
alter table public.stories add column if not exists audience text not null default 'followers';
alter table public.stories add column if not exists created_at timestamptz not null default now();
alter table public.stories add column if not exists expires_at timestamptz not null default (now() + interval '24 hours');
create index if not exists stories_expiry_idx on public.stories(expires_at desc);
create index if not exists stories_user_created_idx on public.stories(user_id, created_at desc);
alter table public.stories enable row level security;
drop policy if exists "Ezone members read active stories" on public.stories;
drop policy if exists "Ezone members publish own stories" on public.stories;
drop policy if exists "Ezone members delete own stories" on public.stories;
create policy "Ezone members read active stories" on public.stories
for select to authenticated using (
    expires_at > now() and (
        audience = 'public' or user_id = auth.uid() or
        (audience = 'followers' and exists (
            select 1 from public.follows f where f.follower_id = auth.uid() and f.following_id = stories.user_id
        ))
    )
);
create policy "Ezone members publish own stories" on public.stories
for insert to authenticated with check (auth.uid() = user_id);
create policy "Ezone members delete own stories" on public.stories
for delete to authenticated using (auth.uid() = user_id);

create table if not exists public.story_views (
    story_id uuid not null references public.stories(id) on delete cascade,
    viewer_id uuid not null references public.profiles(id) on delete cascade,
    viewed_at timestamptz not null default now(),
    primary key(story_id, viewer_id)
);
alter table public.story_views enable row level security;
drop policy if exists "Ezone viewers record story views" on public.story_views;
drop policy if exists "Ezone owners see story views" on public.story_views;
create policy "Ezone viewers record story views" on public.story_views
for insert to authenticated with check (auth.uid() = viewer_id);
create policy "Ezone owners see story views" on public.story_views
for select to authenticated using (
    viewer_id = auth.uid() or exists (select 1 from public.stories s where s.id = story_id and s.user_id = auth.uid())
);

create table if not exists public.story_highlights (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    title text not null check (length(btrim(title)) between 1 and 40),
    cover_url text,
    created_at timestamptz not null default now()
);
create table if not exists public.story_highlight_items (
    highlight_id uuid not null references public.story_highlights(id) on delete cascade,
    story_id uuid not null references public.stories(id) on delete cascade,
    position integer not null default 0,
    primary key(highlight_id, story_id)
);
alter table public.story_highlights enable row level security;
alter table public.story_highlight_items enable row level security;
drop policy if exists "Ezone highlights are visible" on public.story_highlights;
drop policy if exists "Ezone users manage own highlights" on public.story_highlights;
drop policy if exists "Ezone highlight items are visible" on public.story_highlight_items;
create policy "Ezone highlights are visible" on public.story_highlights for select to authenticated using (true);
create policy "Ezone users manage own highlights" on public.story_highlights for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "Ezone highlight items are visible" on public.story_highlight_items for select to authenticated using (true);

create table if not exists public.saved_posts (
    user_id uuid not null references public.profiles(id) on delete cascade,
    post_id uuid not null references public.posts(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key(user_id, post_id)
);
alter table public.saved_posts enable row level security;
drop policy if exists "Ezone users manage their saved posts" on public.saved_posts;
create policy "Ezone users manage their saved posts" on public.saved_posts
for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.post_reposts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    post_id uuid not null references public.posts(id) on delete cascade,
    created_at timestamptz not null default now(),
    unique(user_id, post_id)
);
alter table public.post_reposts enable row level security;
drop policy if exists "Ezone reposts visible to members" on public.post_reposts;
drop policy if exists "Ezone users manage own reposts" on public.post_reposts;
create policy "Ezone reposts visible to members" on public.post_reposts for select to authenticated using (true);
create policy "Ezone users manage own reposts" on public.post_reposts
for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.user_notes (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    body text not null check (length(btrim(body)) between 1 and 80),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '24 hours')
);
create index if not exists user_notes_expiry_idx on public.user_notes(expires_at desc);
alter table public.user_notes enable row level security;
drop policy if exists "Ezone members read active notes" on public.user_notes;
drop policy if exists "Ezone users manage own notes" on public.user_notes;
create policy "Ezone members read active notes" on public.user_notes for select to authenticated using (expires_at > now());
create policy "Ezone users manage own notes" on public.user_notes
for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

create table if not exists public.hashtags (
    id uuid primary key default gen_random_uuid(),
    tag text not null unique check (tag = lower(tag) and length(tag) between 1 and 40)
);
create table if not exists public.post_hashtags (
    post_id uuid not null references public.posts(id) on delete cascade,
    hashtag_id uuid not null references public.hashtags(id) on delete cascade,
    primary key(post_id, hashtag_id)
);
alter table public.hashtags enable row level security;
alter table public.post_hashtags enable row level security;
drop policy if exists "Ezone hashtags readable" on public.hashtags;
drop policy if exists "Ezone post hashtags readable" on public.post_hashtags;
create policy "Ezone hashtags readable" on public.hashtags for select to authenticated using (true);
create policy "Ezone post hashtags readable" on public.post_hashtags for select to authenticated using (true);

create table if not exists public.post_polls (
    id uuid primary key default gen_random_uuid(),
    post_id uuid not null unique references public.posts(id) on delete cascade,
    question text not null check (length(btrim(question)) between 1 and 180),
    expires_at timestamptz
);
create table if not exists public.poll_options (
    id uuid primary key default gen_random_uuid(),
    poll_id uuid not null references public.post_polls(id) on delete cascade,
    label text not null check (length(btrim(label)) between 1 and 80)
);
create table if not exists public.poll_votes (
    poll_id uuid not null references public.post_polls(id) on delete cascade,
    option_id uuid not null references public.poll_options(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key(poll_id, user_id)
);
alter table public.post_polls enable row level security;
alter table public.poll_options enable row level security;
alter table public.poll_votes enable row level security;
drop policy if exists "Ezone polls readable" on public.post_polls;
drop policy if exists "Ezone poll options readable" on public.poll_options;
drop policy if exists "Ezone members read poll votes" on public.poll_votes;
drop policy if exists "Ezone members vote once" on public.poll_votes;
create policy "Ezone polls readable" on public.post_polls for select to authenticated using (true);
create policy "Ezone poll options readable" on public.poll_options for select to authenticated using (true);
create policy "Ezone members read poll votes" on public.poll_votes for select to authenticated using (true);
create policy "Ezone members vote once" on public.poll_votes for insert to authenticated with check (auth.uid() = user_id);

create table if not exists public.blocked_users (
    blocker_id uuid not null references public.profiles(id) on delete cascade,
    blocked_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key(blocker_id, blocked_id),
    check(blocker_id <> blocked_id)
);
alter table public.blocked_users enable row level security;
drop policy if exists "Ezone users manage their blocks" on public.blocked_users;
create policy "Ezone users manage their blocks" on public.blocked_users
for all to authenticated using (auth.uid() = blocker_id) with check (auth.uid() = blocker_id);

create table if not exists public.content_reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.profiles(id) on delete cascade,
    post_id uuid references public.posts(id) on delete cascade,
    reported_user_id uuid references public.profiles(id) on delete cascade,
    reason text not null check (reason in ('spam','harassment','nudity','violence','scam','other')),
    details text not null default '',
    status text not null default 'open' check (status in ('open','reviewing','resolved','dismissed')),
    created_at timestamptz not null default now(),
    check(post_id is not null or reported_user_id is not null)
);
alter table public.content_reports enable row level security;
drop policy if exists "Ezone members submit reports" on public.content_reports;
drop policy if exists "Ezone reporters view own reports" on public.content_reports;
create policy "Ezone members submit reports" on public.content_reports
for insert to authenticated with check (auth.uid() = reporter_id);
create policy "Ezone reporters view own reports" on public.content_reports
for select to authenticated using (auth.uid() = reporter_id);

-- Extend notifications to cover the newer social interactions.
alter table public.notifications drop constraint if exists notifications_type_check;
alter table public.notifications add constraint notifications_type_check
check (type in ('follow','like','comment','message','repost','story_reply','mention'));


-- Close Friends controls for private story audiences.
create table if not exists public.close_friends (
    owner_id uuid not null references public.profiles(id) on delete cascade,
    friend_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key(owner_id, friend_id),
    check(owner_id <> friend_id)
);
alter table public.close_friends enable row level security;
drop policy if exists "Ezone users manage their close friends" on public.close_friends;
drop policy if exists "Ezone users are visible in close friends lists" on public.close_friends;
create policy "Ezone users manage their close friends" on public.close_friends
for all to authenticated using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
create policy "Ezone users are visible in close friends lists" on public.close_friends
for select to authenticated using (auth.uid() = friend_id);
drop policy if exists "Ezone members read active stories" on public.stories;
create policy "Ezone members read active stories" on public.stories
for select to authenticated using (
    expires_at > now() and (
        audience = 'public' or user_id = auth.uid() or
        (audience = 'followers' and exists (
            select 1 from public.follows f where f.follower_id = auth.uid() and f.following_id = stories.user_id
        )) or
        (audience = 'close_friends' and exists (
            select 1 from public.close_friends cf where cf.owner_id = stories.user_id and cf.friend_id = auth.uid()
        ))
    )
);
drop policy if exists "Ezone users manage highlight items" on public.story_highlight_items;
create policy "Ezone users manage highlight items" on public.story_highlight_items
for all to authenticated using (
    exists (select 1 from public.story_highlights h where h.id = highlight_id and h.user_id = auth.uid())
) with check (
    exists (select 1 from public.story_highlights h where h.id = highlight_id and h.user_id = auth.uid())
);

-- Ezone post music attachments. Users may upload audio they have rights to share.
alter table public.posts add column if not exists music_path text;
alter table public.posts add column if not exists music_title text;
alter table public.posts add column if not exists music_artist text;


-- Explicit API grants. Row Level Security policies above remain the access controls.
grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;
alter default privileges in schema public
  grant select, insert, update, delete on tables to authenticated;
alter default privileges in schema public
  grant usage, select on sequences to authenticated;

-- Refresh PostgREST's schema cache so the Android app can see newly-created tables.
notify pgrst, 'reload schema';


-- Ezone private-account follow requests and notification preferences.
create table if not exists public.follow_requests (
  id uuid primary key default gen_random_uuid(),
  follower_id uuid not null references public.profiles(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','declined')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (follower_id, owner_id),
  check (follower_id <> owner_id)
);
create index if not exists follow_requests_owner_pending_idx on public.follow_requests(owner_id, created_at desc) where status = 'pending';
alter table public.follow_requests drop constraint if exists follow_requests_follower_id_owner_id_key;
create unique index if not exists follow_requests_pending_unique_idx on public.follow_requests(follower_id, owner_id) where status = 'pending';
alter table public.follow_requests enable row level security;
drop policy if exists "Ezone participants read follow requests" on public.follow_requests;
drop policy if exists "Ezone users create follow requests" on public.follow_requests;
drop policy if exists "Ezone owners respond to follow requests" on public.follow_requests;
drop policy if exists "Ezone participants cancel follow requests" on public.follow_requests;
create policy "Ezone participants read follow requests" on public.follow_requests for select to authenticated using (auth.uid() = follower_id or auth.uid() = owner_id);
create policy "Ezone users create follow requests" on public.follow_requests for insert to authenticated with check (auth.uid() = follower_id and exists (select 1 from public.profiles p where p.id = owner_id and p.is_private = true));
create policy "Ezone owners respond to follow requests" on public.follow_requests for update to authenticated using (auth.uid() = owner_id and status = 'pending') with check (auth.uid() = owner_id and status in ('accepted','declined'));
create policy "Ezone participants cancel follow requests" on public.follow_requests for delete to authenticated using (auth.uid() = follower_id or auth.uid() = owner_id);
create or replace function public.ezone_accept_follow_request() returns trigger language plpgsql security definer set search_path = public as $$ begin if old.status = 'pending' and new.status = 'accepted' then insert into public.follows(follower_id, following_id) values (new.follower_id, new.owner_id) on conflict (follower_id, following_id) do nothing; end if; new.updated_at = now(); return new; end; $$;
revoke all on function public.ezone_accept_follow_request() from public, anon, authenticated;
drop trigger if exists ezone_accept_follow_request_trigger on public.follow_requests;
create trigger ezone_accept_follow_request_trigger before update of status on public.follow_requests for each row execute function public.ezone_accept_follow_request();
drop policy if exists "Ezone users follow" on public.follows;
create policy "Ezone users follow public profiles" on public.follows for insert to authenticated with check (auth.uid() = follower_id and follower_id <> following_id and exists (select 1 from public.profiles p where p.id = following_id and p.is_private = false));
create table if not exists public.notification_preferences (user_id uuid primary key references public.profiles(id) on delete cascade, likes boolean not null default true, comments boolean not null default true, follows boolean not null default true, messages boolean not null default true, product_updates boolean not null default false, updated_at timestamptz not null default now());
alter table public.notification_preferences enable row level security;
drop policy if exists "Ezone users manage notification preferences" on public.notification_preferences;
create policy "Ezone users manage notification preferences" on public.notification_preferences for all to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
revoke all on function public.ezone_handle_new_user() from public, anon, authenticated;
revoke all on function public.ezone_notify_comment() from public, anon, authenticated;
revoke all on function public.ezone_notify_follow() from public, anon, authenticated;
revoke all on function public.ezone_notify_like() from public, anon, authenticated;
revoke all on function public.ezone_notify_message() from public, anon, authenticated;
revoke all on function public.rls_auto_enable() from public, anon, authenticated;
grant select, insert, update, delete on public.follow_requests, public.notification_preferences to authenticated;
notify pgrst, 'reload schema';


-- Respect per-account in-app notification preferences.
create or replace function public.ezone_notify_comment()
returns trigger language plpgsql security definer set search_path = public
as $$
declare owner_id uuid;
begin
  select p.user_id into owner_id from public.posts p where p.id = new.post_id;
  if owner_id is not null and owner_id <> new.user_id
     and coalesce((select np.comments from public.notification_preferences np where np.user_id = owner_id), true) then
    insert into public.notifications(user_id, actor_id, post_id, type, message)
    values (owner_id, new.user_id, new.post_id, 'comment', 'Someone commented on your post');
  end if;
  return new;
end;
$$;
create or replace function public.ezone_notify_like()
returns trigger language plpgsql security definer set search_path = public
as $$
declare owner_id uuid;
begin
  select p.user_id into owner_id from public.posts p where p.id = new.post_id;
  if owner_id is not null and owner_id <> new.user_id
     and coalesce((select np.likes from public.notification_preferences np where np.user_id = owner_id), true) then
    insert into public.notifications(user_id, actor_id, post_id, type, message)
    values (owner_id, new.user_id, new.post_id, 'like', 'Someone liked your post');
  end if;
  return new;
end;
$$;
create or replace function public.ezone_notify_follow()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  if coalesce((select np.follows from public.notification_preferences np where np.user_id = new.following_id), true) then
    insert into public.notifications(user_id, actor_id, type, message)
    values (new.following_id, new.follower_id, 'follow', 'Someone started following you');
  end if;
  return new;
end;
$$;
create or replace function public.ezone_notify_message()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  if coalesce((select np.messages from public.notification_preferences np where np.user_id = new.receiver_id), true) then
    insert into public.notifications(user_id, actor_id, type, message)
    values (new.receiver_id, new.sender_id, 'message', 'You received a new message');
  end if;
  return new;
end;
$$;
revoke all on function public.ezone_notify_comment() from public, anon, authenticated;
revoke all on function public.ezone_notify_like() from public, anon, authenticated;
revoke all on function public.ezone_notify_follow() from public, anon, authenticated;
revoke all on function public.ezone_notify_message() from public, anon, authenticated;



-- Enforce private-account visibility for Stories as well as posts.
drop policy if exists "Ezone members read active stories" on public.stories;
create policy "Ezone members read active stories" on public.stories
for select to authenticated
using (
  expires_at > now()
  and (
    user_id = auth.uid()
    or (
      exists (
        select 1 from public.profiles p
        where p.id = stories.user_id
          and p.is_private = false
      )
      and audience = 'public'
    )
    or (
      audience = 'followers'
      and exists (
        select 1 from public.follows f
        where f.follower_id = auth.uid()
          and f.following_id = stories.user_id
      )
    )
    or (
      audience = 'close_friends'
      and exists (
        select 1 from public.close_friends cf
        where cf.owner_id = stories.user_id
          and cf.friend_id = auth.uid()
      )
    )
  )
);
notify pgrst, 'reload schema';


-- Profile grids: owner-controlled pinned posts, tagged-post gallery and collaborator invites.
create table if not exists public.profile_post_pins (
  user_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(user_id, post_id)
);
create index if not exists profile_post_pins_user_created_idx on public.profile_post_pins(user_id, created_at desc);
alter table public.profile_post_pins enable row level security;
drop policy if exists "Ezone pins readable by members" on public.profile_post_pins;
drop policy if exists "Ezone users pin own posts" on public.profile_post_pins;
drop policy if exists "Ezone users unpin own posts" on public.profile_post_pins;
create policy "Ezone pins readable by members" on public.profile_post_pins for select to authenticated using (true);
create policy "Ezone users pin own posts" on public.profile_post_pins for insert to authenticated with check (
  auth.uid() = user_id and exists (select 1 from public.posts p where p.id = post_id and p.user_id = auth.uid())
);
create policy "Ezone users unpin own posts" on public.profile_post_pins for delete to authenticated using (auth.uid() = user_id);

create table if not exists public.tagged_posts (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  tagged_user_id uuid not null references public.profiles(id) on delete cascade,
  tagged_by uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique(post_id, tagged_user_id)
);
create index if not exists tagged_posts_user_created_idx on public.tagged_posts(tagged_user_id, created_at desc);
alter table public.tagged_posts enable row level security;
drop policy if exists "Ezone tagged posts readable by members" on public.tagged_posts;
drop policy if exists "Ezone users tag people in own posts" on public.tagged_posts;
drop policy if exists "Ezone taggers remove tags" on public.tagged_posts;
create policy "Ezone tagged posts readable by members" on public.tagged_posts for select to authenticated using (true);
create policy "Ezone users tag people in own posts" on public.tagged_posts for insert to authenticated with check (
  auth.uid() = tagged_by and exists (select 1 from public.posts p where p.id = post_id and p.user_id = auth.uid())
);
create policy "Ezone taggers remove tags" on public.tagged_posts for delete to authenticated using (auth.uid() = tagged_by or auth.uid() = tagged_user_id);

create table if not exists public.post_collaborators (
  post_id uuid not null references public.posts(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  collaborator_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check(status in ('pending','accepted','declined')),
  created_at timestamptz not null default now(),
  primary key(post_id, collaborator_id),
  check(owner_id <> collaborator_id)
);
alter table public.post_collaborators enable row level security;
drop policy if exists "Ezone collaborators read own invitations" on public.post_collaborators;
drop policy if exists "Ezone post owners invite collaborators" on public.post_collaborators;
drop policy if exists "Ezone collaborators respond" on public.post_collaborators;
create policy "Ezone collaborators read own invitations" on public.post_collaborators for select to authenticated using (auth.uid() = owner_id or auth.uid() = collaborator_id);
create policy "Ezone post owners invite collaborators" on public.post_collaborators for insert to authenticated with check (
  auth.uid() = owner_id and exists (select 1 from public.posts p where p.id = post_id and p.user_id = auth.uid())
);
create policy "Ezone collaborators respond" on public.post_collaborators for update to authenticated using (auth.uid() = collaborator_id and status = 'pending')
with check (auth.uid() = collaborator_id and status in ('accepted','declined'));
grant select, insert, delete on public.profile_post_pins, public.tagged_posts to authenticated;
grant select, insert, update on public.post_collaborators to authenticated;
notify pgrst, 'reload schema';

-- Phase 1 hybrid feed additions are maintained in supabase/migrations/20261121000100_hybrid_social_feed.sql.
-- The migration is inlined here so fresh SQL Editor installs receive the same additive schema.

-- Ezone hybrid social foundation.
-- Additive migration for the existing Kotlin/Compose + Supabase app.
-- Existing auth, profiles, posts, likes, comments, follows, Stories, DMs,
-- saved posts, highlights, Notes, polls, blocks, reports and profile features remain intact.

begin;

do $$
begin
  create type public.ezone_post_format as enum ('reel', 'photo', 'carousel');
exception when duplicate_object then null;
end $$;

-- Extend the current profile/post records instead of replacing their legacy columns.
alter table public.profiles
  add column if not exists followers_count bigint not null default 0,
  add column if not exists following_count bigint not null default 0,
  add column if not exists posts_count bigint not null default 0;

create table if not exists public.audios (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  artist_name text not null default 'Original audio',
  audio_url text not null,
  duration numeric(8,2) not null default 0 check (duration >= 0),
  cover_image_url text,
  usage_count bigint not null default 0 check (usage_count >= 0),
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now()
);

alter table public.posts
  add column if not exists post_format public.ezone_post_format not null default 'photo',
  add column if not exists location text,
  add column if not exists audio_id uuid references public.audios(id) on delete set null,
  add column if not exists likes_count bigint not null default 0,
  add column if not exists comments_count bigint not null default 0,
  add column if not exists shares_count bigint not null default 0,
  add column if not exists views_count bigint not null default 0,
  add column if not exists completion_rate numeric(6,5) not null default 0;

alter table public.posts
  drop constraint if exists posts_likes_count_nonnegative,
  drop constraint if exists posts_comments_count_nonnegative,
  drop constraint if exists posts_shares_count_nonnegative,
  drop constraint if exists posts_views_count_nonnegative,
  drop constraint if exists posts_completion_rate_check;

alter table public.posts
  add constraint posts_likes_count_nonnegative check (likes_count >= 0),
  add constraint posts_comments_count_nonnegative check (comments_count >= 0),
  add constraint posts_shares_count_nonnegative check (shares_count >= 0),
  add constraint posts_views_count_nonnegative check (views_count >= 0),
  add constraint posts_completion_rate_check check (completion_rate between 0 and 1);

update public.posts
set post_format = case when media_type = 'video' then 'reel'::public.ezone_post_format
                       else 'photo'::public.ezone_post_format end
where post_format = 'photo' and media_type = 'video';

-- Ordered media records enable carousels while preserving the current media_path field.
create table if not exists public.post_media (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  media_url text not null,
  thumbnail_url text,
  media_type text not null check (media_type in ('image', 'video')),
  aspect_ratio numeric(8,4) not null default 1 check (aspect_ratio > 0),
  order_index integer not null default 0 check (order_index >= 0),
  created_at timestamptz not null default now(),
  unique(post_id, order_index)
);

insert into public.post_media(post_id, media_url, media_type, order_index)
select p.id, p.media_path, p.media_type, 0
from public.posts p
where p.media_path is not null
  and p.media_type in ('image', 'video')
  and not exists (
    select 1 from public.post_media pm
    where pm.post_id = p.id and pm.order_index = 0
  );

-- Existing stories already support audience and 24-hour expiry; only add overlays and counters.
alter table public.stories
  add column if not exists overlays_json jsonb not null default '[]'::jsonb,
  add column if not exists views_count bigint not null default 0;

alter table public.comments
  add column if not exists parent_comment_id uuid references public.comments(id) on delete cascade,
  add column if not exists likes_count bigint not null default 0;

create index if not exists posts_format_created_idx
  on public.posts(post_format, created_at desc);
create index if not exists posts_audio_idx
  on public.posts(audio_id) where audio_id is not null;
create index if not exists post_media_post_order_idx
  on public.post_media(post_id, order_index);
create index if not exists stories_active_user_idx
  on public.stories(user_id, expires_at desc);
create index if not exists comments_parent_created_idx
  on public.comments(parent_comment_id, created_at);
create index if not exists hashtags_tag_search_idx
  on public.hashtags(lower(tag));
create index if not exists post_hashtags_hashtag_post_idx
  on public.post_hashtags(hashtag_id, post_id);

-- View history and completion metrics power the recommendation score.
create table if not exists public.post_views (
  user_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  view_count integer not null default 1 check (view_count > 0),
  completed_count integer not null default 0 check (completed_count >= 0),
  last_viewed_at timestamptz not null default now(),
  primary key(user_id, post_id),
  constraint post_views_completion_lte_views check (completed_count <= view_count)
);
create index if not exists post_views_user_recent_idx
  on public.post_views(user_id, last_viewed_at desc);
create index if not exists post_views_post_idx
  on public.post_views(post_id);

create table if not exists public.user_hashtag_engagement (
  user_id uuid not null references public.profiles(id) on delete cascade,
  hashtag_id uuid not null references public.hashtags(id) on delete cascade,
  engagement_score numeric(12,3) not null default 0 check (engagement_score >= 0),
  updated_at timestamptz not null default now(),
  primary key(user_id, hashtag_id)
);

-- Comment reactions are separate from post likes and maintain comments.likes_count.
create table if not exists public.comment_likes (
  comment_id uuid not null references public.comments(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(comment_id, user_id)
);
create index if not exists comment_likes_user_idx
  on public.comment_likes(user_id, created_at desc);

-- Extend existing one-to-one DMs without changing body/seen_at used by the current app.
alter table public.direct_messages
  add column if not exists message_type text not null default 'text',
  add column if not exists shared_post_id uuid references public.posts(id) on delete set null,
  add column if not exists media_url text;

alter table public.direct_messages
  drop constraint if exists direct_messages_message_type_check;
alter table public.direct_messages
  add constraint direct_messages_message_type_check
  check (message_type in ('text', 'post_share', 'reel_share', 'image', 'video'));

create index if not exists direct_messages_shared_post_idx
  on public.direct_messages(shared_post_id) where shared_post_id is not null;

-- Add group conversations alongside (not instead of) the existing 1:1 direct_messages.
create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  created_by uuid not null references public.profiles(id) on delete cascade,
  title text,
  is_group boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.conversation_participants (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  joined_at timestamptz not null default now(),
  last_read_at timestamptz,
  primary key(conversation_id, user_id)
);

create table if not exists public.conversation_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  body text not null default '',
  message_type text not null default 'text'
    check (message_type in ('text', 'post_share', 'reel_share', 'image', 'video')),
  shared_post_id uuid references public.posts(id) on delete set null,
  media_url text,
  created_at timestamptz not null default now(),
  constraint conversation_message_payload check (
    (message_type = 'text' and length(btrim(body)) between 1 and 5000)
    or (message_type in ('post_share', 'reel_share') and shared_post_id is not null)
    or (message_type in ('image', 'video') and media_url is not null)
  )
);

create index if not exists conversations_updated_idx
  on public.conversations(updated_at desc);
create index if not exists conversation_participants_user_idx
  on public.conversation_participants(user_id, conversation_id);
create index if not exists conversation_messages_recent_idx
  on public.conversation_messages(conversation_id, created_at desc);

-- RLS-safe membership helpers live in a non-exposed schema.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to authenticated;

create or replace function private.ezone_is_conversation_member(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.conversation_participants cp
    where cp.conversation_id = p_conversation_id
      and cp.user_id = (select auth.uid())
  );
$$;

create or replace function private.ezone_is_conversation_creator(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.conversations c
    where c.id = p_conversation_id
      and c.created_by = (select auth.uid())
  );
$$;

revoke all on function private.ezone_is_conversation_member(uuid) from public, anon;
revoke all on function private.ezone_is_conversation_creator(uuid) from public, anon;
grant execute on function private.ezone_is_conversation_member(uuid) to authenticated;
grant execute on function private.ezone_is_conversation_creator(uuid) to authenticated;

-- Enable RLS on every new exposed table.
alter table public.audios enable row level security;
alter table public.post_media enable row level security;
alter table public.post_views enable row level security;
alter table public.user_hashtag_engagement enable row level security;
alter table public.comment_likes enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_participants enable row level security;
alter table public.conversation_messages enable row level security;

drop policy if exists "Ezone audio catalog readable" on public.audios;
create policy "Ezone audio catalog readable"
  on public.audios for select to authenticated using (true);
drop policy if exists "Ezone users add audio records" on public.audios;
create policy "Ezone users add audio records"
  on public.audios for insert to authenticated
  with check (created_by = (select auth.uid()));
drop policy if exists "Ezone audio owners update records" on public.audios;
create policy "Ezone audio owners update records"
  on public.audios for update to authenticated
  using (created_by = (select auth.uid()))
  with check (created_by = (select auth.uid()));
drop policy if exists "Ezone audio owners delete records" on public.audios;
create policy "Ezone audio owners delete records"
  on public.audios for delete to authenticated
  using (created_by = (select auth.uid()));

drop policy if exists "Ezone post media readable with parent post" on public.post_media;
create policy "Ezone post media readable with parent post"
  on public.post_media for select to authenticated
  using (exists (select 1 from public.posts p where p.id = post_id));
drop policy if exists "Ezone post owners manage media" on public.post_media;
create policy "Ezone post owners manage media"
  on public.post_media for all to authenticated
  using (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ));

drop policy if exists "Ezone users read own post views" on public.post_views;
create policy "Ezone users read own post views"
  on public.post_views for select to authenticated
  using (user_id = (select auth.uid()));
drop policy if exists "Ezone users record own post views" on public.post_views;
create policy "Ezone users record own post views"
  on public.post_views for insert to authenticated
  with check (user_id = (select auth.uid()));
drop policy if exists "Ezone users update own post views" on public.post_views;
create policy "Ezone users update own post views"
  on public.post_views for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

drop policy if exists "Ezone users read own hashtag affinity" on public.user_hashtag_engagement;
create policy "Ezone users read own hashtag affinity"
  on public.user_hashtag_engagement for select to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists "Ezone comment likes readable" on public.comment_likes;
create policy "Ezone comment likes readable"
  on public.comment_likes for select to authenticated using (true);
drop policy if exists "Ezone users like comments" on public.comment_likes;
create policy "Ezone users like comments"
  on public.comment_likes for insert to authenticated
  with check (user_id = (select auth.uid()));
drop policy if exists "Ezone users unlike comments" on public.comment_likes;
create policy "Ezone users unlike comments"
  on public.comment_likes for delete to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists "Ezone group members read conversations" on public.conversations;
create policy "Ezone group members read conversations"
  on public.conversations for select to authenticated
  using (private.ezone_is_conversation_member(id));
drop policy if exists "Ezone users create conversations" on public.conversations;
create policy "Ezone users create conversations"
  on public.conversations for insert to authenticated
  with check (created_by = (select auth.uid()));
drop policy if exists "Ezone creators update conversations" on public.conversations;
create policy "Ezone creators update conversations"
  on public.conversations for update to authenticated
  using (created_by = (select auth.uid()))
  with check (created_by = (select auth.uid()));

drop policy if exists "Ezone group members read participants" on public.conversation_participants;
create policy "Ezone group members read participants"
  on public.conversation_participants for select to authenticated
  using (private.ezone_is_conversation_member(conversation_id));
drop policy if exists "Ezone creators add participants" on public.conversation_participants;
create policy "Ezone creators add participants"
  on public.conversation_participants for insert to authenticated
  with check (private.ezone_is_conversation_creator(conversation_id));
drop policy if exists "Ezone users update own participant state" on public.conversation_participants;
create policy "Ezone users update own participant state"
  on public.conversation_participants for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
drop policy if exists "Ezone users leave conversations" on public.conversation_participants;
create policy "Ezone users leave conversations"
  on public.conversation_participants for delete to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists "Ezone group members read messages" on public.conversation_messages;
create policy "Ezone group members read messages"
  on public.conversation_messages for select to authenticated
  using (private.ezone_is_conversation_member(conversation_id));
drop policy if exists "Ezone members send group messages" on public.conversation_messages;
create policy "Ezone members send group messages"
  on public.conversation_messages for insert to authenticated
  with check (
    sender_id = (select auth.uid())
    and private.ezone_is_conversation_member(conversation_id)
  );
drop policy if exists "Ezone senders delete group messages" on public.conversation_messages;
create policy "Ezone senders delete group messages"
  on public.conversation_messages for delete to authenticated
  using (sender_id = (select auth.uid()));

-- Add the requested mention event to the existing notification type constraint.
alter table public.notifications
  drop constraint if exists notifications_type_check;
alter table public.notifications
  add constraint notifications_type_check
  check (type in ('follow', 'like', 'comment', 'message', 'repost', 'story_reply', 'mention'));

-- Keep denormalized counters accurate. Existing notification triggers are not modified.
create or replace function private.ezone_update_social_counters()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  delta bigint;
begin
  if tg_table_name = 'post_likes' then
    delta := case when tg_op = 'INSERT' then 1 else -1 end;
    update public.posts
      set likes_count = greatest(likes_count + delta, 0)
      where id = case when tg_op = 'INSERT' then new.post_id else old.post_id end;
    if tg_op = 'INSERT' then
      insert into public.user_hashtag_engagement(user_id, hashtag_id, engagement_score)
      select new.user_id, ph.hashtag_id, 1
      from public.post_hashtags ph where ph.post_id = new.post_id
      on conflict (user_id, hashtag_id) do update
        set engagement_score = public.user_hashtag_engagement.engagement_score + 1,
            updated_at = now();
      return new;
    end if;
    return old;
  elsif tg_table_name = 'comments' then
    delta := case when tg_op = 'INSERT' then 1 else -1 end;
    update public.posts
      set comments_count = greatest(comments_count + delta, 0)
      where id = case when tg_op = 'INSERT' then new.post_id else old.post_id end;
    if tg_op = 'INSERT' then
      insert into public.user_hashtag_engagement(user_id, hashtag_id, engagement_score)
      select new.user_id, ph.hashtag_id, 2
      from public.post_hashtags ph where ph.post_id = new.post_id
      on conflict (user_id, hashtag_id) do update
        set engagement_score = public.user_hashtag_engagement.engagement_score + 2,
            updated_at = now();
      return new;
    end if;
    return old;
  elsif tg_table_name = 'follows' then
    delta := case when tg_op = 'INSERT' then 1 else -1 end;
    update public.profiles
      set following_count = greatest(following_count + delta, 0)
      where id = case when tg_op = 'INSERT' then new.follower_id else old.follower_id end;
    update public.profiles
      set followers_count = greatest(followers_count + delta, 0)
      where id = case when tg_op = 'INSERT' then new.following_id else old.following_id end;
    if tg_op = 'INSERT' then return new; else return old; end if;
  elsif tg_table_name = 'posts' then
    if tg_op = 'INSERT' then
      update public.profiles set posts_count = posts_count + 1 where id = new.user_id;
      if new.audio_id is not null then
        update public.audios set usage_count = usage_count + 1 where id = new.audio_id;
      end if;
      return new;
    elsif tg_op = 'DELETE' then
      update public.profiles set posts_count = greatest(posts_count - 1, 0) where id = old.user_id;
      if old.audio_id is not null then
        update public.audios set usage_count = greatest(usage_count - 1, 0) where id = old.audio_id;
      end if;
      return old;
    else
      if old.user_id is distinct from new.user_id then
        update public.profiles set posts_count = greatest(posts_count - 1, 0) where id = old.user_id;
        update public.profiles set posts_count = posts_count + 1 where id = new.user_id;
      end if;
      if old.audio_id is distinct from new.audio_id then
        if old.audio_id is not null then
          update public.audios set usage_count = greatest(usage_count - 1, 0) where id = old.audio_id;
        end if;
        if new.audio_id is not null then
          update public.audios set usage_count = usage_count + 1 where id = new.audio_id;
        end if;
      end if;
      return new;
    end if;
  end if;
  return null;
end;
$$;

revoke all on function private.ezone_update_social_counters() from public, anon, authenticated;

drop trigger if exists ezone_count_post_likes on public.post_likes;
create trigger ezone_count_post_likes
  after insert or delete on public.post_likes
  for each row execute function private.ezone_update_social_counters();

drop trigger if exists ezone_count_comments on public.comments;
create trigger ezone_count_comments
  after insert or delete on public.comments
  for each row execute function private.ezone_update_social_counters();

drop trigger if exists ezone_count_follows on public.follows;
create trigger ezone_count_follows
  after insert or delete on public.follows
  for each row execute function private.ezone_update_social_counters();

drop trigger if exists ezone_count_posts on public.posts;
create trigger ezone_count_posts
  after insert or delete or update of user_id, audio_id on public.posts
  for each row execute function private.ezone_update_social_counters();

create or replace function private.ezone_update_view_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  change_by integer;
  target_post uuid;
begin
  if tg_op = 'INSERT' then
    change_by := new.view_count;
    target_post := new.post_id;
  elsif tg_op = 'DELETE' then
    change_by := -old.view_count;
    target_post := old.post_id;
  else
    change_by := new.view_count - old.view_count;
    target_post := new.post_id;
  end if;
  if change_by <> 0 then
    update public.posts p
      set views_count = greatest(p.views_count + change_by, 0),
          completion_rate = coalesce((
            select sum(pv.completed_count)::numeric / nullif(sum(pv.view_count), 0)
            from public.post_views pv
            where pv.post_id = target_post
          ), 0)
      where p.id = target_post;
  end if;
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;
revoke all on function private.ezone_update_view_counts() from public, anon, authenticated;
drop trigger if exists ezone_post_view_count on public.post_views;
create trigger ezone_post_view_count
  after insert or update of view_count or delete on public.post_views
  for each row execute function private.ezone_update_view_counts();

create or replace function private.ezone_update_story_view_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.stories set views_count = views_count + 1 where id = new.story_id;
  return new;
end;
$$;
revoke all on function private.ezone_update_story_view_counts() from public, anon, authenticated;
drop trigger if exists ezone_story_view_count on public.story_views;
create trigger ezone_story_view_count
  after insert on public.story_views
  for each row execute function private.ezone_update_story_view_counts();

create or replace function private.ezone_update_comment_like_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare target_comment uuid;
declare delta integer;
begin
  target_comment := case when tg_op = 'INSERT' then new.comment_id else old.comment_id end;
  delta := case when tg_op = 'INSERT' then 1 else -1 end;
  update public.comments
    set likes_count = greatest(likes_count + delta, 0)
    where id = target_comment;
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;
revoke all on function private.ezone_update_comment_like_counts() from public, anon, authenticated;
drop trigger if exists ezone_comment_like_count on public.comment_likes;
create trigger ezone_comment_like_count
  after insert or delete on public.comment_likes
  for each row execute function private.ezone_update_comment_like_counts();

create or replace function private.ezone_update_share_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  old_post uuid;
  new_post uuid;
begin
  if tg_op <> 'INSERT' then old_post := old.shared_post_id; end if;
  if tg_op <> 'DELETE' then new_post := new.shared_post_id; end if;
  if old_post is not null and old_post is distinct from new_post then
    update public.posts set shares_count = greatest(shares_count - 1, 0) where id = old_post;
  end if;
  if new_post is not null and new_post is distinct from old_post then
    update public.posts set shares_count = shares_count + 1 where id = new_post;
  end if;
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;
revoke all on function private.ezone_update_share_counts() from public, anon, authenticated;
drop trigger if exists ezone_direct_message_share_count on public.direct_messages;
create trigger ezone_direct_message_share_count
  after insert or update of shared_post_id or delete on public.direct_messages
  for each row execute function private.ezone_update_share_counts();
drop trigger if exists ezone_group_message_share_count on public.conversation_messages;
create trigger ezone_group_message_share_count
  after insert or update of shared_post_id or delete on public.conversation_messages
  for each row execute function private.ezone_update_share_counts();
create or replace function private.ezone_update_repost_share_counts()
returns trigger
language plpgsql
security definer
set search_path = ''
as $
begin
  if tg_op = 'INSERT' then
    update public.posts set shares_count = shares_count + 1 where id = new.post_id;
    return new;
  end if;
  update public.posts set shares_count = greatest(shares_count - 1, 0) where id = old.post_id;
  return old;
end;
$;
revoke all on function private.ezone_update_repost_share_counts() from public, anon, authenticated;
drop trigger if exists ezone_repost_share_count on public.post_reposts;
create trigger ezone_repost_share_count
  after insert or delete on public.post_reposts
  for each row execute function private.ezone_update_repost_share_counts();

-- Backfill counters from existing rows without changing or deleting user content.
update public.posts p set
  likes_count = (select count(*) from public.post_likes l where l.post_id = p.id),
  comments_count = (select count(*) from public.comments c where c.post_id = p.id),
  shares_count =
    (select count(*) from public.post_reposts r where r.post_id = p.id)
    + (select count(*) from public.direct_messages dm where dm.shared_post_id = p.id)
    + (select count(*) from public.conversation_messages cm where cm.shared_post_id = p.id),
  views_count = coalesce((select sum(pv.view_count) from public.post_views pv where pv.post_id = p.id), 0),
  completion_rate = coalesce((
    select sum(pv.completed_count)::numeric / nullif(sum(pv.view_count), 0)
    from public.post_views pv where pv.post_id = p.id
  ), 0);

update public.profiles pr set
  followers_count = (select count(*) from public.follows f where f.following_id = pr.id),
  following_count = (select count(*) from public.follows f where f.follower_id = pr.id),
  posts_count = (select count(*) from public.posts p where p.user_id = pr.id);

update public.audios a set
  usage_count = (select count(*) from public.posts p where p.audio_id = a.id);

update public.stories s set
  views_count = (select count(*) from public.story_views sv where sv.story_id = s.id);

-- Validate nested replies cannot point to a comment on a different post.
create or replace function private.ezone_validate_comment_parent()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare parent_post uuid;
begin
  if new.parent_comment_id is null then return new; end if;
  select c.post_id into parent_post
  from public.comments c where c.id = new.parent_comment_id;
  if parent_post is null or parent_post <> new.post_id then
    raise exception 'Parent comment must belong to the same post';
  end if;
  return new;
end;
$$;
revoke all on function private.ezone_validate_comment_parent() from public, anon, authenticated;
drop trigger if exists ezone_validate_comment_parent on public.comments;
create trigger ezone_validate_comment_parent
  before insert or update of parent_comment_id, post_id on public.comments
  for each row execute function private.ezone_validate_comment_parent();

-- A viewer can record only their own watch. The trigger updates the denormalized post view count.
create or replace function public.ezone_record_post_view(p_post_id uuid, p_completed boolean default false)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare viewer_id uuid := (select auth.uid());
begin
  if viewer_id is null then
    raise exception 'Authentication required';
  end if;
  insert into public.post_views(user_id, post_id, view_count, completed_count, last_viewed_at)
  values (viewer_id, p_post_id, 1, case when p_completed then 1 else 0 end, now())
  on conflict (user_id, post_id) do update
    set view_count = public.post_views.view_count + 1,
        completed_count = public.post_views.completed_count + case when p_completed then 1 else 0 end,
        last_viewed_at = now();
end;
$$;

-- For You ranking uses the requested weighted formula and a capped hashtag-affinity boost.
create or replace function public.get_for_you_feed(
  user_id uuid,
  page_limit integer default 20,
  page_offset integer default 0
)
returns table(post_id uuid, score numeric, created_at timestamptz)
language plpgsql
stable
security invoker
set search_path = ''
as $$
begin
  if (select auth.uid()) is null or (select auth.uid()) <> get_for_you_feed.user_id then
    raise exception 'You may only request your own feed';
  end if;
  if get_for_you_feed.page_limit < 1
     or get_for_you_feed.page_limit > 100
     or get_for_you_feed.page_offset < 0 then
    raise exception 'Invalid pagination parameters';
  end if;

  return query
  with candidates as (
    select
      p.id,
      p.created_at,
      (
        (
          p.likes_count::numeric
          + (p.comments_count::numeric * 3)
          + (p.shares_count::numeric * 5)
          + (p.completion_rate * 10)
        )
        / power(
            greatest(extract(epoch from (now() - p.created_at)) / 3600.0, 0) + 2.0,
            1.5
          )
      ) * (
        1 + least(coalesce((
          select sum(uhe.engagement_score)
          from public.post_hashtags ph
          join public.user_hashtag_engagement uhe
            on uhe.hashtag_id = ph.hashtag_id
           and uhe.user_id = get_for_you_feed.user_id
          where ph.post_id = p.id
        ), 0) / 20.0, 2.0)
      ) as rank_score
    from public.posts p
    where p.user_id <> get_for_you_feed.user_id
      and not exists (
        select 1
        from public.post_views seen
        where seen.user_id = get_for_you_feed.user_id
          and seen.post_id = p.id
          and seen.last_viewed_at >= now() - interval '48 hours'
      )
      and not exists (
        select 1 from public.blocked_users b
        where (b.blocker_id = get_for_you_feed.user_id and b.blocked_id = p.user_id)
           or (b.blocker_id = p.user_id and b.blocked_id = get_for_you_feed.user_id)
      )
  )
  select c.id, c.rank_score, c.created_at
  from candidates c
  order by c.rank_score desc, c.created_at desc, c.id
  limit get_for_you_feed.page_limit
  offset get_for_you_feed.page_offset;
end;
$$;

-- Explicit grants: RLS remains the row-level authorization boundary.
grant select on public.audios, public.post_media, public.comment_likes to authenticated;
grant insert, delete on public.audios to authenticated;
revoke update on public.audios from authenticated;
grant update(title, artist_name, audio_url, duration, cover_image_url)
  on public.audios to authenticated;
grant insert, update, delete on public.post_media to authenticated;
grant insert, delete on public.comment_likes to authenticated;
grant select, insert, update on public.post_views to authenticated;
grant select on public.user_hashtag_engagement to authenticated;
grant select, insert, update on public.conversations, public.conversation_participants to authenticated;
grant select, insert, delete on public.conversation_messages to authenticated;
-- Counter columns are maintained by database triggers, not client writes.
revoke update on public.profiles from authenticated;
grant update(username, display_name, bio, avatar_url, is_private, website, location, pronouns, accent_color)
  on public.profiles to authenticated;
revoke update on public.posts from authenticated;
grant update(body, media_path, media_type, music_path, music_title, music_artist, post_format, location, audio_id)
  on public.posts to authenticated;

grant execute on function public.ezone_record_post_view(uuid, boolean) to authenticated;
grant execute on function public.get_for_you_feed(uuid, integer, integer) to authenticated;

-- Realtime changes are limited to tables needed by new interactive experiences.
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin alter publication supabase_realtime add table public.conversation_messages; exception when duplicate_object then null; end;
    begin alter publication supabase_realtime add table public.comment_likes; exception when duplicate_object then null; end;
  end if;
end $$;

commit;
