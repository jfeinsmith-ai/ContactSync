Build a native Kotlin Android app for on-demand, one-way bulk copying of
contacts between two Google accounts present on the device.

The user selects a FROM account and a different TO account on every run.
Use Android ContactsContract / ContentResolver, not the Google People API.
Request Android contacts permissions at runtime. No Google OAuth setup,
backend, automatic schedule, background sync, or INTERNET permission.

PRIMARY UX
This app must be practical for 1,000+ contacts.

Flow:

1. User chooses FROM and TO Google accounts.
2. User taps Scan.
3. App reads only raw contacts belonging to those accounts and produces
   a plan. Nothing is written during scanning.
4. App shows compact counts:
   - Source contacts scanned
   - New contacts ready to copy
   - Existing contacts automatically skipped by email
   - Existing contacts automatically skipped by LinkedIn URL
   - Name-only clashes requiring review
   - Records skipped because they lack usable identity information
   - Read errors
5. Only name-only clashes are shown as individual review items.
6. User resolves those clashes, then taps "Copy N contacts."
7. App names both full account addresses and N in a final confirmation.
8. App copies eligible contacts and shows progress plus final counts.

Do not build detailed contact views for normal records. Do not require
individual approval for email or LinkedIn matches, or for contacts with
no collision. Do not show full contact details in the normal preview.

ACCOUNT AND DATA HANDLING

- Read account-specific ContactsContract.RawContacts and their associated
  Data rows. Never treat an aggregated Contacts row as belonging to
  either account.
- Match accounts using their exact account name and account type. Allow
  only two distinct Google accounts that the app can actually read and
  target for writes.
- Read structured given and family names, all email addresses, phone
  numbers, and Website rows. Extract LinkedIn person-profile URLs from
  Website rows where present.
- For new contacts, copy structured names, all emails, all phone numbers,
  and websites. Preserve original values and available type/label
  information. Document fields not copied.
- Do not split a display name into guessed first/last components.

COLLISION RULES
Build indexes of existing TO-account raw contacts and source contacts
planned for this run. Compare using normalized keys, but copy the
original field values.

1. EMAIL MATCH: If any normalized email address on a FROM contact
   matches any existing TO contact, automatically skip the FROM contact
   as already present. Never update the existing record.
2. LINKEDIN MATCH: If any normalized LinkedIn person-profile URL matches
   an existing TO contact, automatically skip it as already present.
   Normalize superficial URL variations only; never fetch LinkedIn.
3. NAME-ONLY MATCH: If structured first AND last name match a TO contact
   but neither email nor LinkedIn matches, pause only that source
   contact for user review.
4. NO MATCH: Automatically queue the FROM contact for creation.
5. Within-run source duplicates: If no corresponding collision existed in the
   TO account at Scan, copy every source record, including duplicate-looking
   records. Do not turn source-only duplicates into review items or suppress
   later candidates. Report the retained source-only duplicate count after the
   copy so the user can clean them up later. During copying, allow collisions
   caused solely by contacts created earlier in that same run.
6. If email and LinkedIn point to different existing TO contacts, or
   there are other contradictory identifiers, skip automatically as an
   ambiguous collision and report its count. Do not create, merge, or
   update. A normal user review screen is not required for these cases.
7. If a source record has no usable structured first+last name, valid
   email, or LinkedIn person-profile URL, skip it and count it. Do not
   guess from phone number or display name.

Name-only review should show enough to distinguish the two records:
both names and, if available, their email, LinkedIn URL, and phone
numbers. Default each item to Skip. Choices are:

- Skip;
- Create a separate new TO-account contact despite the name match.

Support "Skip all remaining name clashes" so the user can finish a
large run quickly. A bulk "Create all name clashes" option is not
required. Do not offer merge or update in version 1.

MATCH NORMALIZATION

- Name: trim, normalize Unicode, collapse whitespace, and compare
  structured first and last components case-insensitively.
- Email: trim and compare case-insensitively. Do not remove Gmail dots
  or plus tags.
- LinkedIn: compare recognizable personal-profile URLs after
  normalizing host, scheme, trailing slash, query, and fragment.
  Do not confuse company pages, posts, or other LinkedIn pages with
  personal profiles.
- Keep these functions separate and thoroughly unit-tested.

SAFETY AND RE-RUNS

