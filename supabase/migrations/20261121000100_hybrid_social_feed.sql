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
