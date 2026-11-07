# Uruchomienie i wdrożenie

## Wymagania

* Docker 24+ z Compose v2
* Do pracy bez Dockera: Java 21, Node.js 22+, npm 10+

## 1. Cały stos w Dockerze

```bash
cp .env.example .env          # zmień hasła
docker compose -f infra/docker-compose.yml --env-file .env up -d --build
```

| Usługa | Adres |
|---|---|
| Frontend (nginx, proxy `/api` → backend) | http://localhost:8081 |
| Backend API | http://localhost:8080/api/v1/system/status |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| PostgreSQL / Redis | `127.0.0.1:5432` / `127.0.0.1:6379` (porty tylko na localhost) |

Migracje Flyway uruchamiają się automatycznie przy starcie backendu.

## 2. Tryb deweloperski (hot reload)

```bash
docker compose -f infra/docker-compose.yml --env-file .env up -d postgres redis
```

```bash
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run
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
