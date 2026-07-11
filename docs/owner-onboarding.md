# Owner Onboarding

Use this guide when you are ready to set up Yuuka for yourself.

Yuuka is currently configured as a single-owner app. Public registration is disabled by default, and production mode requires an owner email, password hash, and authenticator secret.

## Recommended Helper

The helper prompts without putting the password in shell history, then prints the BCrypt hash, Base32 TOTP secret, and authenticator URI.

Windows PowerShell:

```powershell
.\ops\New-YuukaOwnerConfig.ps1 -Email you@example.com
```

Linux/macOS:

```sh
./ops/new-owner-config.sh you@example.com
```

Store the outputs in an encrypted password manager before continuing. The helper never writes them to a project file.

## 1. Choose Owner Email

Pick the email address you will use to sign in.

Set it in `.env`:

```sh
YUUKA_OWNER_EMAIL=you@example.com
```

## 2. Generate Password Hash

Do not put your raw password in production environment variables.

From `backend/`, generate a BCrypt hash manually. Prefer the environment variable so the password does not remain in shell history:

```sh
YUUKA_PASSWORD_TO_HASH='your-long-password' ./gradlew printPasswordHash
```

On Windows PowerShell:

```powershell
$env:YUUKA_PASSWORD_TO_HASH = Read-Host 'Password' -MaskInput
.\gradlew.bat printPasswordHash
Remove-Item Env:YUUKA_PASSWORD_TO_HASH
```

Copy only the generated hash into `.env`:

```sh
YUUKA_OWNER_PASSWORD_HASH=$2a$12$...
```

For local-only bootstrap, you can temporarily use:

```sh
YUUKA_OWNER_BOOTSTRAP_PASSWORD=your-long-password
```

Do not use `YUUKA_OWNER_BOOTSTRAP_PASSWORD` with the `prod` Spring profile.

## 3. Generate Authenticator Secret

From `backend/`, generate a TOTP secret and authenticator URI:

```sh
./gradlew printTotpSecret -Pemail="you@example.com"
```

On Windows PowerShell:

```powershell
.\gradlew.bat printTotpSecret -Pemail="you@example.com"
```

Add the printed authenticator URI to your authenticator app. Then set:

```sh
YUUKA_OWNER_TOTP_SECRET=BASE32SECRET
```

## 4. Required Production Settings

For production, use the `prod` Spring profile and set:

```sh
SPRING_PROFILES_ACTIVE=prod
YUUKA_OWNER_EMAIL=you@example.com
YUUKA_OWNER_PASSWORD_HASH=$2a$12$...
YUUKA_OWNER_TOTP_SECRET=BASE32SECRET
YUUKA_AUTH_REGISTRATION_ENABLED=false
YUUKA_JWT_SECRET=<long-random-secret>
YUUKA_CORS_ALLOWED_ORIGINS=https://your-app-origin.example
```

Production startup refuses:

- Default JWT secret
- Default database password
- Missing owner email
- Missing owner password hash
- Missing owner TOTP secret
- Plaintext owner bootstrap password
- Enabled public registration
- Localhost CORS origins

## 5. Sign In

Use:

- Your configured owner email
- Your password
- The 6 digit code from your authenticator app

Only the configured owner email should be accepted.

## Recovery

- Lost authenticator: while you still control the homelab, run the helper again, replace `YUUKA_OWNER_TOTP_SECRET`, restart, and enroll the new URI.
- Suspected session theft: revoke refresh sessions with `YUUKA_CONFIRM_REVOKE=REVOKE ./ops/revoke-sessions.sh` or `.\ops\Revoke-YuukaSessions.ps1`, rotate `YUUKA_JWT_SECRET`, restart, and sign in again.
- Lost password: generate a new hash, replace `YUUKA_OWNER_PASSWORD_HASH`, and restart. The configured hash updates the existing owner at startup. Revoke sessions too if compromise is possible.

Never disable TOTP or enable registration as a recovery shortcut in production.
