# Ezone

Ezone is a native Android social app built with Kotlin and Jetpack Compose. It uses Supabase Auth, PostgREST, and Storage. Accounts are real Supabase users; the app does not seed demo accounts or bot friends.

## Current app features

- Email/password sign-up, login, password reset, and saved Supabase sessions with refresh-token renewal.
- Google sign-in through Supabase OAuth (requires the Google provider to be configured in Supabase).
- Black, white, and ice-blue interface.
- For You and Following feeds backed by real posts.
- Text posts, photo uploads, and video uploads.
- Search profiles, follow/unfollow, like/unlike, comments, profile editing, and activity notifications.
- Per-user data access enforced with Supabase Row Level Security.

## One-time Supabase setup

1. Open your Supabase project dashboard.
2. Open **SQL Editor**.
3. Paste and run the complete file at \`supabase/schema.sql\`.
4. In **Storage**, confirm the \`ezone-media\` bucket exists (the SQL creates it).
5. In **Authentication → URL Configuration**, add \`ezone://auth\` to the Redirect URLs.
6. For Google sign-in, enable the Google provider in **Authentication → Sign In / Providers** and enter the OAuth Client ID and Client Secret from your Google OAuth application. Add the Supabase callback URL shown on that provider page to the Google OAuth application's authorized redirect URIs.

The Android client contains only the Supabase project URL and publishable key. Never put a Supabase \`service_role\` key in the app.

## Build

GitHub Actions runs \`.github/workflows/android.yml\` for pushes to \`development\` and \`main\`. Download the \`streak-saver-ai-debug-apk\` artifact from the successful run to install the debug build.

## Known external configuration

Google OAuth will not complete until the Google provider is enabled and configured in the Supabase dashboard. Email confirmation and password reset email delivery depend on the Supabase Auth email settings for the project.


## Private messaging

The Android app includes an inbox shortcut in the top bar and a **Message** action on other members' profiles. Messages are stored in the Supabase `direct_messages` table. Row Level Security allows only the sender and receiver to read a conversation; authenticated users can send only as themselves. Run the latest `supabase/schema.sql` migration to enable messaging. The app intentionally does not create sample accounts or fake conversations.
