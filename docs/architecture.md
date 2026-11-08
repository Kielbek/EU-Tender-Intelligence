# Architektura – EU Tender Intelligence

> Status: **Etap 1** (fundamenty). Dokument opisuje architekturę docelową oraz to, co już istnieje w repozytorium.
> Sekcje oznaczone `[ZAIMPLEMENTOWANE]` działają w kodzie; pozostałe to projekt do realizacji w kolejnych etapach.

## 1. Decyzja technologiczna

Oryginalna specyfikacja proponowała FastAPI + Next.js. Na wyraźne życzenie właściciela produktu stack został zmieniony na
**Spring Boot + Angular**. Poniżej mapowanie elementów specyfikacji na ekosystem Java/Angular:

| Wymaganie ze specyfikacji | Rozwiązanie w Spring / Angular | Uzasadnienie |
|---|---|---|
| FastAPI | **Spring Boot 4.1 (Java 21)**, Spring Web MVC, virtual threads | Dojrzały ekosystem enterprise, wirtualne wątki dla I/O (TED, LLM, S3) |
| Pydantic | Java `record` + Jakarta Bean Validation | Walidacja deklaratywna DTO |
| SQLAlchemy | Spring Data JPA (Hibernate 7) + natywne SQL tam, gdzie potrzeba (FTS, pgvector) | Standard, prosta wielodostępność przez filtry |
| Alembic | **Flyway** (`backend/src/main/resources/db/migration`) | Wersjonowane migracje SQL, uruchamiane przy starcie |
| Celery + Redis | **JobRunr** (kolejka zadań w PostgreSQL, retry, dashboard) + Spring `@Scheduled` + ShedLock | Brak osobnego brokera, trwałe zadania, retry z backoffem, panel zadań dla admina |
| Redis | Redis: cache, rate limiting (Bucket4j), sesje odświeżania, blokady | |
| OpenAI / wymienny LLM | **Spring AI** (abstrakcja `ChatModel`/`EmbeddingModel`: OpenAI, Anthropic, Mistral, Azure, Ollama) | Wymiana providera konfiguracją |
| pgvector | pgvector + Spring AI `PgVectorStore` lub własne repozytorium SQL | RAG w tej samej bazie, izolacja danych przez SQL |
| OCR | Apache Tika + Tesseract (Tess4J) w workerze | Działa lokalnie, bez wysyłania dokumentów do zewnętrznych usług |
| OpenSearch | Opcjonalnie od Etapu 7; do tego czasu PostgreSQL FTS + `pg_trgm` | Unikamy przedwczesnej złożoności |
| Next.js / React | **Angular 22** (standalone components, signals, SSR dla stron SEO) | |
| Tailwind + shadcn/ui | Tailwind CSS 4 + Angular CDK + własna biblioteka komponentów w stylu shadcn | Spójny design, dostępność z CDK (focus trap, a11y) |
| Lucide Icons | `lucide-angular` | |
| Recharts | Własne lekkie wykresy HTML (słupki/kolumny) | Zero zależności (~0 kB vs ~1 MB ECharts), tooltip z klawiatury, widok tabeli, kolory walidowane pod kątem kontrastu i CVD |
| i18n | **Transloco** (przełączanie języka w runtime, lazy-loaded słowniki) | Angular i18n wymaga osobnego buildu per język |
| Stripe | `stripe-java` + weryfikowane webhooki | |
| S3 | AWS SDK v2 (S3-compatible: MinIO lokalnie, Hetzner Object Storage / AWS S3 produkcyjnie) | |

## 2. Widok ogólny

