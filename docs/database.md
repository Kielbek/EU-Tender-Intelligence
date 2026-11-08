# Model bazy danych – PostgreSQL 16 + pgvector

> Status (Etap 3): **zaimplementowane** migracje `V1`–`V7` – rozszerzenia, kraje, tożsamość, organizacje, zaproszenia,
> audyt, outbox e-maili, waluty, kursy EBC, CPV, pełny schemat przetargów i ingestion, rejestr źródeł danych.
> Tabele prywatne firm (3.5), powiadomienia (3.6) i płatności (3.7) powstaną w etapach 5–6.
>
> `V7` (na podstawie prawdziwych danych TED): kraje spoza UE dodawane na żądanie, brak kluczy obcych do walut i CPV w tabelach
> przetargów (źródło jest autorytatywne, format pilnują CHECK-i), `latest_order_key`/`order_key` (kolejność ogłoszeń),
> rodzaje zmian `PRIOR_INFORMATION/COMPETITION/CHANGE/CANCELLATION/AWARD/MODIFICATION/OTHER`, jedno aktywne zadanie na źródło.
>
> Różnice względem projektu: kody CPV przechowywane jako 8 cyfr **bez cyfry kontrolnej** (tak jak w TED/eForms);
> `refresh_tokens.family_id` grupuje rotacje jednej sesji; `email_outbox` (transakcyjna kolejka e-maili) i
> `organization_invitations` dodane; izolacja tenantów na razie w warstwie serwisu (RLS dojdzie razem z tabelami prywatnymi w Etapie 5).

## 1. Konwencje

* Klucze główne: `uuid` (UUIDv7 generowany w aplikacji – sortowalny w czasie, bez wycieku liczności jak w `bigserial`).
  Wyjątek: słowniki (`countries`, `cpv_codes`, `currencies`) mają naturalne klucze tekstowe.
* Czas: zawsze `timestamptz` (UTC w bazie, konwersja do strefy użytkownika w UI). Terminy z ogłoszeń zapisywane
  z oryginalną strefą w osobnej kolumnie `*_tz` (np. `Europe/Berlin`), bo termin „12:00 czasu lokalnego zamawiającego” ma znaczenie prawne.
* Kwoty: `numeric(18,2)` + `currency char(3)` (ISO 4217). Dodatkowo `*_eur numeric(18,2)` wyliczane wg kursu EBC z dnia
  publikacji (tabela `exchange_rates`) – do filtrowania i sortowania między walutami. Oryginalna kwota nigdy nie jest nadpisywana.
* Teksty wielojęzyczne: oryginał + kod języka (`*_lang`); tłumaczenia w `translations` (nie nadpisujemy oryginału).
* Soft delete tylko tam, gdzie wymaga tego audyt/RODO (`deleted_at`); dane prywatne usuwane fizycznie na żądanie RODO.
* Każda tabela: `created_at`, `updated_at`; tabele zmienne: `version int` (optimistic locking).
* Dane prywatne organizacji: kolumna `organization_id NOT NULL` + polityka RLS.
* Rozszerzenia: `vector`, `pg_trgm`, `unaccent`, `citext`, `btree_gin`.

## 2. Diagram (uproszczony)

```
users ─┬─< organization_members >─ organizations ─┬─< company_profiles ─┬─< company_certifications
       │                                           │                    ├─< company_references
       │                                           │                    └─< company_documents
       │                                           ├─< subscriptions ─< payments
       │                                           ├─< saved_searches
       │                                           ├─< saved_tenders >──────────────┐
       │                                           ├─< tender_matches >─────────────┤
       │                                           └─< ai_analyses >────────────────┤
       ├─< notifications                                                            │
       └─< notification_preferences                                                 │
                                                                                    │
data_sources ─< tender_sources >─ tenders ◄─────────────────────────────────────────┘
      │                             │ ├─< tender_versions
      └─< ingestion_jobs            │ ├─< tender_lots
                                    │ ├─< tender_cpv >─ cpv_codes
                                    │ ├─< tender_documents ─< tender_document_chunks (vector)
                                    │ ├─< tender_requirements
                                    │ └─< tender_results ─ economic_operators
                                    └── contracting_authorities ─ countries
```

