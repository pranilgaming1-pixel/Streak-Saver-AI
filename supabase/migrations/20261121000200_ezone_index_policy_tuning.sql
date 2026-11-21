-- Ezone Phase 1 follow-up: avoid duplicate SELECT policy evaluation and
-- cover the new and existing foreign-key lookup paths.
begin;

drop policy if exists "Ezone post owners manage media" on public.post_media;

drop policy if exists "Ezone post owners insert media" on public.post_media;
create policy "Ezone post owners insert media"
  on public.post_media for insert to authenticated
  with check (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ));

drop policy if exists "Ezone post owners update media" on public.post_media;
create policy "Ezone post owners update media"
  on public.post_media for update to authenticated
  using (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ))
  with check (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ));

drop policy if exists "Ezone post owners delete media" on public.post_media;
create policy "Ezone post owners delete media"
  on public.post_media for delete to authenticated
  using (exists (
    select 1 from public.posts p
    where p.id = post_id and p.user_id = (select auth.uid())
  ));

create index if not exists audios_created_by_idx on public.audios(created_by);
create index if not exists blocked_users_blocked_idx on public.blocked_users(blocked_id);
create index if not exists close_friends_friend_idx on public.close_friends(friend_id);
create index if not exists comments_user_idx on public.comments(user_id);
create index if not exists content_reports_post_idx on public.content_reports(post_id);
create index if not exists content_reports_reported_user_idx on public.content_reports(reported_user_id);
create index if not exists content_reports_reporter_idx on public.content_reports(reporter_id);
create index if not exists conversation_messages_sender_idx on public.conversation_messages(sender_id);
create index if not exists conversation_messages_shared_post_idx on public.conversation_messages(shared_post_id);
create index if not exists conversations_creator_idx on public.conversations(created_by);
create index if not exists notifications_actor_idx on public.notifications(actor_id);
create index if not exists notifications_post_idx on public.notifications(post_id);
create index if not exists poll_options_poll_idx on public.poll_options(poll_id);
create index if not exists poll_votes_option_idx on public.poll_votes(option_id);
create index if not exists poll_votes_user_idx on public.poll_votes(user_id);
create index if not exists post_collaborators_collaborator_idx on public.post_collaborators(collaborator_id);
create index if not exists post_collaborators_owner_idx on public.post_collaborators(owner_id);
create index if not exists post_likes_user_idx on public.post_likes(user_id);
create index if not exists post_reposts_post_idx on public.post_reposts(post_id);
create index if not exists profile_post_pins_post_idx on public.profile_post_pins(post_id);
create index if not exists saved_posts_post_idx on public.saved_posts(post_id);
create index if not exists story_highlight_items_story_idx on public.story_highlight_items(story_id);
create index if not exists story_highlights_user_idx on public.story_highlights(user_id);
create index if not exists story_views_viewer_idx on public.story_views(viewer_id);
create index if not exists tagged_posts_tagged_by_idx on public.tagged_posts(tagged_by);
create index if not exists user_hashtag_engagement_hashtag_idx on public.user_hashtag_engagement(hashtag_id);
create index if not exists user_notes_user_idx on public.user_notes(user_id);

create or replace function private.ezone_touch_conversation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.conversations
    set updated_at = now()
    where id = new.conversation_id;
  return new;
end;
$$;
revoke all on function private.ezone_touch_conversation() from public, anon, authenticated;
drop trigger if exists ezone_touch_conversation on public.conversation_messages;
create trigger ezone_touch_conversation
  after insert on public.conversation_messages
  for each row execute function private.ezone_touch_conversation();

commit;
