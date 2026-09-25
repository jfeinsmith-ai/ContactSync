# Two-account device verification checklist

Use two disposable Google contacts before trying a full address book.

1. Install the debug APK on an Android device with two Google accounts.
2. Grant ContactSync read and write contacts permissions.
3. In the FROM account, create two synthetic disposable contacts:
   - one with a unique email address;
   - one whose structured first and last name already exists in the TO account,
     but whose email and LinkedIn fields do not match.
4. Select the FROM and TO addresses in ContactSync and tap **Scan**.
5. Confirm that the unique record is ready and the name-only record defaults to
   **Skip** in the review list.
6. Tap **Copy 1 contact** and verify the confirmation names both full addresses.
7. Finish the copy. In the device Contacts app, filter to the TO account and
   confirm the new raw contact is stored there.
8. Open [Google Contacts](https://contacts.google.com/) in a browser, select the
   TO account, wait for Android's normal sync, and confirm the contact appears.
9. Run Scan again. Confirm the copied contact is automatically skipped by email.
10. Reverse FROM and TO and confirm the plan reflects the reversed direction.

Do not call the test complete if the contact exists only on-device. Record the
device model, Android version, account type, local result, web result, and any
sync delay without including contact values or account addresses in project
logs.
