# Analiza dokumentacji przetargowej z AI (Etap 5b)

Analiza czyta dokumenty przetargu i wypisuje: przedmiot zamówienia, terminy, warunki udziału, wymagane dokumenty,
kryteria oceny, wadium i zabezpieczenie, warunki umowy oraz ryzyka. **Każdy punkt ma cytat ze źródła** (dokument,
strona). Gdy dokumenty nie zawierają danej informacji, sekcja pokazuje „Nie znaleziono informacji w dostępnej dokumentacji”.

## Model i dostawca

* **Claude (Anthropic)** przez oficjalne Java SDK (`com.anthropic:anthropic-java`), klasa `AnthropicClaudeGateway`.
* Model domyślny: `claude-sonnet-5-5` (`AI_ANALYSIS_MODEL`), effort `medium` (`AI_ANALYSIS_EFFORT`), myślenie adaptacyjne,
  odpowiedź strumieniowana (długie wejście i wyjście).
* Włączony **server-side fallback** (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). Gdy klasyfikator
  bezpieczeństwa odrzuci zapytanie, API samo kieruje je do modelu zastępczego. Jeśli i to się nie uda, analiza kończy się
  kodem `ai_refused` i **nie zużywa limitu**.
* Klucz: `ANTHROPIC_API_KEY` (tylko zmienna środowiskowa, nigdy w kodzie). Pusty klucz wyłącza AI: endpointy zwracają
  `503 ai_not_configured`, a status systemu pokazuje `CLAUDE_API: PENDING_CONFIGURATION`.
* Sprawdzenie klucza na żywo: `POST /api/v1/admin/ai/check` (PLATFORM_ADMIN). To prawdziwe wywołanie API
  (pobranie opisu modelu), nie zużywa tokenów.

> **Status testów:** logika (dokumenty, limity, cache, cytaty, błędy) jest pokryta testami z atrapą klienta Claude,
> która zwraca odpowiedzi w kształcie prawdziwego API. **Wywołanie prawdziwego API nie zostało jeszcze wykonane**:
> w środowisku deweloperskim nie było klucza Anthropic. Po ustawieniu `ANTHROPIC_API_KEY` wykonaj `POST /api/v1/admin/ai/check`
> i jedną analizę testową, zanim udostępnisz funkcję klientom.

## Dokumenty

| Źródło | Widoczność | Jak trafia do systemu |
|---|---|---|
| Ogłoszenie (dane z TED) | wszyscy | Zawsze dołączane jako dokument tekstowy zbudowany z danych oficjalnego API TED (tytuł, zamawiający, CPV, wartość, terminy, opis, warunki udziału, kryteria, części). PDF ogłoszenia z ted.europa.eu jest chroniony weryfikacją przeglądarki (AWS WAF), więc go nie pobieramy. |
| Link „dokumenty zamówienia” z ogłoszenia | wszyscy | Na żądanie użytkownika (`POST …/documents/fetch-public`), **tylko gdy link prowadzi bezpośrednio do pliku**. Strona portalu (HTML), logowanie (401/403) i weryfikacja przeglądarki są zgłaszane jako powód i **nigdy nie są obchodzone**. Adresy w sieciach prywatnych są blokowane (SSRF). Ponowna próba dla tego samego przetargu jest możliwa najwcześniej po 6 godzinach. |
| Plik wgrany przez użytkownika | tylko jego organizacja | PDF, DOCX, DOC, ODT, RTF, TXT, XLSX, XLS, ODS lub ZIP z nimi, do 25 MB (`AI_MAX_UPLOAD_MB`). Typ rozpoznajemy po zawartości, nie po nazwie. ZIP: do 60 plików, bez zagnieżdżonych archiwów, do 150 MB po rozpakowaniu. |

* PDF trafia do Claude jako PDF (model czyta też układ i tabele). Pozostałe formaty zamieniamy na tekst (Apache Tika).
* **Skany bez warstwy tekstowej** dostają status `NO_TEXT_LAYER` i nie idą do analizy, bo API nie potrafi cytować obrazów.
  OCR (Tesseract) jest w planie.
