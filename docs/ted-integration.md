# Integracja z TED i portalami krajowymi

> Status: **projekt** (Etap 3). Implementacja jeszcze nie istnieje.
> **Zweryfikowane na żywo (Etap 2, 2026-11-08):** `POST https://api.ted.europa.eu/v3/notices/search` działa anonimowo.
> Pola `publication-number`, `notice-type` (np. `cn-standard`), `buyer-name` (mapa język → lista), `classification-cpv`
> (8 cyfr, bez cyfry kontrolnej), `deadline-receipt-tender-date-lot` (format `2026-11-13+01:00`), `procedure-identifier`
> (UUID – BT-04, klucz deduplikacji) i `links` (XML/PDF/HTML) istnieją. Pozostałe nazwy pól – do potwierdzenia testem kontraktowym w Etapie 3.

## 1. Źródła TED

| Źródło | Do czego | Uwagi |
|---|---|---|
| **TED Search API v3** `POST https://api.ted.europa.eu/v3/notices/search` | pobieranie przyrostowe, wyszukiwanie po polach | anonimowe (bez klucza) dla wyszukiwania; zapytanie w składni *expert query*; wybór zwracanych pól (`fields`) |
| **TED bulk download** (dzienne paczki XML, eForms) | backfill historii, pełne dane XML | pełna treść ogłoszenia, w tym pola niedostępne w Search API |
| **Pojedyncze ogłoszenie** (XML/PDF/HTML przez `links` w odpowiedzi) | pełny XML eForms do normalizacji | |
| Słownik CPV, kody NUTS, listy kodowe eForms (EU Vocabularies) | słowniki | |

Warunki: dane TED są udostępniane zgodnie z polityką ponownego wykorzystania informacji Komisji (decyzja 2011/833/UE) –
dopuszczone wykorzystanie komercyjne z podaniem źródła. Każde ogłoszenie w aplikacji pokazuje „Źródło: TED” i link do oryginału.
**Do zrobienia przed produkcją:** potwierdzić aktualne warunki i limity (fair use) na stronie TED i zapisać datę weryfikacji w `data_sources.terms_verified_at`.

## 2. Zapytanie przyrostowe

```json
POST /v3/notices/search
{
  "query": "publication-date>=20261106 AND publication-date<=20261107",
  "fields": ["publication-number", "notice-type", "publication-date", "notice-title",
             "buyer-name", "buyer-country", "classification-cpv", "procedure-type",
             "deadline-receipt-tender-date-lot", "estimated-value-lot", "links"],
  "limit": 250,
  "paginationMode": "ITERATION",
  "scope": "ALL"
}
```

* Okno czasowe = od ostatniej udanej daty publikacji (z zakładką 1 dnia, bo TED publikuje partiami) – idempotentne dzięki `UNIQUE(data_source_id, external_id)`.
* Paginacja: tryb `ITERATION` z tokenem `iterationNextToken` (tryb numerów stron ma limit głębokości). Token zapisywany w `ingestion_jobs.cursor`, więc przerwane zadanie wznawia się od miejsca przerwania.
* Dokładne nazwy pól (`fields`) i operatory należy zweryfikować z listą pól w dokumentacji / „Expert search” na ted.europa.eu – nazwy w przykładzie są do potwierdzenia testem kontraktowym.
* Klient HTTP: Spring `RestClient` z timeoutami, retry (Resilience4j) dla 429/5xx z wykładniczym backoffem i jitterem, limit współbieżności 1–2 żądań, nagłówek `User-Agent` identyfikujący aplikację.

## 3. Potok przetwarzania

```
fetch (Search API / XML)  →  raw_notices (surowe, hash)  →  parse (eForms XML / JSON)
  →  normalize (NormalizedNotice)  →  resolve procedure (dedup, §4 database.md)
  →  upsert tenders + tender_versions(diff) + tender_sources  →  events (nowy / zmiana / anulowanie / wynik)
  →  queue DocumentFetchJob (BT-15 documents URL)
```