```
                          ┌───────────────────────────────┐
  Przeglądarka  ─────────▶│  Angular SPA + SSR (nginx /   │
  (PL/EN/DE/...)          │  node SSR dla stron SEO)      │
                          └──────────────┬────────────────┘
                                         │ HTTPS /api/v1 (JSON, JWT)
                          ┌──────────────▼────────────────┐
                          │  backend – rola "api"         │  Spring Boot (bezstanowy, N replik)
                          │  REST, auth, RBAC, limity     │
                          └──┬───────┬────────┬──────┬────┘
                             │       │        │      │
                  ┌──────────▼┐ ┌────▼───┐ ┌──▼───┐ ┌▼───────────┐
                  │PostgreSQL │ │ Redis  │ │ S3   │ │ Stripe /   │
                  │+pgvector  │ │        │ │MinIO │ │ SMTP / LLM │
                  │+JobRunr   │ └────────┘ └──────┘ └────────────┘
                  └─────▲─────┘
                        │ zadania (JobRunr)
                  ┌─────┴─────────────────────────┐
                  │  backend – rola "worker"      │  ten sam artefakt, inny profil
                  │  ingestion TED / adaptery     │
                  │  OCR, chunking, embeddingi    │
                  │  analizy AI, matching, maile  │
                  └─────┬─────────────────────────┘
                        │ HTTPS (zgodnie z ToS źródeł)
                ┌───────▼────────┐   ┌──────────────────────────┐
                │ TED Search API │   │ Adaptery krajowe (PL, DE,│
                │ TED bulk XML   │   │ CZ, FR, NL, AT, ...)     │
                └────────────────┘   └──────────────────────────┘
```

**Modularny monolit** (jeden artefakt, ścisłe granice pakietów) uruchamiany w dwóch rolach:

* `api` – obsługuje ruch HTTP; skaluje się poziomo.
* `worker` – wykonuje zadania w tle (ingestion, OCR, AI, powiadomienia); skaluje się niezależnie.

Rola wybierana jest zmienną `APP_ROLES` (`api`, `worker` lub `api,worker` w dev). Monolit zamiast mikroserwisów, bo:
mały zespół, jedna baza, transakcje między modułami, niższy koszt utrzymania. Granice modułów pozwalają wydzielić
serwis później (np. ingestion), jeśli zajdzie potrzeba.

## 3. Moduły backendu (bounded contexts)

Pakiet bazowy: `eu.tenderintel`.

| Pakiet | Odpowiedzialność | Etap |
|---|---|---|
| `common` | błędy (RFC 9457 Problem Details), correlation id, czas, waluty, paginacja | 1 `[ZAIMPLEMENTOWANE: błędy, correlation id]` |
| `config` | konfiguracja aplikacji (`AppProperties`), security, CORS, OpenAPI | 1 `[ZAIMPLEMENTOWANE]` |
| `system` | status systemu, informacje o wersji, gotowość zależności | 1 `[ZAIMPLEMENTOWANE]` |
| `identity` | użytkownicy, rejestracja, logowanie, JWT, reset hasła, weryfikacja e-mail | 2 |
| `organization` | organizacje, członkostwa, role, zaproszenia, izolacja tenantów | 2 |
| `tender` | przetargi, wersje, dokumenty, zamawiający, CPV, kraje, wyszukiwanie | 2–4 |
| `ingestion` | źródła danych, adaptery (TED + krajowe), zadania pobierania, normalizacja, deduplikacja | 3 |
| `company` | profil firmy, certyfikaty, referencje, dokumenty firmy | 5 |
| `matching` | silnik dopasowania 0–100 z uzasadnieniem | 5 |
| `ai` | abstrakcja LLM, RAG, OCR, ekstrakcja wymagań, tłumaczenia, asystent | 5 |
| `notification` | preferencje, alerty, digest, e-mail, in-app | 6 |
| `billing` | plany, subskrypcje, Stripe, limity (entitlements) | 6 |
| `admin` | panel administratora, monitoring źródeł, ponawianie zadań, logi | 7 |
| `analytics` | analiza rynku i konkurencji (wyniki postępowań, wykonawcy) | 7+ |
| `growth.*` | CRM, LinkedIn (ręczny), e-mail outreach, darmowe raporty, partnerzy, polecenia, SEO CMS – patrz [growth-system.md](growth-system.md) | 9+ |

Zasady:

* Moduł wystawia **fasadę** (`*Service`) i DTO; repozytoria i encje są pakietowo-prywatne tam, gdzie to możliwe.
* Komunikacja asynchroniczna między modułami przez **zdarzenia domenowe** Springa (`ApplicationEventPublisher`) z
  `@TransactionalEventListener` – np. `TenderPublished` → `matching` → `notification`.
* Docelowo weryfikacja granic przez Spring Modulith / ArchUnit (test w CI).

## 4. Wielodostępność (multi-tenancy) i izolacja danych

* Dane **publiczne** (przetargi, dokumenty przetargowe, CPV, zamawiający) – wspólne dla wszystkich.
* Dane **prywatne** (profile firm, dokumenty firm, zapisane wyszukiwania, dopasowania, analizy AI dokumentów firmy,
  powiadomienia) – zawsze z kolumną `organization_id`.
