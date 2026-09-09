# Deployment

How to run the full stack (Postgres + `ml-service` + `java-backend`) on a single always-on VM,
behind an nginx reverse proxy with HTTPS.

This guide targets the **Oracle Cloud Infrastructure (OCI) Always Free** tier because it gives you a
genuinely always-on machine with enough RAM to run all three containers plus a
`sentence-transformers` model. Nothing here is OCI-specific except the "Provision the VM" and
"Cloud-side firewall" sections — the rest applies to any Ubuntu host.

---

## 0. What you'll end up with

```
                    Internet
                       │  :80 / :443
                       ▼
             ┌───────────────────┐
             │  nginx (host)     │   TLS termination, Let's Encrypt cert
             └─────────┬─────────┘
                       │  proxy_pass http://127.0.0.1:8080
                       ▼
   ┌───────────────────────────────────────────────┐
   │  docker compose  (bridge network)             │
   │                                               │
   │   java-backend ──► ml-service (ml-service:8000)│
   │        │                                      │
   │        ▼                                      │
   │     postgres  (pantry_pg_data volume)         │
   └───────────────────────────────────────────────┘
```

- Only nginx is exposed to the internet (ports 80/443). Postgres and `ml-service` are never
  published on a host port; `java-backend` is published only on `127.0.0.1:8080` (loopback), so the
  only way in from outside is through nginx.
- The three containers talk to each other over the compose bridge network by service name
  (`postgres`, `ml-service`, `java-backend`).

---

## 1. Provision the VM (Oracle Cloud Always Free)

1. Create an OCI account and sign in to the Console.
2. **Compute → Instances → Create instance.**
   - **Image:** Canonical Ubuntu 24.04 (or 22.04).
   - **Shape:** `VM.Standard.A1.Flex` (Ampere / ARM). The Always Free allowance is up to 4 OCPU and
     24 GB RAM across all your A1 instances — 2 OCPU / 12 GB is plenty for this stack and leaves
     headroom. (The older `VM.Standard.E2.1.Micro` AMD micro shape is also Always Free but its 1 GB
     RAM is too small once the embedding model loads.)
   - **SSH keys:** upload your public key — you'll need the matching private key to connect.
   - **Networking:** let it create a new VCN and subnet, and make sure the instance gets a **public
     IPv4 address**.
3. Note the instance's **public IP** once it boots.
4. Point a DNS **A record** for your domain (e.g. `pantry.example.com`) at that public IP. HTTPS
   issuance in step 7 needs the name to resolve before you start.

> **ARM note:** the A1 shape is `aarch64`. All four base images used here
> (`postgres:16`, `python:3.12-slim`, `maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre-jammy`)
> are multi-arch and pull an arm64 variant automatically, and the CPU-only PyTorch wheel the
> `ml-service` image installs has an `aarch64` build. Because the images are **built on the VM
> itself** (`docker compose build`), architecture takes care of itself — you don't need buildx or
> cross-compilation. Building `java-backend` downloads the full Maven dependency set the first time
> and the `ml-service` build downloads PyTorch (~200 MB) plus bakes the ~80 MB embedding model, so
> the first `docker compose build` on a fresh VM takes a while; subsequent builds reuse the layer
> cache.

---

## 2. Connect and update

```bash
ssh ubuntu@<public-ip>
sudo apt-get update && sudo apt-get -y upgrade
```

---

## 3. Install Docker Engine + Compose plugin

Use Docker's official apt repository (the `docker.io` package in Ubuntu's own repo is older and
ships Compose v1):

```bash
sudo apt-get -y install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# run docker without sudo (log out / back in afterwards for it to take effect)
sudo usermod -aG docker $USER
```

Verify:

```bash
docker version
docker compose version      # expect v2.x ("compose plugin"), invoked as `docker compose`
```

> This repo's files say `docker-compose` (the v1 hyphenated binary) in a few places for historical
> reasons. On the VM use `docker compose` (space). The compose file itself is compatible with both.

---

## 4. Get the code and configure secrets

```bash
sudo apt-get -y install git
git clone <your-repo-url> meal-grocery-assistant
cd meal-grocery-assistant

cp .env.example .env
nano .env        # fill in real values — see below
```

`.env` must contain (see `.env.example` for the annotated template):

| Variable        | What it is                                                                 |
|-----------------|---------------------------------------------------------------------------|
| `OCR_API_KEY`   | Gemini API key — receipt OCR, price-tag OCR, fallback nutrition estimate. |
| `USDA_API_KEY`  | USDA FoodData Central key (`DEMO_KEY` works for light testing only).      |

