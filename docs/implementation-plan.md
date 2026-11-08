# Plan implementacji

Plan łączy dwa dokumenty wymagań: **platformę przetargową** (etapy 1–8) i **system pozyskiwania klientów** (etapy G1–G8).
System sprzedażowy budujemy na istniejącej platformie (ten sam backend, baza, auth), dlatego zaczyna się po fundamentach
platformy – ale jego najprostsze elementy (formularz darmowego raportu, CRM) wchodzą wcześnie, bo generują leady zanim produkt jest kompletny.

## Co budujemy najpierw (i dlaczego)

1. **Dane TED + wyszukiwarka + szczegóły przetargu** – bez prawdziwych danych nic innego nie ma wartości.
2. **Konta, organizacje, plany FREE/STARTER z limitami** – od pierwszego dnia można zbierać użytkowników.
3. **Alerty e-mail z zapisanych wyszukiwań** – najtańsza funkcja o największej wartości postrzeganej (powód płacenia).
4. **Darmowy cotygodniowy raport** (lead magnet) – wykorzystuje te same dane i mechanizm alertów.
5. **Profil firmy + dopasowanie regułowe (CPV, kraj, wartość)** – zanim pojawi się AI.
6. **Analiza AI dokumentów z cytatami** – kluczowy wyróżnik, ale najdroższa i najbardziej ryzykowna – po ustabilizowaniu danych.

MVP sprzedażowe (pierwsi płacący klienci): punkty 1–5 + Stripe.

## Etapy platformy

| Etap | Zakres | Kryterium ukończenia |
|---|---|---|
| **1** ✅ | Analiza, architektura, struktura repo, Docker Compose (PostgreSQL+pgvector, Redis, MinIO, Mailpit), szkielet backendu (Spring Boot, Flyway, Actuator, Problem Details, correlation id, security baseline, endpoint statusu), szkielet frontendu (Angular, Tailwind, Transloco PL/EN, layout, ekran statusu), CI | `docker compose up` uruchamia całość; testy backendu i frontendu przechodzą w CI |
| **2** ✅ | Słowniki (kraje, CPV, waluty, kursy EBC), identity (rejestracja, weryfikacja e-mail, logowanie, refresh, reset hasła), organizacje i członkostwa, RBAC, audyt, rate limiting, modele przetargów + migracje, API odczytu przetargów | testy integracyjne auth i izolacji tenantów |
| **3** ✅ | Klient TED Search API, zadania przyrostowe i backfill, normalizacja eForms, deduplikacja, wersjonowanie + diff, zdarzenia zmian, rejestr źródeł i adapterów krajowych (`PENDING_CONFIGURATION`) | dane z ostatnich 30 dni w bazie; ponowne uruchomienie nie tworzy duplikatów |
| **4** ✅ | Frontend: auth, dashboard (prawdziwe statystyki), wyszukiwarka z filtrami, sortowaniem, paginacją, eksport CSV/XLSX, zapisane wyszukiwania, ulubione, szczegóły przetargu z historią zmian | e2e (Playwright) głównych ścieżek |
| **5a** ✅ | Profil firmy (zakres CPV, kraje, rodzaje zamówień, zakres wartości, zasoby, referencje, certyfikaty), wyjaśnialne dopasowanie 0–100 ([matching.md](matching.md)), lista dopasowanych przetargów, „Najlepsze przetargi” na dashboardzie, panel dopasowania na stronie przetargu | testy silnika i izolacji; przeliczanie na żywych danych TED |
| **5b** ✅ | Dokumenty przetargu (publiczny link – tylko bezpośrednie pliki, upload PDF/DOCX/…/ZIP), analiza AI (Claude) z cytatami ze źródeł, współdzielenie analiz, limity planów, licznik kosztów ([ai-analysis.md](ai-analysis.md)) | testy z atrapą API; **test z prawdziwym kluczem do wykonania** |
| **5b+** ✅ | Przetargi krajowe z BZP ([bzp-integration.md](bzp-integration.md)), przegląd kodu z poprawkami (SSRF, limity parsowania, wydajność dopasowań), RODO (eksport, usunięcie konta, regulamin, polityka prywatności), konfiguracja firmy po rejestracji (kroki na pełnym ekranie, z „Pomiń”), przewodnik „Pierwsze kroki”, stały import TED+BZP, kurs BGN | testy integracyjne na prawdziwych ogłoszeniach; import na żywo bez błędów |
| **5c** | OCR, tłumaczenia, wyszukiwanie w języku naturalnym, asystent AI, embeddingi | zestaw testów „złotych” analiz |
| **6** ✅ | Powiadomienia (in-app, e-mail, częstotliwości, wypisanie RFC 8058), plany i limity, Stripe Checkout + Customer Portal + webhooki, Stripe Tax ([billing-notifications.md](billing-notifications.md)) | testy integracyjne + SDK na stripe-mock; **test webhooków z kontem Stripe (Stripe CLI) do wykonania** |
| **7** | Panel administratora (użytkownicy, organizacje, źródła, zadania, ponawianie, zużycie AI, logi), asystent AI, analiza rynku i konkurencji, testy bezpieczeństwa (OWASP ZAP), testy wydajności (k6) | |
| **8** | Wdrożenie (Hetzner/AWS), TLS, backupy (pgBackRest/WAL-G + testy odtwarzania), monitoring (Prometheus/Grafana/alerty), dokumentacja operacyjna | odtworzenie backupu na czystym środowisku |

## Etapy systemu pozyskiwania klientów (szczegóły w [growth-system.md](growth-system.md))

| Etap | Zakres | Po którym etapie platformy |
|---|---|---|
| G1 | Analiza (zrobione w tym dokumencie) | 1 |
| G2 | Model danych CRM, zgody, suppression list | 2 |
| G3 | CRM: firmy, kontakty, statusy lejka, aktywności, zadania, automatyczne tworzenie rekordu przy rejestracji | 2 |
| G4 | LinkedIn (ręczny – szablony PL/EN/DE/CZ, historia, kolejne kroki) + e-mail (kampanie, szablony, personalizacja, wypisy) | 4 |
| G5 | Darmowy raport: landing page (SSR), formularz z double opt-in, generator cotygodniowy, mierzenie konwersji | 4 (dane) + 6 (e-mail) |
| G6 | Partnerzy (prowizje od faktycznych płatności, zwroty) i polecenia (kody, nagrody jako kredyt Stripe, antyfraud) | 6 (Stripe) |
| G7 | SEO CMS: strony kraj × branża z prawdziwymi danymi, sitemap, hreflang, canonical, dane strukturalne | 4 |
| G8 | Dashboard sprzedażowy (MRR, churn, CAC, LTV, konwersje, jakość kanałów), automatyzacje, cele edytowalne | 6 |

## Zasady pracy w każdym etapie

1. Migracje Flyway – nigdy nie edytujemy zastosowanej migracji.
2. Każdy endpoint: walidacja, autoryzacja, test integracyjny (Testcontainers), opis w OpenAPI.
3. Każdy ekran: stany ładowania (skeleton), pusty, błąd; tylko działające akcje.
4. Integracje bez kluczy/dostępu oznaczane jako „wymaga konfiguracji” – w UI i w `GET /api/v1/system/info`.
5. Po etapie: testy, aktualizacja dokumentacji, raport z tego, co zrobiono i czego brakuje.