* Izolacja w trzech warstwach:
  1. Warstwa serwisu: każde zapytanie o dane prywatne przyjmuje `TenantContext` z tokenu JWT; repozytoria nie mają metod bez `organizationId`.
  2. Hibernate `@Filter`/`@TenantId` na encjach prywatnych.
  3. **PostgreSQL Row Level Security** na tabelach prywatnych (`SET LOCAL app.current_org = ...` w transakcji) jako obrona w głąb.
* Testy integracyjne sprawdzające, że użytkownik organizacji A nie odczyta zasobów organizacji B (IDOR).

## 5. Bezpieczeństwo (skrót – szczegóły w Etapie 2 i 7)

* Hasła: Argon2id (`Argon2PasswordEncoder`), polityka siły haseł, blokada po nieudanych próbach.
* Tokeny: krótki access token JWT (15 min, w pamięci SPA) + rotowany refresh token w ciasteczku `HttpOnly; Secure; SameSite=Strict`, przechowywany jako hash w DB (możliwość unieważnienia).
* RBAC: role platformy (`USER`, `PLATFORM_ADMIN`) + role w organizacji (`COMPANY_ADMIN`, `TEAM_MEMBER`); autoryzacja metod `@PreAuthorize`.
* Rate limiting: Bucket4j + Redis per IP / per użytkownik / per plan.
* Limity planów egzekwowane w backendzie (`EntitlementService`), licznik zużycia w DB.
* Walidacja: Bean Validation na wszystkich DTO; zapytania wyłącznie parametryzowane (JPA/JDBC).
* XSS: Angular domyślnie escapuje; HTML z ogłoszeń sanityzowany po stronie backendu (OWASP Java HTML Sanitizer) przed zapisem; nagłówki CSP.
* Dokumenty: prywatny bucket, dostęp przez krótko żyjące presigned URL po sprawdzeniu uprawnień; skan AV (ClamAV) dla uploadów użytkownika.
* Webhooki Stripe: weryfikacja podpisu, idempotencja po `event.id`.
* **Prompt injection**: treść dokumentów przetargowych jest niezaufanym wejściem – jest przekazywana do LLM wyłącznie jako dane w wydzielonych blokach, system prompt zakazuje wykonywania poleceń z dokumentów, model nie ma narzędzi o skutkach ubocznych w trybie analizy, wynik walidowany schematem JSON i cytatami (każdy cytat musi istnieć w źródłowym chunku).
* Audyt: `audit_logs` dla operacji wrażliwych (logowanie, zmiany ról, eksport, dostęp do dokumentów firmy, akcje admina).
* RODO: minimalizacja danych, eksport i usunięcie konta, retencja logów, rejestr podprocesorów (LLM, e-mail, Stripe), umowy DPA, hosting w UE.

## 6. AI – zasada „bez halucynacji”

1. Dokumenty → Tika (tekst) / Tesseract (OCR skanów) → normalizacja → podział na chunki z metadanymi
   (dokument, strona, sekcja, offset).
2. Embeddingi chunków → pgvector (indeks HNSW).
3. Ekstrakcja wymagań: dla każdej z 12 kategorii wyszukiwanie chunków (hybrydowo: wektor + FTS) → LLM ze
   **structured output** (JSON Schema) → każda pozycja musi zawierać `document_id`, `page/section`, `quote`.
4. **Weryfikacja cytatów**: backend sprawdza, czy `quote` występuje (po normalizacji) w danym chunku. Pozycje
   niezweryfikowane są odrzucane. Brak danych → „Nie znaleziono informacji w dostępnej dokumentacji”.
5. Analiza w języku źródłowym, następnie tłumaczenie wyniku na język użytkownika (cytaty zachowane w oryginale + tłumaczenie oznaczone jako automatyczne).
6. Koszty: cache wyników per (dokument hash, wersja promptu, model), limity tokenów per plan, rejestr zużycia w `ai_usage`.

## 7. Wyszukiwanie