* Pliki przechowujemy w PostgreSQL (`document_blobs`, deduplikacja po SHA-256), więc obejmuje je kopia zapasowa bazy.
  Przeniesienie do magazynu S3 nie zmieni API.

## Cytaty: dlaczego „zero niezweryfikowanych cytatów”

* Dokumenty wysyłamy z włączonymi cytatami Claude (`citations: {enabled: true}`). Pole `cited_text` w odpowiedzi
  **wyciąga z dokumentu samo API**. Model go nie generuje, więc cytat zawsze istnieje w źródle.
* `AnalysisParser` przypisuje cytaty do punktów. **Punkt bez cytatu jest odrzucany** (licznik `droppedUncited` w wyniku).
  Wyjątek: podsumowanie (SUMMARY) może być syntezą i jest wtedy oznaczone „synteza AI”.
* Strony w PDF podajemy 1-indeksowane (API zwraca koniec zakresu „exclusive”, przeliczamy go).

## Bezpieczeństwo: dokumenty to niezaufane dane

* System prompt (`AnalysisPrompt`) mówi wprost, że dokumenty to dane, a nie polecenia. Model ma ignorować
  instrukcje zawarte w dokumentach i nie zmieniać przez nie formatu odpowiedzi.
* Model **nie ma żadnych narzędzi**, więc nic, co napisze, nie uruchamia akcji w systemie. Wynik jest wyświetlany jako
  tekst (Angular escapuje HTML).
* Dokumenty organizacji nie trafiają do innych organizacji, ani jako pliki, ani przez analizy (analiza z prywatnymi
  plikami ma `organization_id` i widzi ją tylko ta organizacja).

## Koszty, cache i limity

* Analiza uruchamia się **tylko na żądanie** („Analizuj dokumentację”), nie dla każdego przetargu.
* Analiza dokumentów publicznych jest **współdzielona**: identyczny zestaw dokumentów (hash), język i wersja promptu
  oznaczają ten sam wynik. Ponowne otwarcie nie wywołuje modelu i nie zużywa limitu.
* Limity miesięczne (miesiąc kalendarzowy UTC) w `PlanLimits`: FREE 2, STARTER 30, PRO 100, BUSINESS 300, ENTERPRISE 1000.
  Przekroczenie zwraca `402 ai_quota_exceeded`. Nieudana lub odrzucona analiza nie zużywa limitu.
* Każde wywołanie trafia do `ai_usage` (tokeny, koszt w USD według cennika w `AiPricing`). Admin widzi koszt per
  organizacja: `GET /api/v1/admin/ai/usage`.
* Ograniczenia jednego zapytania: PDF łącznie do 22 MB i 550 stron, tekst do 600 tys. znaków. Dokumenty ponad limit są
  wypisane w wyniku jako pominięte (`skippedDocuments`).
* Szacunek: analiza dokumentacji ~100 tys. tokenów kosztuje na Sonnet 5.5 ok. 0,25 USD (wejście $2, wyjście $10 za 1 mln tokenów).

## Przebieg

1. `POST /api/v1/organizations/{org}/tenders/{id}/analyses?lang=pl`. Odpowiedź 202 (`PENDING`) albo 200 (gotowa,
   współdzielona analiza).
2. Analiza działa w tle (pula `ai-analysis`, `AI_WORKERS`). Analizy przerwane restartem są oznaczane `interrupted`
   i nie zużywają limitu.
3. UI odpytuje `GET …/analyses/latest?lang=pl` co 3 s. Gdy dokumenty zmieniły się od ostatniej analizy, wynik ma
   `stale: true` i UI proponuje ponowną analizę.

## Następne kroki (etap 5c)

OCR skanów, tłumaczenia tytułów i opisów (Haiku 5.5), wyszukiwanie w języku naturalnym, asystent AI (pytania do
dokumentów), embeddingi (lokalny model wielojęzyczny) i wyszukiwanie fragmentów dla bardzo dużych dokumentacji.