## 3. Tabele

### 3.1 Tożsamość i organizacje (Etap 2)

**users**
| kolumna | typ | uwagi |
|---|---|---|
| id | uuid PK | |
| email | citext UNIQUE NOT NULL | |
| password_hash | text | Argon2id; NULL dla kont SSO (przyszłość) |
| full_name | text | |
| locale | varchar(5) default 'pl' | język interfejsu |
| timezone | text default 'Europe/Warsaw' | |
| platform_role | text CHECK in ('USER','PLATFORM_ADMIN') | |
| email_verified_at | timestamptz | |
| status | text CHECK in ('ACTIVE','LOCKED','DELETED') | |
| failed_login_count | int | blokada konta |
| last_login_at | timestamptz | |

Tabele pomocnicze: `user_tokens` (hash tokenu, typ: `EMAIL_VERIFY`/`PASSWORD_RESET`/`INVITE`, `expires_at`, `used_at`),
`refresh_tokens` (hash, rodzina tokenów do wykrywania ponownego użycia, `revoked_at`, user agent, IP).

**organizations** – `id`, `name`, `slug UNIQUE`, `country_code FK`, `vat_id`, `billing_email`, `stripe_customer_id UNIQUE`, `plan_code`, `status`.

**organization_members** – `organization_id FK`, `user_id FK`, `role` (`COMPANY_ADMIN`/`TEAM_MEMBER`), `invited_by`, `joined_at`; `UNIQUE(organization_id, user_id)`.
Rola „User” ze specyfikacji = użytkownik bez organizacji lub w organizacji jednoosobowej (tworzona automatycznie przy rejestracji).

### 3.2 Słowniki

* **countries** – `code char(2) PK` (ISO 3166-1 alpha-2), `code3 char(3)` (TED używa alpha-3), `name_en`, `is_eu bool`, `default_currency`, `default_language`.
* **cpv_codes** – `code varchar(10) PK` (np. `45000000-7`), `parent_code`, `level`, `labels jsonb` (`{"pl": "...", "en": "..."}`). Źródło: oficjalny słownik CPV 2008 (UE).
* **currencies**, **exchange_rates** (`currency`, `rate_date`, `rate_to_eur`, źródło: EBC) – `UNIQUE(currency, rate_date)`.
* **nuts_codes** – regiony (NUTS 2021/2024) dla filtrów Region/Miasto.

### 3.3 Źródła danych i ingestion (Etap 3)

**data_sources**
| kolumna | typ | uwagi |
|---|---|---|
| id | uuid PK | |
| code | text UNIQUE | `TED`, `PL_EZAMOWIENIA`, `DE_OEFFENTLICHEVERGABE`, ... |
| country_code | char(2) NULL | NULL dla TED (cała UE) |
| adapter_type | text | klasa adaptera |
| status | text CHECK in ('ACTIVE','INACTIVE','ERROR','PENDING_CONFIGURATION') | wymaganie panelu admina |
| terms_url | text | link do warunków ponownego wykorzystania danych |
| terms_verified_at | timestamptz | kiedy ktoś sprawdził ToS |
| schedule_cron | text | konfigurowalny harmonogram |
| config | jsonb | parametry bez sekretów (sekrety w env) |
| last_success_at, last_error_at, last_error | | |

