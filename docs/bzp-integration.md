# Integracja z Biuletynem Zamówień Publicznych (BZP, e-Zamówienia)

Przetargi krajowe w Polsce (poniżej progów unijnych) są publikowane tylko w BZP. Te powyżej progów importujemy z TED.

## Podstawa prawna i dostęp

* Oficjalny „Regulamin korzystania z API” Prezesa UZP (zarządzenie nr 20/2022), §3 ust. 8: usługa API BZP
  (WebService) do pobierania danych z ogłoszeń krajowych jest **bezpłatna i nie wymaga wniosku dostępowego**.
  Dokument: https://media.ezamowienia.gov.pl/pod/2023/01/Regulamin-korzystania-z-API.pdf
* Endpoint: `GET https://ezamowienia.gov.pl/mo-board/api/v1/notice` (anonimowy). Wymagane parametry: `NoticeType`,
  `PublicationDateFrom`, `PublicationDateTo`; `PageSize` maks. 500.
* Do zrobienia po stronie operatora: potwierdzić z prawnikiem zasady ponownego wykorzystania informacji sektora
  publicznego (ustawa z 11.08.2021 r.) i wpisać datę weryfikacji w `data_sources.terms_verified_at`.

## Zweryfikowane na żywo (2026-11-05, dzień roboczy)

| Typ ogłoszenia | Liczba | Co z nim robimy |
|---|---|---|
| `ContractNotice` (ogłoszenie o zamówieniu) | ~650 | nowe postępowanie (OPEN do terminu) |
| `TenderResultNotice` (wynik) | ~580 | AWARDED z wykonawcami i cenami; CANCELLED przy unieważnieniu wszystkich części |
| `NoticeUpdateNotice` (zmiana) | ~320 | wersja zmiany; nowy termin z „8.1 Termin składania ofert … Po zmianie: …” |
| `AgreementIntentionNotice` (zamiar zawarcia umowy) | ~20–30 | DIRECT_AWARD |
| `SmallContractNotice` | kilka–kilkanaście | jak ogłoszenie o zamówieniu |
| `ContractPerformingNotice` (wykonanie umowy) | ~950 | **pomijamy** – nic dla oferentów |

Import 4 dni (05–08.11.2026): 3 195 ogłoszeń, 0 błędów, 2 513 postępowań (w tym ~1 300 otwartych w trybie podstawowym).

## Mapowanie

* Klucz postępowania: `bzp:` + identyfikator OCDS (`tenderId`, np. `ocds-148610-…`); kolejne ogłoszenia tego
  samego postępowania (zmiany, wynik) łączą się w jeden przetarg z historią wersji.
* Kolejność ogłoszeń: numer BZP (`2026/BZP 00523249/01` → rok, numer, wersja).
* Z JSON: tytuł, zamawiający (nazwa, NIP, miasto), kraj, rodzaj zamówienia, główny CPV, termin składania ofert (UTC),
  wykonawcy w wynikach.
* Z treści HTML (pola numerowane, stałe w ogłoszeniach): opis (4.2.2 / 4.5.1, z podziałem na części), wszystkie kody
  CPV, NUTS 3, numer referencyjny, adres strony postępowania (3.1 – dokumenty), tryb („tryb podstawowy” → `pl-basic`,
  przetarg nieograniczony → `open` …), wartości części i ceny w wynikach (format polski „550088,89 PLN”), liczba ofert.
* Szacunkowa wartość zamówienia **nie jest publikowana** w ogłoszeniach o zamówieniu BZP – nie zgadujemy jej.
* Pełna treść ogłoszenia (bez stylów CSS) jest zapisywana w `raw_notices` i dołączana do analizy AI jako dokument
  „Ogłoszenie o zamówieniu (oficjalna treść)” – zawiera warunki udziału, kryteria i wadium.
* Zmiany do postępowań sprzed okna importu nie tworzą pustych przetargów (surowe ogłoszenie zostaje w `raw_notices`).

## Harmonogram i stronicowanie

* Co godzinę (`app.bzp.cron`, domyślnie `:35`), od ostatniego otrzymanego ogłoszenia każdego typu minus 10 minut
  (bez ponownego pobierania całego dnia). Przy pierwszym starcie: import z `BZP_INITIAL_BACKFILL_DAYS` dni.
* API sortuje po czasie publikacji; kolejna strona zaczyna się od czasu ostatniego ogłoszenia (włącznie), duplikaty
  odrzucamy po `objectId`. Gdy cała strona ma ten sam czas, czytamy ją ponownie stroną 500.
* Ręcznie: `POST /api/v1/admin/bzp/ingest?from=&to=` (maks. 31 dni), ponowienie: `POST /api/v1/admin/ingestion-jobs/{id}/retry`.
