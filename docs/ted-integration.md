# Integracja z TED i portalami krajowymi

> Status: **Etap 3 – zaimplementowane i przetestowane na prawdziwych danych.**
> Kod: `backend/src/main/java/eu/tenderintel/ingestion/` (wspólny zapis przetargów) i `.../ingestion/ted/` (adapter TED).

## 1. Źródło: TED Search API v3

* `POST https://api.ted.europa.eu/v3/notices/search` – anonimowo, bez klucza.
* Zapytanie: `publication-date>=YYYYMMDD AND publication-date<=YYYYMMDD`, `scope: ALL`, paginacja `ITERATION`
  (`iterationNextToken` – bez limitu głębokości, w przeciwieństwie do numerów stron).
* **Limit API: liczba pól × rozmiar strony ≤ 10 000.** Pobieramy 44 pola (`TedNoticeParser.FIELDS`), strona = 200
  ogłoszeń (rozmiar wyliczany automatycznie, żeby nigdy nie przekroczyć limitu).
* Ponawianie: 429 i 5xx – do 5 prób z wykładniczym odstępem (2, 4, 8, 16 s); inne 4xx – błąd od razu (ponowienie
  błędnego zapytania nic nie da). Pauza 0,3 s między stronami.
* Warunki ponownego wykorzystania: dokumentacja dla deweloperów TED opisuje API jako służące do „pobierania ogłoszeń do
  analizy lub ponownego wykorzystania”. Formalna nota prawna TED nie była możliwa do odczytania automatycznie –
  **operator musi ją zweryfikować** i ustawić `data_sources.terms_verified_at`. Każdy przetarg pokazuje źródło i link do oryginału.

### Zweryfikowane na danych (dzień 2026-11-06, 3 799 ogłoszeń)

| Obserwacja | Konsekwencja w kodzie |
|---|---|
| Ok. 3 800 ogłoszeń dziennie (dni robocze); soboty/niedziele – brak publikacji | 4 dni = 15 tys. ogłoszeń w ~3,5 min |
| eForms **nie ma osobnego sprostowania**: zmiana to ogłoszenie tej samej formy z `change-reason-code` / `change-notice-version-identifier` (558 z 1 924 ogłoszeń o zamówieniu) | `ChangeKind.CHANGE` |
| Anulowanie: `change-reason-code = cancel` lub `cancel-intent` | status `CANCELLED` |
| `form-type`: `planning`, `competition`, `result`, `dir-awa-pre` (VEAT), `cont-modif` | mapowanie na `FormType` |
| Pola-listy powtarzają wartości (po jednej na część/sekcję) | deduplikacja (CPV), zachowanie kolejności tam, gdzie liczy się wyrównanie (części, zwycięzcy) |
| Teksty to mapy `język ISO 639-2/T → wartość`; ogłoszenia dwujęzyczne (np. `swe` + `fin`) | język pierwszego tytułu; opis i zamawiający w tym samym języku |
| Termin = data `2026-11-13+02:00` + osobno godzina `15:00:00+02:00`, per część | `OffsetDateTime`, bez godziny → początek dnia (nigdy później niż prawdziwy termin) |
| Ogłoszenia wieloczęściowe z różnymi terminami (część już zamknięta w dniu publikacji) | termin efektywny = najbliższy przyszły termin części; po jego upływie automatycznie następny |
| Kraje spoza UE (NOR, CHE, ISL, MKD, MDA, COL, EGY, …) | kraj dodawany na żądanie z tablic ISO w JDK (`is_eu = false`) |
| Waluty spoza słownika (COP, MDL) | wartość zapisana, `estimated_value_eur` = null (brak kursu EBC) – bez zgadywania |
| 56 ogłoszeń bez `procedure-identifier` (BT-04) | klucz zapasowy `ted:<numer publikacji>` |
| Listy zwycięzców i kwot różnej długości (242 z ~1 500 ogłoszeń o wyniku) | zwycięzca zapisany **bez kwoty** – nigdy nie przypisujemy kwoty na ślepo |
| Wszystkie kody CPV z ogłoszeń (4 dni) istnieją w oficjalnym słowniku | — |

## 2. Potok

```
TedSearchClient (strona po stronie)
  → raw_notices (surowy JSON, dedup po hash – ponowna normalizacja po poprawkach parsera bez pobierania)
  → TedNoticeParser → NormalizedNotice (format niezależny od źródła)
  → TenderUpsertService (transakcja per ogłoszenie):
       pominięcie, jeśli (źródło, numer publikacji) już przetworzone
       rozpoznanie postępowania: procedure_key = eforms:<BT-04>
       zamawiający / wykonawcy: po identyfikatorze krajowym w kraju, inaczej po znormalizowanej nazwie
       przeliczenie na EUR kursem EBC z dnia publikacji (kurs nie starszy niż 10 dni)
       nowy przetarg  → tenders + wersja 1
       kolejne ogłoszenie → wersja n+1 z diffem pól; stan kanoniczny tylko jeśli ogłoszenie nowsze
       wyniki → tender_results + economic_operators
  → TenderIngestedEvent (dla dopasowań i powiadomień – Etapy 5–6)
```