**ingestion_jobs** – `id`, `data_source_id FK`, `type` (`INCREMENTAL`/`BACKFILL`/`DOCUMENTS`), `status` (`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`PARTIAL`),
`window_from`, `window_to`, `cursor` (np. `iterationNextToken` TED), `fetched_count`, `created_count`, `updated_count`, `skipped_count`, `error_count`,
`errors jsonb` (próbka błędów), `started_at`, `finished_at`, `triggered_by` (user/cron). Indeks `(data_source_id, created_at desc)`.

**raw_notices** – surowe ogłoszenie przed normalizacją: `data_source_id`, `external_id`, `payload_hash sha256`, `payload jsonb` lub klucz S3 do XML, `fetched_at`.
`UNIQUE(data_source_id, external_id, payload_hash)` – deduplikacja identycznych pobrań. Pozwala na ponowną normalizację bez ponownego pobierania.

### 3.4 Przetargi

**contracting_authorities** – `id`, `name`, `normalized_name` (lower+unaccent – do deduplikacji), `national_id` (np. NIP/REGON, SIRET), `country_code`, `nuts_code`, `city`, `address`, `website`, `type` (BT-11), `activity` (BT-10).
Indeksy: `gin (normalized_name gin_trgm_ops)`, `UNIQUE(country_code, national_id) WHERE national_id IS NOT NULL`.

**tenders** – kanoniczny rekord postępowania (aktualny stan, scalony ze źródeł)
| kolumna | typ | uwagi |
|---|---|---|
| id | uuid PK | |
| procedure_key | text UNIQUE | klucz deduplikacji – patrz §4 |
| title, title_lang | text | |
| description | text | sanityzowany |
| country_code | char(2) FK | |
| nuts_code, city | | |
| contracting_authority_id | uuid FK | |
| reference_number | text | numer referencyjny zamawiającego |
| contract_type | text | `WORKS`/`SUPPLIES`/`SERVICES` |
| procedure_type | text | `OPEN`/`RESTRICTED`/`NEG_W_CALL`/... (eForms BT-105) |
| main_cpv | varchar(10) FK | |
| estimated_value, currency, estimated_value_eur | numeric/char | |
| published_at | timestamptz | |
| submission_deadline | timestamptz | |
| submission_deadline_tz | text | |
| opening_at | timestamptz | |
| status | text | `PLANNED`/`OPEN`/`CLOSED`/`AWARDED`/`CANCELLED`/`UNKNOWN` |
| notice_language | char(2) | |
| original_url | text | link do oryginału |
| documents_url | text | BT-15 |
| participation_conditions, award_criteria | jsonb | ustrukturyzowane z ogłoszenia (nie z AI) |
| search_vector | tsvector | generowany |
| embedding | vector(1536) | wymiar zależny od modelu – migracja przy zmianie |
| current_version_no | int | |
| last_changed_at | timestamptz | |

Indeksy: `(country_code, status, submission_deadline)`, `(published_at desc)`, `(main_cpv)`, `(estimated_value_eur)`,
`gin(search_vector)`, `hnsw(embedding vector_cosine_ops)`, częściowy `(submission_deadline) WHERE status='OPEN'`.
Przy dużym wolumenie: partycjonowanie `tender_versions` po roku publikacji.

**tender_sources** – powiązanie przetargu ze źródłami (ogłoszenie może być w TED i w portalu krajowym):
`tender_id FK`, `data_source_id FK`, `external_id` (np. TED `publication-number` `00123456-2025`), `external_url`, `notice_type`, `first_seen_at`, `last_seen_at`;
**`UNIQUE(data_source_id, external_id)`** – oryginalne identyfikatory przechowywane zawsze.

**tender_versions** – każda opublikowana wersja/ogłoszenie (ogłoszenie, sprostowanie, zmiana, anulowanie, wynik):
`tender_id`, `version_no`, `tender_source_id`, `notice_type` (eForms: `cn-standard`, `can-standard`, `corr`, ...), `change_kind` (`INITIAL`/`CORRIGENDUM`/`MODIFICATION`/`CANCELLATION`/`AWARD`),
`snapshot jsonb` (znormalizowany stan), `diff jsonb` (zmienione pola: stara/nowa wartość – „Historia zmian”), `published_at`. `UNIQUE(tender_id, version_no)`.

**tender_lots** – części zamówienia (eForms LOT): `tender_id`, `lot_number`, `title`, `estimated_value`, `cpv`, `submission_deadline`.

**tender_cpv** – `tender_id`, `cpv_code`, `is_main`; PK `(tender_id, cpv_code)`.

**tender_documents** – `id`, `tender_id`, `source_url`, `file_name`, `mime_type`, `size_bytes`, `sha256`, `storage_key` (S3), `language`,
`fetch_status` (`PENDING`/`FETCHED`/`FAILED`/`NOT_ACCESSIBLE`/`REQUIRES_LOGIN`), `extraction_status`, `ocr_used bool`, `page_count`, `version_no`.
`UNIQUE(tender_id, sha256)`; zmiana dokumentacji = nowy rekord z nowym hashem → powiadomienie.

**tender_document_chunks** – `id`, `document_id FK ON DELETE CASCADE`, `chunk_index`, `page_from`, `page_to`, `section_path`, `content`, `content_lang`,
`token_count`, `embedding vector(1536)`, `tsv tsvector`. Indeksy: `hnsw(embedding)`, `gin(tsv)`, `(document_id, chunk_index) UNIQUE`.

**tender_requirements** – wynik ekstrakcji (AI lub ustrukturyzowane dane ogłoszenia):
`tender_id`, `category` (12 kategorii z modułu D), `text`, `normalized jsonb` (np. `{"type":"turnover","min":1000000,"currency":"EUR"}`),
`source_kind` (`NOTICE`/`DOCUMENT_AI`), `document_id`, `page`, `section`, `quote`, `quote_verified bool`, `analysis_id`, `language`.

**tender_results** – wyniki postępowania: `tender_id`, `lot_id`, `winner_id FK economic_operators`, `awarded_value`, `currency`, `tenders_received`, `lowest_offer`, `highest_offer`, `award_date`, `tender_source_id` (pochodzenie danych).
**economic_operators** – wykonawcy: nazwa, `normalized_name`, `national_id`, `country_code`, `sme bool`.

### 3.5 Prywatne dane organizacji (RLS)

* **company_profiles** – `organization_id UNIQUE`, `name`, `registration_country`, `industry`, `description`, `website`, `countries_of_interest char(2)[]`,
  `size` (`MICRO`/`SMALL`/`MEDIUM`/`LARGE`), `annual_revenue`, `revenue_currency`, `years_of_experience`, `technical_resources`, `staff_resources`,
  `financial_capacity jsonb`, `embedding vector` (profil do dopasowań semantycznych).
* **company_cpv** – `company_profile_id`, `cpv_code`, `include_children bool`.
* **company_certifications** – nazwa (ISO 9001 itd.), `issuer`, `valid_until`, `document_id`.
* **company_references** – zrealizowane kontrakty: `title`, `client`, `country`, `value`, `currency`, `year_completed`, `cpv_codes`, `description`.
* **company_documents** – `storage_key`, `sha256`, `mime_type`, `classification`, `uploaded_by` (prywatny bucket, skan AV).
* **saved_searches** – `organization_id`, `user_id`, `name`, `filters jsonb` (ten sam schemat co API wyszukiwania), `alert_enabled`, `alert_frequency`, `last_run_at`.
* **saved_tenders** – `organization_id`, `user_id`, `tender_id`, `status` (`WATCHING`/`PREPARING`/`SUBMITTED`/`WON`/`LOST`/`DROPPED`), `notes`; `UNIQUE(organization_id, user_id, tender_id)`.
* **tender_matches** – `organization_id`, `tender_id`, `score smallint CHECK 0..100`, `breakdown jsonb` (składowe wyniku), `met jsonb`, `unmet jsonb`, `missing_info jsonb`,
  `documents_to_prepare jsonb`, `engine_version`, `computed_at`; `UNIQUE(organization_id, tender_id)`, indeks `(organization_id, score desc)`.
* **ai_analyses** – `organization_id`, `tender_id`, `requested_by`, `status`, `language`, `model`, `prompt_version`, `result jsonb`, `input_tokens`, `output_tokens`, `cost_eur`, `error`.
* **translations** – `entity_type`, `entity_id`, `field`, `source_lang`, `target_lang`, `content`, `provider`, `is_machine bool default true`, `source_hash` – cache tłumaczeń (dane publiczne współdzielone).

### 3.6 Powiadomienia (Etap 6)

* **notification_preferences** – `user_id`, `organization_id`, `event_type` (`NEW_MATCH`/`DEADLINE_CHANGED`/`DOCS_CHANGED`/`CANCELLED`/`RESULT_PUBLISHED`/`DEADLINE_APPROACHING`),
  `channel` (`EMAIL`/`IN_APP`/`PUSH`), `frequency` (`INSTANT`/`DAILY`/`WEEKLY`/`OFF`), `min_score`; `UNIQUE(user_id, organization_id, event_type, channel)`.
* **notifications** – `user_id`, `organization_id`, `type`, `tender_id`, `payload jsonb`, `read_at`, `sent_email_at`, `dedup_key UNIQUE` (brak duplikatów alertów).

### 3.7 Płatności (Etap 6)

* **plans** – `code` (`FREE`/`STARTER`/`PRO`/`BUSINESS`/`ENTERPRISE`), `stripe_price_ids jsonb` (per waluta/okres), `limits jsonb`, `features jsonb`.
* **subscriptions** – `organization_id`, `plan_code`, `stripe_subscription_id UNIQUE`, `status` (odwzorowanie statusów Stripe), `current_period_start/end`, `cancel_at_period_end`, `trial_end`, `seats`.
* **payments** – `organization_id`, `stripe_invoice_id UNIQUE`, `stripe_payment_intent_id`, `amount`, `currency`, `tax_amount`, `status`, `paid_at`, `refunded_amount`.
* **usage_counters** – `organization_id`, `metric` (`SEARCHES`/`AI_ANALYSES`/`AI_TOKENS`/`EXPORTS`/`DOCUMENT_PAGES`), `period_start`, `value`; `UNIQUE(organization_id, metric, period_start)` – atomowy `UPDATE ... SET value = value + 1 WHERE value < limit`.
* **stripe_events** – `event_id PK`, `type`, `processed_at` – idempotencja webhooków.

### 3.8 Audyt

**audit_logs** – `id`, `occurred_at`, `actor_user_id`, `organization_id`, `action`, `target_type`, `target_id`, `ip inet`, `user_agent`, `details jsonb`.
Tylko INSERT (rola aplikacyjna bez UPDATE/DELETE), partycjonowanie miesięczne, retencja konfigurowalna.

## 4. Deduplikacja przetargów (`procedure_key`)

Kolejność rozstrzygania, czy nowe ogłoszenie dotyczy istniejącego postępowania:

1. To samo źródło + ten sam `external_id` → ten sam rekord (aktualizacja).
2. eForms **Procedure Identifier (BT-04, UUID)** – wspólny dla wszystkich ogłoszeń jednego postępowania w TED; `procedure_key = 'eforms:' || BT-04`.
3. Ogłoszenia TED sprzed eForms: odwołanie do poprzedniej publikacji (`previous publication`) → łączenie łańcucha.
4. Portal krajowy vs TED: dopasowanie po (kraj, zamawiający znormalizowany/ID, numer referencyjny zamawiającego), a gdy brak –
   podobieństwo tytułu (trigram ≥ 0,85) + ten sam termin składania ofert ±1 dzień. Dopasowania „rozmyte” trafiają do kolejki
   weryfikacji w panelu admina zamiast automatycznego scalania.

## 5. Wersjonowanie i wykrywanie zmian

Każde nowe ogłoszenie dla istniejącego postępowania tworzy `tender_versions` z `diff` względem poprzedniego snapshotu.
Pola monitorowane: termin składania, wartość, status, dokumenty (hash), kryteria, warunki. Zmiana → zdarzenie domenowe
(`TenderDeadlineChanged`, `TenderCancelled`, `TenderDocumentsChanged`, `TenderAwarded`) → moduł powiadomień.

## 6. Moduł pozyskiwania klientów (growth)

Tabele opisane w [growth-system.md](growth-system.md#model-danych): `crm_companies`, `crm_contacts`, `crm_activities`, `crm_tasks`,
`contact_consents`, `suppression_list`, `email_campaigns`, `email_messages`, `message_templates`, `report_subscriptions`,
`partners`, `partner_commissions`, `referral_codes`, `referrals`, `referral_rewards`, `seo_pages`, `growth_goals`, `automation_rules`.