* Parser eForms (UBL 2.3, SDK eForms) dla ogłoszeń od 10.2023; parser starego formatu (TED XML R2.0.9) tylko jeśli potrzebny backfill historii.
* Mapowanie typów ogłoszeń: Contract Notice → `INITIAL`, Corrigendum/Change → `CORRIGENDUM`, Contract Award Notice → `AWARD` (zasila `tender_results`),
  ogłoszenie o anulowaniu / brak udzielenia → `CANCELLATION`.
* Status: `OPEN` gdy termin w przyszłości, `CLOSED` po terminie, `AWARDED` po ogłoszeniu wyniku, `CANCELLED` – z ogłoszenia.

## 4. Dokumenty przetargowe

* TED zwykle **nie hostuje** dokumentacji – ogłoszenie zawiera URL (BT-15) do platformy zamawiającego.
* Pobieramy tylko dokumenty **publicznie dostępne bez logowania**, z poszanowaniem `robots.txt` i limitów; nie omijamy CAPTCHA,
  logowania ani innych zabezpieczeń. Gdy wymagane jest logowanie → `fetch_status = REQUIRES_LOGIN` i link dla użytkownika.
* Funkcja dodatkowa: użytkownik może sam wgrać dokumenty pobrane z platformy zamawiającego (prywatne dla jego organizacji) – to ścieżka główna dla portali wymagających rejestracji.

## 5. Adaptery krajowe

Interfejs (Java):

```java
public interface TenderSourceAdapter {
    String sourceCode();                       // np. "PL_EZAMOWIENIA"
    AdapterReadiness readiness();              // READY / PENDING_CONFIGURATION / NOT_IMPLEMENTED
    FetchResult fetch(FetchWindow window, Cursor cursor);   // strona surowych ogłoszeń + następny kursor
    NormalizedNotice normalize(RawNotice raw);
}
```

Adapter bez potwierdzonego, legalnego dostępu do danych istnieje tylko jako wpis w `data_sources` ze statusem `PENDING_CONFIGURATION`
i **nie** udaje działania. Wiele ogłoszeń krajowych powyżej progów UE i tak trafia do TED – krajowe adaptery dają głównie zamówienia podprogowe.

| Priorytet | Kraj | Kandydat na źródło (do weryfikacji ToS i API) | Status startowy |
|---|---|---|---|
| 1 | PL | Platforma e-Zamówienia / Biuletyn Zamówień Publicznych (UZP) | PENDING_CONFIGURATION |
| 2 | DE | Datenservice Öffentlicher Einkauf (oeffentlichevergabe.de) – otwarte dane eForms-DE | PENDING_CONFIGURATION |
| 3 | CZ | Věstník veřejných zakázek / NEN – otwarte dane ISVZ | PENDING_CONFIGURATION |
| 4 | FR | BOAMP – otwarte dane (DILA) | PENDING_CONFIGURATION |
| 5 | NL | TenderNed – otwarte dane | PENDING_CONFIGURATION |
| 6 | AT | data.gv.at – Kerndaten öffentlicher Ausschreibungen | PENDING_CONFIGURATION |
| 7 | SK | ÚVO – Vestník verejného obstarávania | PENDING_CONFIGURATION |
| 8 | SE | brak centralnego portalu publicznego; ogłoszenia w prywatnych bazach (licencje komercyjne) | PENDING_CONFIGURATION (prawdopodobnie tylko TED) |
| 9 | ES | Plataforma de Contratación del Sector Público – kanały ATOM open data | PENDING_CONFIGURATION |
| 10 | IT | ANAC – Banca Dati Nazionale dei Contratti Pubblici (open data) | PENDING_CONFIGURATION |

Kandydaci wynikają z wiedzy o rynku – **żaden nie został jeszcze zweryfikowany w tym projekcie**. Dla każdego przed implementacją:
licencja danych, dostępność API/feedu, limity, format, czy zawiera dokumenty, częstotliwość aktualizacji.

## 6. Monitorowanie

* `data_sources.status` przechodzi w `ERROR` po N kolejnych nieudanych zadaniach; alert do admina.
* Metryki: `ingestion_notices_total{source,result}`, opóźnienie świeżości (czas od publikacji w TED do pojawienia się w aplikacji), liczba błędów parsowania.
* Próbki nieparsowalnych ogłoszeń zapisywane do analizy (bez danych osobowych poza tym, co jest w publicznym ogłoszeniu).
