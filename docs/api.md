# API

* Bazowy prefiks: `/api/v1` (wersjonowanie w ścieżce; zmiany łamiące → `/api/v2`).
* Format: JSON, daty ISO 8601 w UTC, kwoty jako string dziesiętny + kod waluty ISO 4217.
* Błędy: RFC 9457 `application/problem+json` z polami `type`, `title`, `status`, `detail`, `correlationId` (+ `errors[]` dla walidacji).
* Każda odpowiedź ma nagłówek `X-Request-Id` (przekazany przez klienta, jeśli poprawny, lub wygenerowany).
* Specyfikacja OpenAPI generowana z kodu: `GET /v3/api-docs`, Swagger UI: `/swagger-ui.html` (wyłączane `API_DOCS_ENABLED=false`).

## Endpointy – Etapy 1–5a `[ZAIMPLEMENTOWANE]`

Pełna, aktualna specyfikacja: Swagger UI. Skrót:

| Metoda | Ścieżka | Dostęp | Opis |
|---|---|---|---|
| GET | `/api/v1/system/status` | publiczny | wersja, stan PostgreSQL (z wersją pgvector) i Redis, gotowość integracji (`READY` / `PENDING_CONFIGURATION` / `NOT_IMPLEMENTED`) |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | publiczny | sondy dla orkiestratora (bez szczegółów) |
| GET | `/actuator/prometheus` | **zablokowany** w Etapie 1 (`denyAll`) | metryki – udostępnione w Etapie 8 tylko w sieci wewnętrznej |
| POST | `/api/v1/auth/register` | publiczny (limit 10/h/IP) | zawsze 202 – bez ujawniania istnienia konta |
| POST | `/api/v1/auth/verify-email`, `/verify-email/resend` | publiczny | token z e-maila |
| POST | `/api/v1/auth/login` | publiczny (limity IP + konto) | access token w body, refresh token w ciasteczku `eti_refresh` (HttpOnly, SameSite=Strict, path `/api/v1/auth`) |
| POST | `/api/v1/auth/refresh`, `/logout` | ciasteczko + nagłówek `X-ETI-CSRF: 1` | rotacja tokenu; ponowne użycie starego → unieważnienie całej rodziny |
| POST | `/api/v1/auth/password/forgot`, `/password/reset` | publiczny | reset unieważnia wszystkie sesje |
| GET/PATCH | `/api/v1/me` | zalogowany | profil (imię, język UE, strefa czasowa) |
| POST | `/api/v1/me/password`, `/me/sessions/revoke-all` | zalogowany | |
| GET | `/api/v1/organizations` | zalogowany | organizacje użytkownika z rolą |
| GET/PATCH | `/api/v1/organizations/{id}` | członek / `COMPANY_ADMIN` | obca organizacja → 404 |
| GET | `/api/v1/organizations/{id}/members` | członek | |
| PUT | `/api/v1/organizations/{id}/members/{userId}/role` | `COMPANY_ADMIN` | ostatni admin chroniony (409 `last_admin`) |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | `COMPANY_ADMIN` lub sam członek | |
| POST/GET/DELETE | `/api/v1/organizations/{id}/invitations[/{invId}]` | `COMPANY_ADMIN` | |
| POST | `/api/v1/invitations/accept` | zalogowany, zweryfikowany e-mail = adres zaproszenia | |
| GET | `/api/v1/countries`, `/currencies`, `/currencies/rates/latest` | publiczny | |
| GET | `/api/v1/cpv?query=&parent=&lang=`, `/api/v1/cpv/{code}` | publiczny | wyszukiwanie po kodzie/nazwie, drzewo |
| GET | `/api/v1/tenders` | zalogowany | filtry: `q, country[], cpv[], status[], contractType[], procedureType[], authority, city, nuts, currency, language, valueMinEur, valueMaxEur, publishedFrom/To, deadlineFrom/To, sort, direction, page, size≤100` |
| GET | `/api/v1/tenders/{id}`, `/{id}/versions` | zalogowany | szczegóły, źródła, historia zmian |
| GET | `/api/v1/tenders?…&lang=` | zalogowany | + `mainCpvLabel` w języku `lang` |
| GET | `/api/v1/tenders/export?…&format=csv\|xlsx&lang=` | zalogowany (30/h) | maks. 5 000 wierszy; nagłówki `X-Total-Count`, `X-Export-Limit`; audyt `DATA_EXPORTED` |
| GET | `/api/v1/organizations/{id}/dashboard?lang=` | członek | agregaty z bazy (szczegóły w kodzie `DashboardService`) |
| GET/PUT/DELETE | `/api/v1/organizations/{id}/saved-tenders[/{tenderId}]` | członek | lista wspólna dla organizacji; PUT `{status?, notes?}` – idempotentne |
| GET/POST/PUT/PATCH/DELETE | `/api/v1/organizations/{id}/saved-searches[/{sid}]`, `POST …/{sid}/run` | członek (tylko własne) | filtry walidowane jak w wyszukiwarce |
| GET/POST | `/api/v1/admin/users`, `/users/{id}/lock`, `/unlock`, `/audit-logs`, `/data-sources`, `/ingestion-jobs`, `/email-outbox`, `/reference/cpv/import`, `/reference/exchange-rates/import` | `PLATFORM_ADMIN` | |
| POST | `/api/v1/admin/ted/ingest?from=YYYY-MM-DD&to=YYYY-MM-DD` | `PLATFORM_ADMIN` | import TED z okna dat (≤ 31 dni), 202 + `jobId`; 409 `job_already_running` |
| GET/PUT | `/api/v1/organizations/{id}/company-profile?lang=` | GET: członek, PUT: `COMPANY_ADMIN` | 404 `profile_missing`; PUT tworzy lub zastępuje profil i przelicza dopasowania; 400 `invalid_value_range` |
| POST/DELETE | `/api/v1/organizations/{id}/company-profile/certifications[/{cid}]`, `/references[/{rid}]` | `COMPANY_ADMIN` | 409 `profile_missing`, jeśli profilu jeszcze nie ma |
| GET | `/api/v1/organizations/{id}/matches?minScore=40..100&page&size&lang` | członek | otwarte przetargi z wynikiem ≥ `minScore` (domyślnie 60), sortowane malejąco; każdy z `breakdown` |
| GET | `/api/v1/organizations/{id}/matches/{tenderId}?lang=` | członek | wyjaśnienie liczone na żywo; 404 `profile_missing` |
| POST | `/api/v1/admin/ingestion-jobs/{id}/retry` | `PLATFORM_ADMIN` | ponowienie zadania TED dla tego samego okna |

