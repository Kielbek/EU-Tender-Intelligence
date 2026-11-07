# API

* Bazowy prefiks: `/api/v1` (wersjonowanie w ścieżce; zmiany łamiące → `/api/v2`).
* Format: JSON, daty ISO 8601 w UTC, kwoty jako string dziesiętny + kod waluty ISO 4217.
* Błędy: RFC 9457 `application/problem+json` z polami `type`, `title`, `status`, `detail`, `correlationId` (+ `errors[]` dla walidacji).
* Każda odpowiedź ma nagłówek `X-Request-Id` (przekazany przez klienta, jeśli poprawny, lub wygenerowany).
* Specyfikacja OpenAPI generowana z kodu: `GET /v3/api-docs`, Swagger UI: `/swagger-ui.html` (wyłączane `API_DOCS_ENABLED=false`).

## Endpointy – Etap 1 `[ZAIMPLEMENTOWANE]`

| Metoda | Ścieżka | Dostęp | Opis |
|---|---|---|---|
| GET | `/api/v1/system/status` | publiczny | wersja, stan PostgreSQL (z wersją pgvector) i Redis, gotowość integracji (`READY` / `PENDING_CONFIGURATION` / `NOT_IMPLEMENTED`) |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | publiczny | sondy dla orkiestratora (bez szczegółów) |
| GET | `/actuator/prometheus` | **zablokowany** w Etapie 1 (`denyAll`) | metryki – udostępnione w Etapie 8 tylko w sieci wewnętrznej |

Każdy inny endpoint zwraca obecnie `403` (domyślne „deny all” do czasu wprowadzenia uwierzytelniania).

## Plan endpointów (Etapy 2–7)

| Obszar | Endpointy |
|---|---|
| Auth | `POST /auth/register`, `POST /auth/verify-email`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `POST /auth/password/forgot`, `POST /auth/password/reset` |
| Użytkownik | `GET/PATCH /me`, `DELETE /me` (RODO), `GET /me/export` |
| Organizacje | `GET/POST /organizations`, `GET/PATCH /organizations/{id}`, `GET/POST/DELETE /organizations/{id}/members`, `POST /organizations/{id}/invitations` |
| Słowniki | `GET /countries`, `GET /cpv?query=`, `GET /currencies` |
| Przetargi | `GET /tenders` (filtry, sortowanie, paginacja kursorowa), `GET /tenders/{id}`, `GET /tenders/{id}/versions`, `GET /tenders/{id}/documents`, `POST /tenders/search/natural-language`, `POST /tenders/export` (CSV/XLSX, limit planu) |
| Zapisane | `GET/POST/DELETE /saved-searches`, `GET/POST/PATCH/DELETE /saved-tenders` |
| Dashboard | `GET /dashboard/summary`, `GET /dashboard/top-matches` |
| Firma | `GET/PUT /company-profile`, CRUD `/company-profile/certifications`, `/references`, `/documents` |
| Dopasowanie | `GET /matches`, `GET /tenders/{id}/match` |
| AI | `POST /tenders/{id}/analyses`, `GET /analyses/{id}`, `POST /translations`, `POST /assistant/messages` (SSE) |
| Powiadomienia | `GET /notifications`, `POST /notifications/{id}/read`, `GET/PUT /notification-preferences` |
| Płatności | `GET /plans`, `POST /billing/checkout-session`, `POST /billing/portal-session`, `GET /billing/usage`, `POST /webhooks/stripe` (podpis) |
| Admin | `/admin/users`, `/admin/organizations`, `/admin/data-sources`, `/admin/ingestion-jobs` (+ `POST /{id}/retry`), `/admin/ai-usage`, `/admin/audit-logs` |
| Growth | `/admin/crm/*`, `/admin/campaigns/*`, `/public/reports/subscribe`, `/partners/me/*`, `/referrals/me` – patrz [growth-system.md](growth-system.md) |
