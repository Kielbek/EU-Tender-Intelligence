# EU Tender Intelligence

Platforma SaaS do wyszukiwania, analizowania i monitorowania przetargów publicznych z 27 państw UE
(źródło bazowe: TED – Tenders Electronic Daily), z analizą dokumentacji przez AI, dopasowaniem do profilu firmy,
tłumaczeniami i alertami, oraz wbudowanym systemem pozyskiwania klientów (CRM, raporty, partnerzy, polecenia, SEO).

**Stack:** Spring Boot 4.1 (Java 21) · PostgreSQL 16 + pgvector · Redis · Angular 22 · Tailwind CSS 4 · Docker.

## Status

| Etap | Zakres | Status |
|---|---|---|
| 1 | Architektura, dokumentacja, Docker, PostgreSQL, szkielet backendu i frontendu, CI | ✅ |
| 2 | Modele danych, migracje, uwierzytelnianie, RBAC, API | ⏳ następny |
| 3–8 | TED, frontend modułów, AI, powiadomienia, Stripe, admin, wdrożenie | 📋 plan |

Co działa teraz: `GET /api/v1/system/status` (stan bazy, pgvector, Redis, gotowość integracji),
ekran „Status systemu” (PL/EN), migracje Flyway (rozszerzenia + 27 państw UE), bezpieczny baseline (deny-all, nagłówki,
Problem Details, correlation id). Integracje zewnętrzne są oznaczone jako `NOT_IMPLEMENTED` do czasu ich zbudowania i przetestowania.

## Szybki start

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml --env-file .env up -d --build
```

Frontend: http://localhost:8081 · API: http://localhost:8080/api/v1/system/status · Swagger: http://localhost:8080/swagger-ui.html

Tryb deweloperski i testy: [docs/deployment.md](docs/deployment.md).

## Dokumentacja

* [Architektura](docs/architecture.md) – decyzje technologiczne, moduły, multi-tenancy, bezpieczeństwo, AI
* [Baza danych](docs/database.md) – model tabel, indeksy, deduplikacja, wersjonowanie
* [Integracja TED i źródeł krajowych](docs/ted-integration.md)
* [API](docs/api.md)
* [Ryzyka](docs/risks.md)
* [Plan implementacji](docs/implementation-plan.md)
* [System pozyskiwania klientów](docs/growth-system.md)
* [Uruchomienie i wdrożenie](docs/deployment.md)

## Struktura

```
backend/    Spring Boot (Maven), migracje Flyway w src/main/resources/db/migration
frontend/   Angular + Tailwind + Transloco
infra/      docker-compose.yml
docs/       dokumentacja
```

## Zasady

Bez atrap danych i „udawanych” integracji; sekrety wyłącznie w zmiennych środowiskowych (`.env` jest ignorowany przez git);
limity planów egzekwowane w backendzie; treść dokumentów przetargowych traktowana jako niezaufane dane dla AI.
