# Outlook for Android

A minimal Android WebView wrapper for Outlook on the web.

## Why

The official Outlook Android app is not always wanted or available, and some tenants block Outlook in mobile browsers through platform-scoped Conditional Access. This app opens `https://outlook.office.com/mail/` in the system WebView with a Linux desktop User-Agent derived from the installed WebView's Chromium version, while keeping Android touch input and zoom.

Sister project of [Teams for Android](https://github.com/L0g0ff/teams-for-android), same approach.

## What works (MVP)

- Microsoft 365 sign-in with persistent session
- Reading and sending mail, calendar and the rest of Outlook on the web
- `mailto:` links from other apps open a new Outlook draft (to, cc, bcc, subject, body)
- Attachment uploads through the Android file picker
- Attachment downloads to Downloads, when Outlook serves them as HTTPS URLs
- Microsoft sign-in windows stay in the app; other links open in your browser

## Not yet

- Notifications: there are none. Background mail notifications need a page that keeps running; see Teams for Android for that approach.
- Downloads that Outlook generates inside the page (`blob:`) show a message instead of saving.

## Note on policy

A desktop User-Agent makes sign-ins appear as Linux in tenant logs and passes policies that only target Android/iOS. Your admin can close that path; on a work account, check with IT.

Microsoft Outlook is proprietary software served by Microsoft and is not included in this repository. This project is not affiliated with Microsoft.
