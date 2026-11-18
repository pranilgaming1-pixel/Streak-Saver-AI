# Streak Saver AI

A native Kotlin Android companion app for TikTok streak reminders and AI-assisted message drafting.

## Included
- Room database for persistent friend profiles, reminder preferences, and manually checked-in streak counts
- Manual daily check-in to track your streak in-app after you send a TikTok message
- Background reminder checks with WorkManager (15-minute periodic checks; delivery time can be delayed by Android battery optimization)
- Android notifications with per-friend daily reminder time
- Gemini message generation using the official REST `generateContent` endpoint
- In-app Gemini API-key settings (key stays in app-private preferences; never commit it to Git)
- TikTok profile handoff: copy a prepared message and open the friend's profile
- GitHub Actions workflow that builds and uploads a debug APK

## TikTok messaging
The app does not collect TikTok credentials, automate UI taps, or call undocumented private endpoints. The TikTok Business Messaging send endpoint has access-review and eligible-business-account requirements. This build therefore prepares messages and opens profiles; the user reviews and sends messages in TikTok. See [TikTok Business Messaging](https://business-api.tiktok.com/portal/docs/send-a-message-to-a-conversation/v1.3).

## Gemini setup
1. Create an API key at https://aistudio.google.com/app/apikey.
2. Install the app, open **AI settings**, and paste the key.
3. Open a friend and tap **AI message**.

The app currently uses `gemini-3.8-flash` with the Gemini Developer API REST endpoint. Usage is subject to your Google AI Studio account limits and terms.

## Build
GitHub Actions runs on pushes to `development` and `main`. Open the repository's **Actions** tab, select the latest Android Build run, and download the `streak-saver-ai-debug-apk` artifact.

## Notes
- Reminder checks run every 15 minutes, not at an exact alarm minute. Android battery and background policies may delay delivery.
- Grant notification permission when prompted.
- No automatic TikTok message sending is included.
