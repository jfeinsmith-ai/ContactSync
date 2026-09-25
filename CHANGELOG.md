# Changelog

## 0.2.0 - 2026-09-25

- Source-only duplicate-looking contacts are all copied when the TO account had
  no corresponding collision at Scan.
- The scan and final result report how many source-only duplicate contacts and
  groups were intentionally retained for later cleanup.
- Existing TO-account collision rules remain unchanged.
- Completed real-device acceptance verification, including a 2,002-contact
  bulk copy and eventual Google Contacts web visibility after account sync.

## 0.1.1 - 2026-09-25

- Fixed scanning on Contacts Provider implementations whose Data view does not
  expose the RawContacts `deleted` column.
- Data rows are now loaded only for previously verified, non-deleted,
  account-owned raw-contact IDs in bounded batches.

## 0.1.0 - 2026-09-25

- Added account-scoped scanning for Google raw contacts and supported Data rows.
- Added normalized email, LinkedIn profile, and structured-name collision rules.
- Added name-only review, exact-account confirmation, progress, and cancellation.
- Added atomic per-contact writes, post-write account verification, stale-plan
  rejection, rerun recovery, and local mapping hints.
- Added synthetic unit tests, privacy-safe Git exclusions, and device checklist.