* Etap 4: PostgreSQL – filtry strukturalne (indeksy B-tree), `tsvector` wielojęzyczny (`simple` + `unaccent`), `pg_trgm` dla nazw zamawiających.
* Wyszukiwanie semantyczne: embedding tytułu + opisu przetargu w pgvector.
* Zapytania w języku naturalnym („budowlane w Niemczech powyżej 500 000 euro”): LLM z function calling zamienia tekst na **ten sam obiekt filtrów**, który zwraca formularz – użytkownik widzi zinterpretowane filtry i może je poprawić.
* OpenSearch dopiero, gdy wolumen/latencja tego wymagają (kryterium: p95 > 500 ms przy >5 mln wersji ogłoszeń).

## 8. Przetwarzanie w tle

| Zadanie | Wyzwalacz | Częstotliwość |
|---|---|---|
| `TedIncrementalIngestionJob` | cron | co 1 h (dni robocze TED publikuje ok. 08:00–09:00 CET; częściej nie ma sensu) |
| `TedBackfillJob` | ręcznie (admin) | jednorazowo/okresowo |
| `DocumentFetchJob` | nowe/zmienione ogłoszenie | zdarzenie |
| `DocumentExtractionJob` (Tika/OCR/chunking/embeddings) | po pobraniu dokumentu | zdarzenie |
| `MatchingJob` | nowy przetarg / zmiana profilu | zdarzenie |
| `NotificationDigestJob` | cron | wg preferencji (natychmiast/dziennie/tygodniowo) |
| `DeadlineReminderJob` | cron | co 1 h |
| `ExpireTendersJob` | cron | dziennie |

Wszystkie zadania: idempotentne, z retry (backoff wykładniczy), zapisem do `ingestion_jobs` / dashboardu JobRunr i blokadą rozproszoną (ShedLock) dla cronów.

## 9. Obserwowalność

* Spring Boot Actuator (`/actuator/health/liveness`, `/readiness`, `/prometheus`) `[ZAIMPLEMENTOWANE: health]`.
* Logi JSON (ECS) z `correlationId` (nagłówek `X-Request-Id`) `[ZAIMPLEMENTOWANE: correlation id]`.
* Prometheus + Grafana, alerty: błędy ingestion, opóźnienie świeżości danych, koszt AI, kolejka zadań.
* Sentry (opcjonalnie) dla frontendu i backendu.

## 10. Frontend

* Angular 22, standalone components, signals, `HttpClient` z interceptorami (auth, correlation id, błędy).
* Struktura feature-first (`src/app/features/*`), wspólne komponenty UI w `src/app/shared/ui`.
* Transloco – język domyślny **polski**, przełącznik języka zapisywany w profilu użytkownika (a przed logowaniem w `localStorage`).
* Design system: granat / biel / szarość / niebieskie akcenty, tryb jasny i ciemny, gęstość informacji jak w narzędziach analitycznych.
* Dostępność: WCAG 2.2 AA – kontrast, focus visible, nawigacja klawiaturą, `aria-live` dla stanów ładowania/błędów, skip link.
* SSR (Angular SSR) tylko dla publicznych stron marketingowych/SEO; aplikacja po zalogowaniu jako SPA.

## 11. Struktura repozytorium

```
/
├── backend/                     Spring Boot (Maven)
│   └── src/main/java/eu/tenderintel/
│       ├── common/              wspólne: błędy, web, czas
│       ├── config/              konfiguracja, security, OpenAPI
│       ├── system/              status systemu
│       ├── identity/            (Etap 2)
│       ├── organization/        (Etap 2)
│       ├── tender/              (Etap 2–4)
│       ├── ingestion/           (Etap 3) ted/, adapters/<kraj>/
│       ├── company/ matching/ ai/ notification/ billing/ admin/ analytics/
│       └── growth/              (CRM i pozyskiwanie klientów)
│   └── src/main/resources/db/migration/   migracje Flyway
├── frontend/                    Angular
│   └── src/app/
│       ├── core/                api, interceptory, i18n, layout
│       ├── shared/ui/           komponenty UI
│       └── features/            dashboard, search, tender, company, ...
├── infra/
│   ├── docker-compose.yml
│   └── postgres/init/           rozszerzenia DB
├── docs/                        architektura, baza, API, wdrożenie, TED, ryzyka, plan
├── .github/workflows/ci.yml
├── .env.example
└── README.md
```

W specyfikacji struktura była opisana w konwencji Python/Next.js (`/api`, `/services`, `/repositories`...). W Javie
stosujemy podział **package-by-feature** (każdy moduł ma swoje `api` / `service` / `repository` / `domain`), co lepiej
pilnuje granic modułów niż globalne katalogi warstw.
