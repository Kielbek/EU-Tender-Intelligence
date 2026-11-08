# EU Tender Intelligence

Platforma SaaS do wyszukiwania, analizowania i monitorowania przetargów publicznych z 27 państw UE
(źródło bazowe: TED – Tenders Electronic Daily), z analizą dokumentacji przez AI, dopasowaniem do profilu firmy,
tłumaczeniami i alertami, oraz wbudowanym systemem pozyskiwania klientów (CRM, raporty, partnerzy, polecenia, SEO).

**Stack:** Spring Boot 4.1 (Java 21) · PostgreSQL 16 + pgvector · Redis · Angular 22 · Tailwind CSS 4 · Docker.

## Status

| Etap | Zakres | Status |
|---|---|---|
| 1 | Architektura, dokumentacja, Docker, PostgreSQL, szkielet backendu i frontendu, CI | ✅ |
| 2 | Modele danych, migracje, uwierzytelnianie, RBAC, organizacje, audyt, rate limiting, słowniki (CPV, EBC), API przetargów | ✅ |
| 3 | Integracja TED: pobieranie, normalizacja eForms, deduplikacja, wersje i historia zmian, harmonogram | ✅ |
| 4 | Frontend: dashboard, wyszukiwarka, szczegóły przetargu | ⏳ następny |
| 5–8 | AI, powiadomienia, Stripe, admin UI, wdrożenie | 📋 plan |

Co działa teraz:

* Konta: rejestracja (akceptacja regulaminu), weryfikacja e-mail, logowanie (Argon2id, JWT + rotowany refresh token
  w ciasteczku HttpOnly z wykrywaniem ponownego użycia), blokada po nieudanych próbach, reset i zmiana hasła, wylogowanie ze wszystkich urządzeń.
* Organizacje (tenanty): członkowie, role `COMPANY_ADMIN`/`TEAM_MEMBER`, zaproszenia e-mail, ochrona ostatniego administratora, izolacja danych.
* Administrator platformy: użytkownicy (blokowanie), logi audytowe, źródła danych, zadania importu, kolejka e-maili.
* Słowniki z oficjalnych źródeł: **CPV 2008** (9454 kody, 24 języki UE – Urząd Publikacji UE), **kursy EBC** (codziennie), 27 państw UE, waluty.
* **Prawdziwe przetargi z TED**: import co godzinę w godzinach publikacji (pn–sb), import początkowy z ostatnich dni,
  ręczny backfill z panelu admina; zmiany, anulowania i wyniki (zwycięzcy, wartości, liczba ofert) z pełną historią zmian.
  Test na żywo: ~18,8 tys. ogłoszeń z 5 dni, 0 błędów – szczegóły w [docs/ted-integration.md](docs/ted-integration.md).
* API przetargów (filtry, CPV, wartość w EUR, pełnotekstowe bez polskich znaków, sortowanie, paginacja, historia zmian).
* Frontend: logowanie, rejestracja, linki z e-maili, konto, organizacja i zespół, status systemu (PL/EN).
* E-maile przez kolejkę (outbox) i SMTP; lokalnie Mailpit (http://localhost:8025).

Integracje, których jeszcze nie ma, są oznaczone w `GET /api/v1/system/status` jako `NOT_IMPLEMENTED`.

## Szybki start

```bash
cp .env.example .env
# ustaw JWT_SECRET (openssl rand -base64 32) i PLATFORM_ADMIN_EMAILS=twoj@email
docker compose -f infra/docker-compose.yml --env-file .env up -d --build
```

Frontend: http://localhost:8081 · Mailpit: http://localhost:8025 · API: http://localhost:8080/api/v1/system/status · Swagger: http://localhost:8080/swagger-ui.html

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