`.env` is gitignored — it never gets committed. `docker compose` reads it automatically from the
project directory for `${VAR}` substitution in `docker-compose.yml`, and passes `OCR_API_KEY` /
`USDA_API_KEY` through to the `java-backend` container as environment variables. The compose file
uses `${OCR_API_KEY:?...}` / `${USDA_API_KEY:?...}`, so `docker compose up` fails fast with a clear
message if either is missing or empty.

Database credentials are **not** in `.env` — Postgres runs only on the internal compose network with
the default `postgres` / `postgres` / `pantrydb` values baked into `docker-compose.yml`, and is
never reachable from outside the host. If you want to change them, edit the `postgres` service
environment and the `SPRING_DATASOURCE_*` values in `docker-compose.yml` together.

### Optional: recipe dataset

Recipe search and meal-plan generation need `java-backend/src/main/resources/data/recipes.csv`
(see `RecipeDataLoader.java` for the column format). It's loaded into the DB once on the first
backend startup. If you add it after the stack is already running, restart the backend:
`docker compose restart java-backend`.

---

## 5. How the services are published

`docker-compose.yml` is already set up for this:

- **`java-backend`** publishes `127.0.0.1:8080:8080` — loopback only. `curl http://127.0.0.1:8080/api/inventory`
  works **on the VM** (and that's what nginx proxies to), but nothing on `8080` is reachable from
  the internet even before the firewall rules below.
- **`ml-service`** uses `expose` (no host port) — reachable only from `java-backend` over the
  compose network.
- **`postgres`** publishes `5432:5432`. That is convenient for connecting a DB client from your
  laptop over an SSH tunnel, but if you don't need that, drop the `ports:` block from the
  `postgres` service so it's internal-only like `ml-service`. Either way the firewall below never
  opens 5432 to the internet.

---

## 6. Firewall

Two layers have to agree: OCI's virtual firewall (security list / NSG) **and** the iptables rules
inside the Ubuntu instance. Oracle's Ubuntu image ships with a restrictive `INPUT` chain that
already blocks most inbound traffic, so you must open ports in *both* places.

### 6a. Cloud-side (OCI Console)

**Networking → Virtual Cloud Networks → your VCN → Security Lists → Default Security List →
Add Ingress Rules:**

| Source CIDR | Protocol | Dest. port | Purpose |
|-------------|----------|-----------|---------|
| `0.0.0.0/0` | TCP      | 80        | HTTP (redirects to HTTPS + ACME challenge) |
| `0.0.0.0/0` | TCP      | 443       | HTTPS |

Port 22 (SSH) is already allowed by the default rule. **Do not** add ingress rules for 8080, 8000,
or 5432 — those stay internal.

### 6b. Instance-side (iptables)

Oracle's Ubuntu image has an `INPUT` chain ending in a `REJECT` rule; new `ACCEPT` rules must be
inserted *before* it. Insert at an explicit position (the SSH rule is usually rule 5 or 6 — check
with `sudo iptables -L INPUT --line-numbers`):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 7 -m state --state NEW -p tcp --dport 443 -j ACCEPT

# persist across reboots
sudo apt-get -y install netfilter-persistent iptables-persistent
sudo netfilter-persistent save
```

If you prefer `ufw`, first make sure its rules land ahead of Oracle's REJECT (they normally do once
`ufw` is enabled): `sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable`.

The container ports need **no** rules here — Docker's own iptables chains handle the compose bridge
network, and nothing is published beyond loopback after step 5.

---

## 7. Bring the stack up

```bash
cd ~/meal-grocery-assistant
docker compose up -d --build
```

Watch it come up:

```bash
docker compose ps
docker compose logs -f java-backend      # Ctrl-C to stop following
```

Startup ordering is handled in `docker-compose.yml`:

- `postgres` has a `pg_isready` healthcheck; `java-backend` has `depends_on: postgres:
  { condition: service_healthy }`, so it won't start until the database accepts connections. On the
  **very first** start Postgres also runs every script in `db/init/` against the fresh
  `pantry_pg_data` volume — that's what creates the schema and seeds the default rows the app
  expects (`user_preference` id 1, `users` id 1). `java-backend` runs Hibernate with
  `ddl-auto: validate` and refuses to boot if the schema is missing or stale.
- `java-backend` depends on `ml-service` with `condition: service_started` only (not
  `service_healthy`) — `MlServiceClient` degrades gracefully to default recipe ordering if
  `ml-service` is down, so the backend must not block on it.

Health-check from the VM:

```bash
curl -fsS http://127.0.0.1:8080/api/inventory        # -> 200, JSON array (possibly [])
```

### Schema upgrades on an existing volume

`db/init/` scripts run **only** against an empty data directory. If you `git pull` a change that
adds a new script and the `pantry_pg_data` volume already exists, apply it by hand:

```bash
docker compose exec -T postgres psql -U postgres -d pantrydb < db/init/00X_whatever.sql
```

or, if you can afford to lose the data, recreate the volume: `docker compose down -v && docker compose up -d`.

---

## 8. nginx reverse proxy

Run nginx on the **host** (not in a container) so it owns 80/443 directly and certbot can manage it.

```bash
sudo apt-get -y install nginx
```

Create `/etc/nginx/sites-available/pantry`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name pantry.example.com;

    # certbot writes ACME challenge files here; everything else -> HTTPS
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name pantry.example.com;

    # placeholder certs until certbot swaps in real ones (step 9)
    ssl_certificate     /etc/ssl/certs/ssl-cert-snakeoil.pem;
    ssl_certificate_key /etc/ssl/private/ssl-cert-snakeoil.key;

    client_max_body_size 15m;      # receipt / price-tag image uploads

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;        # USDA/Gemini lookups on a cold cache can be slow
    }
}
```

Enable it and reload:

```bash
sudo ln -s /etc/nginx/sites-available/pantry /etc/nginx/sites-enabled/pantry
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## 9. HTTPS with Let's Encrypt

```bash
sudo snap install --classic certbot
sudo ln -sf /snap/bin/certbot /usr/bin/certbot

sudo certbot --nginx -d pantry.example.com --redirect --agree-tos -m you@example.com --no-eff-email
```

certbot edits the nginx config in place to point at the issued certificate and sets up renewal.
Verify auto-renewal is scheduled:

```bash
systemctl list-timers | grep certbot        # snap installs a systemd timer
sudo certbot renew --dry-run
```

Test from your laptop:

```bash
curl -fsS https://pantry.example.com/api/inventory
```

---

## 10. Day-to-day operations

| Task | Command (from `~/meal-grocery-assistant`) |
|---|---|
| Deploy a new version | `git pull && docker compose up -d --build` |
| Tail logs | `docker compose logs -f java-backend ml-service` |
| Restart one service | `docker compose restart java-backend` |
| Stop everything (keep DB) | `docker compose down` |
| Stop and wipe the DB | `docker compose down -v` |
| DB shell | `docker compose exec postgres psql -U postgres -d pantrydb` |
| Disk usage | `docker system df` — prune old build layers with `docker builder prune` |
| Back up the DB | `docker compose exec -T postgres pg_dump -U postgres pantrydb | gzip > backup-$(date +%F).sql.gz` |

### Restore a backup

```bash
gunzip -c backup-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U postgres -d pantrydb
```

---

## 11. Hardening checklist

- **SSH:** disable password auth (`PasswordAuthentication no` in `/etc/ssh/sshd_config`), keep it
  key-only. Consider restricting the OCI ingress rule for port 22 to your own IP.
- **No unintended public ports:** `sudo ss -ltnp` should show `nginx` on 80/443, `sshd` on 22, and
  `docker-proxy` on `127.0.0.1:8080` (loopback). `ml-service` (8000) must not appear at all. The
  stock compose file also publishes `docker-proxy` on `0.0.0.0:5432`; the firewall keeps it off the
  internet, but if you don't tunnel to Postgres, remove the `ports:` block from the `postgres`
  service so it isn't listening on a public interface either.
- **Unattended upgrades:** `sudo apt-get -y install unattended-upgrades` for automatic security
  patches.
- **`.env` permissions:** `chmod 600 .env`.
- **Image freshness:** periodically `docker compose build --pull` to pick up base-image security
  updates, then `docker compose up -d`.
- **Secrets rotation:** if a key leaks, rotate it with the provider, update `.env`, and
  `docker compose up -d java-backend`. After rotating `OCR_API_KEY`, also delete any null-macro rows
  from `nutrition_info` so failed lookups retry (see `docs/setup.md`).
- **Postgres credentials:** the default `postgres`/`postgres` is acceptable only because the port is
  never exposed. If you publish it for any reason, change the password first.