* **Kolejność ogłoszeń:** numery publikacji TED są rosnące w roku (`771348-2026` → klucz `2026_00771348`). Stan
  przetargu odpowiada najnowszemu ogłoszeniu, także gdy starsze zostanie zaimportowane później (backfill) – wtedy trafia
  tylko do historii.
* **Scalanie:** nowe ogłoszenie nadpisuje pola, które zawiera; brakujących nie kasuje (ogłoszenie o wyniku nie ma terminu
  składania ofert – zostaje termin z ogłoszenia o zamówieniu).
* **Diff** (historia zmian): tytuł, opis (tylko fakt zmiany, bez kopiowania treści), status, termin, wartość, waluta,
  CPV (jako zbiór – kolejność nie jest zmianą), tryb, rodzaj, link do dokumentów, numer referencyjny.
* **Błędy izolowane:** wadliwe ogłoszenie nie zatrzymuje zadania – liczone w `error_count`, próbka w `errors`, zadanie
  kończy się jako `PARTIAL`.

## 3. Harmonogram i zadania

| Zadanie | Kiedy | Zakres |
|---|---|---|
| Przyrostowe | `0 20 6-21 * * MON-SAT` (czas brukselski), konfigurowalne `app.ted.cron` | od końca ostatniego udanego okna − 1 dzień do dziś |
| Początkowe | przy pierwszym starcie | ostatnie `TED_INITIAL_BACKFILL_DAYS` dni (domyślnie 3) |
| Ręczne | `POST /api/v1/admin/ted/ingest?from=&to=` | maks. 31 dni na zadanie |
| Ponowienie | `POST /api/v1/admin/ingestion-jobs/{id}/retry` | to samo okno dat |
| Zamykanie po terminie | co godzinę | OPEN → następny termin części lub CLOSED; bez terminu → CLOSED po 90 dniach |

* **Jedno aktywne zadanie na źródło** – unikalny indeks w bazie (działa także przy wielu instancjach); zadanie
  „zawieszone” > 6 h (np. po awarii) jest oznaczane jako FAILED.
* Status źródła: sukces → `ACTIVE`; 3 kolejne nieudane zadania → `ERROR` (widoczne w panelu admina).
* Postęp długich zadań widoczny na bieżąco w `GET /api/v1/admin/ingestion-jobs`.

### Wyniki testu na żywo (2026-11-08)

| Okno | Ogłoszenia | Utworzone | Zaktualizowane | Błędy | Czas |
|---|---|---|---|---|---|
| 2026-11-06 | 3 799 | 3 688 | 111 | 0 | 49 s |
| 2026-11-02 – 05 | 14 975 | 13 855 | 1 120 | 0 | 3 min 23 s |
| 2026-11-06 (ponownie) | 3 799 | 0 (3 799 pominiętych) | 0 | 0 | ~50 s |

Wyszukiwanie na 17,5 tys. przetargów: 24–170 ms (filtry, CPV, pełny tekst, sortowanie).

## 4. Znane ograniczenia

* **Przypisanie zwycięzców do części** w ogłoszeniach wieloczęściowych jest niejednoznaczne w płaskich polach Search API.
  Dokładne powiązanie wymaga parsowania XML eForms (`links.xml`) – planowane razem z analizą konkurencji (Etap 7).
* **Dokumenty przetargowe** nie są pobierane w tym etapie (tylko link `document-url-lot`); pobieranie publicznie
  dostępnych dokumentów, OCR i analiza – Etap 5. Nie omijamy logowania ani CAPTCHA na platformach zamawiających.
* Opisy z TED to zwykły tekst; Angular zawsze je escapuje przy wyświetlaniu.
* Bułgaria (euro od 2026-01-01): EBC nie publikuje już kursu BGN; starsze ogłoszenia w BGN pozostają bez przeliczenia na EUR,
  dopóki nie dodamy stałego kursu wymiany (do potwierdzenia w oficjalnym źródle przed wdrożeniem).

## 5. Adaptery krajowe

`TenderUpsertService` i `NormalizedNotice` są niezależne od źródła – adapter krajowy musi tylko pobrać ogłoszenia
i zamienić je na `NormalizedNotice`. Rejestr źródeł (`data_sources`) zawiera 10 krajów priorytetowych ze statusem
`PENDING_CONFIGURATION`; żaden nie jest jeszcze zweryfikowany (licencja danych, API/feed, limity).

| Priorytet | Kraj | Kandydat na źródło (do weryfikacji) |
|---|---|---|
| 1 | PL | Platforma e-Zamówienia / Biuletyn Zamówień Publicznych |
| 2 | DE | Datenservice Öffentlicher Einkauf (oeffentlichevergabe.de) |
| 3 | CZ | Věstník veřejných zakázek / NEN |
| 4 | FR | BOAMP (DILA) |
| 5 | NL | TenderNed |
| 6 | AT | data.gv.at |
| 7 | SK | ÚVO |
| 8 | SE | brak centralnego portalu publicznego – prawdopodobnie tylko TED |
| 9 | ES | Plataforma de Contratación del Sector Público |
| 10 | IT | ANAC – BDNCP |
