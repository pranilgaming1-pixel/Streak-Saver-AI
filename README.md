# Ezone
Native Kotlin + Jetpack Compose Android app.

## Supabase setup
1. Create a project at https://supabase.com/dashboard.
2. In Project Settings > API, copy the Project URL and anon/public (or publishable) client key.
3. Open Ezone > Supabase connection settings and paste both values.
4. Never put a service-role key or database password in the Android app.
5. Run `supabase/schema.sql` in Supabase SQL Editor to create starter profiles and posts tables.

## Implemented
- Supabase email/password sign-up and sign-in.
- Password recovery request.
- Supabase project URL and public client key stored locally on device.
- Starter SQL schema and row-level security policies.

## Not yet implemented
The social feed, profile editing, follows, comments, media uploads, private follower approval, messaging, and notifications still need to be connected. Current starter post-read policy is for authenticated users and does not enforce private accounts.
