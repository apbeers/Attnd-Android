# Android Release Pipeline — Setup Guide

The workflow at `.github/workflows/android-release.yml` triggers on every push
to `main` and uploads a signed AAB to the Google Play **internal** track.
Add the secrets below in **GitHub → Settings → Secrets and variables → Actions**
before the first run.

---

## Secrets required

### 1. `ANDROID_KEYSTORE_BASE64`
Your release keystore file as a base64 string.

Your existing keystore is at:
```
/Users/andrew/Documents/android_keystore/Untitled.jks
```

**How to encode it:**
```bash
base64 -i /Users/andrew/Documents/android_keystore/Untitled.jks | pbcopy
```
Paste the clipboard value as the secret.

> **Keep the original .jks file safe.** If you lose it you cannot update the
> app on Google Play — Google ties the signing key to the package permanently.
> Consider storing a backup in a password manager or encrypted cloud storage.

---

### 2. `ANDROID_KEYSTORE_PASSWORD`
The password for the keystore file.

Current value (from your local `signing.properties`): `bedsheets`

---

### 3. `ANDROID_KEY_ALIAS`
The alias of the key inside the keystore.

Current value: `key0`

---

### 4. `ANDROID_KEY_PASSWORD`
The password for the key itself (may be the same as the keystore password).

Current value: `bedsheets`

---

### 5. `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`
A Google Cloud service account JSON key that has permission to publish to
Google Play. This is the most involved step.

**How to create it:**

1. Open [Google Play Console](https://play.google.com/console) → your app →
   **Setup → API access**.
2. Click **Link to a Google Cloud project** (create one if needed).
3. In Google Cloud Console → **IAM & Admin → Service Accounts** → **Create
   service account**.
   - Name it something like `github-release`.
   - Click **Create and continue**.
4. Grant it the role **Service Account User** at the project level, then close.
5. Back in Google Play Console → **Setup → API access** → **Grant access** next
   to the new service account.
   - Set permissions to **Release manager** (or at minimum: "Manage production
     releases" and "Manage testing track releases").
   - Click **Invite user**.
6. In Google Cloud Console → the service account → **Keys → Add key → Create
   new key → JSON**.
7. Download the `.json` file.
8. Add the **entire contents** of the JSON file as the secret value:
   ```bash
   cat service-account.json | pbcopy
   ```

---

### 6. `ATTND_API_KEY`
Already set from the CI test pipeline. No action needed.

---

## Choosing the release track

The workflow currently uploads to the **internal** track (`track: internal`).
This lets you test the build internally before rolling it out. To change tracks,
edit `.github/workflows/android-release.yml`:

```yaml
track: internal    # Options: internal, alpha, beta, production
```

Promoting from internal → production is done manually in Google Play Console.

---

## App signing by Google Play (optional)

Google Play offers to re-sign your app with their own key (Play App Signing).
If you opt in, Google stores the final signing key and your upload key (the
keystore above) is only used to verify the upload. This is recommended for new
apps — enable it in Play Console → **Setup → App signing** before the first
release.

---

## What the pipeline produces

`app/build/outputs/bundle/release/app-release.aab` — a signed Android App
Bundle uploaded directly to the internal track. No APK is produced (the Play
Store generates optimized APKs from the AAB for each device).
