# Homelab Deployment

Yuuka is designed to keep PostgreSQL private and expose only the API through your tailnet. The default Compose binding is `127.0.0.1:8080`; do not change it to `0.0.0.0` unless a separate firewall and reverse proxy require that topology.

## Host prerequisites

- A Linux homelab host with Docker Engine and Docker Compose v2
- Tailscale installed on the host and phone
- HTTPS enabled for the tailnet
- A current backup destination outside the Docker volume

## Production configuration

1. Copy `.env.example` to `.env`.
2. Generate owner credentials with `./ops/new-owner-config.sh you@example.com`.
3. Generate independent database and JWT secrets, for example with `openssl rand -base64 48`.
4. Set at least:

```dotenv
SPRING_PROFILES_ACTIVE=prod
POSTGRES_PASSWORD=<unique-random-password>
SPRING_DATASOURCE_PASSWORD=<same-database-password>
YUUKA_JWT_SECRET=<independent-random-secret>
YUUKA_JWT_ACCESS_TOKEN_TTL=PT15M
YUUKA_OWNER_EMAIL=you@example.com
YUUKA_OWNER_PASSWORD_HASH=$2a$12$...
YUUKA_OWNER_TOTP_SECRET=<generated-base32-secret>
YUUKA_OWNER_BOOTSTRAP_PASSWORD=
YUUKA_AUTH_REGISTRATION_ENABLED=false
YUUKA_BIND_ADDRESS=127.0.0.1
YUUKA_CORS_ALLOWED_ORIGINS=https://your-homelab-node.your-tailnet.ts.net
```

Keep `.env` readable only by the service administrator (`chmod 600 .env`). Do not commit it.

## Start and verify

```sh
docker compose pull
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8080/health/ready
```

Flyway runs before the application accepts requests. Production startup refuses default secrets, public registration, plaintext bootstrap passwords, missing TOTP, and localhost CORS entries.

## Private Tailscale HTTPS

With the API still bound to loopback, publish it only inside the tailnet:

```sh
sudo tailscale serve --bg 8080
tailscale serve status
```

Tailscale Serve proxies the local `127.0.0.1:8080` service through a tailnet-only HTTPS hostname. Do not use Tailscale Funnel for Yuuka; Funnel is public internet exposure. Current command behavior is documented in the [official Tailscale Serve CLI reference](https://tailscale.com/docs/reference/tailscale-cli/serve).

Set the mobile Server field to:

```text
https://your-homelab-node.your-tailnet.ts.net/api/v1
```

Use Tailscale ACLs/grants so only your user and intended phone can reach the homelab device. The Yuuka password and TOTP remain required even on the tailnet.

## Updates

```sh
./ops/backup.sh
docker compose pull
docker compose build --pull backend
docker compose up -d
curl --fail http://127.0.0.1:8080/health/ready
```

Review migration notes before downgrading. Flyway migrations are forward-only; restore the pre-upgrade backup if a rollback is required.

## Android installation

For development, `npm start` in `mobile/` opens Expo and can be launched in Expo Go. For an installable APK, set `EXPO_PUBLIC_API_BASE_URL` to the Tailscale HTTPS API URL and use the `preview` profile:

```sh
cd mobile
npm ci
npx eas-cli build --platform android --profile preview
```

EAS is optional. A no-cloud build can use `npx eas-cli build --local --platform android --profile preview` on Linux/WSL with the Android toolchain. The production profile emits the store-oriented Android artifact. Never place backend secrets in `EXPO_PUBLIC_*`; those values are compiled into the app.