- No automatic deletion, no destination updates, no reverse sync.
- Store a small local source-to-destination raw-contact ID mapping,
  but never rely on it alone: IDs can change, and the app could crash
  after an insert but before saving a mapping.
- Every new Scan must rebuild the TO-account collision indexes from
  current contacts, independent of local mapping.
- Before starting the copy, verify the selected accounts have not
  changed since Scan. Recheck current TO-account contacts for
  collisions before writes; if the plan has become stale, require
  a fresh Scan rather than writing from an obsolete plan.
- During copying, include newly created contacts in the in-memory
  collision indexes. Before each insert, recheck for a current
  collision so interrupted runs can resume safely.
- Insert one raw contact and its Data rows atomically using
  ContentProviderOperation/applyBatch, explicitly setting the
  selected TO account. Verify the resulting raw contact belongs to
  that account.
- Commit progress contact by contact. Allow cancellation BETWEEN
  contacts. On restart, scanning should skip contacts already copied.
- Stop on an account-targeting or verification failure. Report
  per-contact failures without silently rerouting writes.
- Never request sync-adapter privileges or write sync-adapter-owned
  metadata to force Google sync.
- Treat "saved on device under TO account" and "verified on Google's
  servers" as different claims. Give the user a simple manual
  verification step using the TO account's Google Contacts on the web.

IMPLEMENTATION AND TESTING

- Kotlin Android Studio project; simple UI; pure matching/planning
  logic separate from Contacts Provider access.
- Efficiently query and index 1,000+ contacts; avoid one provider
  query per contact where feasible, avoid holding contact photos,
  and keep the UI responsive during scanning/copying.
- Unit-test all collision types, source-source collisions, conflicting
  email/LinkedIn matches, normalization, name-only decisions,
  repeated runs, crash-after-insert recovery, cancellation, and
  account reversal.
- Test account-scoped reads and writes on a device or test provider.
- First run with two disposable contacts and verify they appear in
  the intended Google account on the web before testing the full
  address book.
- Provide a buildable project, README, test results, and an explicit
  list of anything not verified on a real two-account device.

Implement a working small vertical slice first, then the bulk scan,
collision rules, and robust rerun behavior. Do not call a mocked test
an end-to-end verification of Google sync.

GIT VERSIONING AND GITHUB

Manage this project with Git from the start.

- Before changing files, inspect the current directory for an existing
  Git repository, remote, commits, and uncommitted user changes. If a
  repository exists, preserve its history and do not overwrite or
  discard user changes.
- If this is a new project, initialize Git and create a GitHub repository.
  Make the repository PUBLIC.  Contact information if saved/cached within the project structure is PII and must be gitignored
- Use the GitHub account and authentication already configured in the
  development environment. If GitHub access is unavailable, stop at
  local commits and give me the specific authentication/setup step
  needed; do not ask me to paste a token into a prompt or source file.
- If an existing GitHub repository or remote is ambiguous, ask me which
  one to use before creating or pushing anything.
- Work in small, meaningful commits: project scaffold, account-scoped
  reads, collision planner, preview/review UI, verified writes, tests,
  and documentation. Commit only after the relevant build or tests
  pass, or clearly label and explain a work-in-progress commit.
- Use a simple version scheme: start at v0.1.0 for the first working
  release. Update the app's versionName and versionCode together;
  versionCode must increase for each release build. Create annotated
  Git tags for releases, and maintain a concise CHANGELOG.md.
- Push commits and release tags to GitHub after each completed,
  verified milestone. Do not force-push, rewrite published history,
  or delete remote branches or tags without asking me.
- Before EVERY commit and push, inspect staged files and the diff for
  secrets and personal data. Never commit OAuth credentials, signing
  keys/keystores, local.properties, build outputs, contact exports,
  contact databases, run logs containing contact details, or test
  data copied from my real address book.
- Add an appropriate .gitignore before the first commit. Keep any
  directory I designate for local payloads or personal data ignored
  even if it is inside the project directory. Use synthetic contacts
  in fixtures and tests.
- Do not commit a signing key. Document how I can configure local
  signing later if needed.
- After each milestone, report the commit hash, pushed branch, GitHub
  repository location, tests/build results, and next step. If a push
  fails, report the failure accurately; do not claim the work is on
  GitHub until the push succeeds.

Do not interpret permission to manage Git or publish the public repository as
permission to publish my contact data.
