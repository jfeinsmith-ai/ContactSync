# ContactSync

ContactSync is a native Kotlin Android app for an on-demand, one-way bulk copy
of contacts between two Google accounts already present on the device. It uses
Android's Contacts Provider only: there is no backend, Google OAuth client,
automatic schedule, or Internet permission.

## What it does

1. Select distinct FROM and TO Google accounts.
2. Scan without writing anything.
3. Automatically skip email and LinkedIn profile matches already in TO.
4. Review only structured first-and-last-name clashes (default: Skip).
5. Confirm both full account addresses and the exact number of contacts.
6. Copy one raw contact at a time with account verification and between-contact
   cancellation.

Every scan rebuilds the destination index from current account-scoped raw
contacts. Immediately before copying, ContactSync rejects stale plans; before
each insert it rechecks the current destination. Inserts use a single atomic
`applyBatch` and explicitly target the selected TO account.

Duplicate-looking contacts that exist only within FROM are all copied. The scan
and final result report the number of retained source-only duplicate records and
groups so they can be cleaned up later in TO.

## Privacy and permissions

- Runtime permissions: `READ_CONTACTS` and `WRITE_CONTACTS`.
- Normal permission: `GET_ACCOUNTS`, used to list Google accounts on-device.
- Deliberately absent: `INTERNET`.
- Contact values are read into memory only for the active scan/copy run.
- The only persisted app data is a small, hashed source/account mapping to the
  resulting destination raw-contact ID. It is only a recovery hint and never a
  source of truth.
- Project-local contact data, exports, databases, caches, payloads, backups,
  and contact-bearing logs are treated as PII and ignored by Git.

## Copied fields

ContactSync copies the original structured given/family names, all email
addresses, all phone numbers, and all website rows. Available Android type and
custom-label values are preserved. Display names are never split or guessed.

Version 0.2.0 does **not** copy middle names, prefixes/suffixes, phonetic names,
nicknames, organizations, postal addresses, events, notes, relationships,
IM/SIP fields, photos, group memberships, custom MIME rows, or sync-adapter
metadata. It never merges, updates, deletes, or reverse-syncs contacts.

## Build and test

Requirements: Android Studio with JDK 17 and Android SDK 35.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and is
ignored by Git. Unit tests use synthetic `example.test` identities only. The
verified v0.2.0 APK is available from the
[GitHub release](https://github.com/jfeinsmith-ai/ContactSync/releases/tag/v0.2.0).

## Device safety check

Before using a real address book, follow
[docs/DEVICE_TEST_CHECKLIST.md](docs/DEVICE_TEST_CHECKLIST.md) with two
disposable contacts. A successful local insert is not proof that Google has
synced it; verify the TO account separately at
[Google Contacts](https://contacts.google.com/).

## Verification status

- **v0.2.0 is fully verified for the acceptance scenario.**
- Automated JVM collision/copy-safety suite: 21 tests passing.
- Android lint: 0 errors. Debug and release builds pass.
- Real Android device with two Google accounts: verified.
- Disposable-contact scan, name-only review, exact-account targeting, copy, and
  rerun behavior: verified.
- Bulk run: 2,002 contacts created locally under the selected TO account with no
  app-reported write failures.
- Google Contacts web verification: all copied contacts became visible after
  Android-to-Google synchronization completed. For a large run, server
  visibility can lag successful on-device creation.
- Source-only duplicate retention and completion reporting: verified as the
  intended v0.2.0 behavior.

“Created on device” remains intentionally distinct from “visible on Google’s
servers”: the first is verified immediately by ContactSync, while the second
depends on Android’s Google account sync finishing.

Development is specification-driven. See [ContactSync Spec.md](ContactSync%20Spec.md).
