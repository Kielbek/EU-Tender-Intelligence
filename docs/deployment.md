# Uruchomienie i wdrożenie

## Wymagania

* Docker 24+ z Compose v2
* Do pracy bez Dockera: Java 21, Node.js 22+, npm 10+

## 1. Cały stos w Dockerze

```bash
cp .env.example .env          # zmień hasła, ustaw JWT_SECRET i PLATFORM_ADMIN_EMAILS
docker compose -f infra/docker-compose.yml --env-file .env up -d --build
```

| Usługa | Adres |
|---|---|
| Frontend (nginx, proxy `/api` → backend) | http://localhost:8081 |
| Backend API | http://localhost:8080/api/v1/system/status |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Mailpit (podgląd e-maili, tylko lokalnie) | http://localhost:8025 |
| PostgreSQL / Redis | `127.0.0.1:5432` / `127.0.0.1:6379` (porty tylko na localhost) |

Migracje Flyway uruchamiają się automatycznie przy starcie backendu. Przy pierwszym starcie (puste tabele) backend
importuje w tle słownik CPV (Urząd Publikacji UE, ~1–2 min) i kursy EBC z 90 dni – postęp w `GET /api/v1/admin/ingestion-jobs`.
Następnie startuje import TED z ostatnich `TED_INITIAL_BACKFILL_DAYS` dni (domyślnie 3; ok. 3 800 ogłoszeń na dzień
roboczy, ~1 min/dzień), a potem import przyrostowy co godzinę. Starsze dane: `POST /api/v1/admin/ted/ingest?from=&to=`
(maks. 31 dni na zadanie). Wymaga dostępu do `publications.europa.eu`, `www.ecb.europa.eu` i `api.ted.europa.eu`.

Szacunek miejsca: ~3 KB surowego JSON + ~3 KB danych przetargu na ogłoszenie → ok. 6–8 GB rocznie (z indeksami).

**Pierwszy administrator:** wpisz swój adres w `PLATFORM_ADMIN_EMAILS`, zarejestruj się i kliknij link aktywacyjny (w Mailpit) –
konto otrzyma rolę `PLATFORM_ADMIN` po potwierdzeniu adresu.

## 2. Tryb deweloperski (hot reload)

```bash
docker compose -f infra/docker-compose.yml --env-file .env up -d postgres redis
```

```bash
cd backend && set -a && . ../.env && set +a && SMTP_HOST=localhost SMTP_PORT=1025 SMTP_AUTH=false SMTP_STARTTLS=false ./mvnw spring-boot:run
```

```bash
cd frontend && npm ci && npm start     # http://localhost:4200, /api proxowane na :8080
```

## 3. Testy

```bash
cd backend && ./mvnw verify            # wymaga Dockera (Testcontainers: pgvector + Redis)
```

```bash
cd frontend && npx ng test --watch=false && npx ng build
```

## 4. Produkcja (plan – Etap 8)

* **Hosting**: Hetzner Cloud (UE, dobry stosunek ceny) lub AWS eu-central-1. Start: 1× VM aplikacyjna (api + worker),
  zarządzany PostgreSQL lub dedykowana VM z pgBackRest, Redis, Hetzner Object Storage (S3).
* **Baza na produkcji**: aplikacja łączy się rolą bez uprawnień superusera; rozszerzenia (`vector`, `pg_trgm`, `unaccent`, `citext`, `btree_gin`)
  tworzy administrator przed pierwszym wdrożeniem – migracja `V1` jest wtedy no-op.
* **TLS**: reverse proxy (Caddy/Traefik) z automatycznym Let's Encrypt, HSTS.
* **Sekrety**: zmienne środowiskowe z menedżera sekretów (np. Doppler, AWS Secrets Manager, SOPS) – nigdy w repozytorium.
* **Backupy**: ciągła archiwizacja WAL + pełny backup dzienny, retencja 30 dni, kopia w innej lokalizacji, **comiesięczny test odtworzenia**.
* **Monitoring**: Prometheus + Grafana + Alertmanager, logi JSON (ECS) do Loki, uptime check na `/actuator/health/readiness`.
* **CI/CD**: GitHub Actions – testy → build obrazów → push do rejestru → wdrożenie (Compose/Kamal lub k8s przy większej skali).
* `API_DOCS_ENABLED=false` na produkcji, `CORS_ALLOWED_ORIGINS` ustawione na domenę aplikacji.
* `REFRESH_COOKIE_SECURE=true`, `APP_PUBLIC_URL` = adres aplikacji (linki w e-mailach), prawdziwy dostawca SMTP z SPF/DKIM/DMARC.
* `JWT_SECRET` unikalny per środowisko; rotacja = wylogowanie wszystkich (access tokeny żyją 15 min).
* `server.forward-headers-strategy=framework`: backend ufa `X-Forwarded-For` – wystaw go **tylko** za zaufanym reverse proxy (inaczej limity per IP można obejść).