Kody błędów (`code` w problem+json): `validation_failed`, `unauthenticated`, `forbidden`, `not_found`, `invalid_credentials`,
`too_many_attempts`, `rate_limited` (+ `Retry-After`), `email_not_verified`, `account_disabled`, `invalid_token`, `invalid_session`,
`weak_password`, `last_admin`, `already_member`, `invitation_email_mismatch`, `csrf_header_missing`, `job_already_running`,
`invalid_window`, `window_too_large`, `not_retryable`, `invalid_filters`, `duplicate_name`, `too_many_saved_searches`, `profile_missing`, `invalid_value_range`, `internal_error`.

Wartości `changeKind` w historii zmian: `PRIOR_INFORMATION`, `COMPETITION`, `CHANGE`, `CANCELLATION`, `AWARD`, `MODIFICATION`, `OTHER`.
Wartości `procedureType` – kody TED/eForms: `open`, `restricted`, `neg-w-call`, `neg-wo-call`, `comp-dial`, `innovation`, `oth-single`, `oth-mult`.

## Plan endpointów (Etapy 3–7)

| Obszar | Endpointy |
|---|---|
| Użytkownik (RODO) | `DELETE /me`, `GET /me/export` |
| Przetargi | `GET /tenders/{id}/documents`, `POST /tenders/search/natural-language` |
| Firma | `/company-profile/documents` (Etap 5b) |
| AI | `POST /tenders/{id}/analyses`, `GET /analyses/{id}`, `POST /translations`, `POST /assistant/messages` (SSE) |
| Powiadomienia | `GET /notifications`, `POST /notifications/{id}/read`, `GET/PUT /notification-preferences` |
| Płatności | `GET /plans`, `POST /billing/checkout-session`, `POST /billing/portal-session`, `GET /billing/usage`, `POST /webhooks/stripe` (podpis) |
| Admin | `/admin/organizations`, `/admin/ai-usage` |
| Growth | `/admin/crm/*`, `/admin/campaigns/*`, `/public/reports/subscribe`, `/partners/me/*`, `/referrals/me` – patrz [growth-system.md](growth-system.md) |
