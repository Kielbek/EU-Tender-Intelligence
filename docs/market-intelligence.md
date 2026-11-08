# Wyniki przetargów, ceny i konkurencja

Odpowiada na pytania, które firma zadaje sobie przed złożeniem oferty: **kto wygrywa takie zamówienia, za ile i z iloma
konkurentami**. Wszystko pochodzi z opublikowanych ogłoszeń o wyniku (TED, BZP) – bez szacowania „szansy wygranej”.

## Dane

* `tender_results` – wiersz na zwycięzcę i opublikowane ogłoszenie (TED, BZP: zwycięzca, kwota, liczba ofert, data).
* To samo rozstrzygnięcie bywa publikowane kilka razy (TED i BZP, sprostowania, zmiany umowy), dlatego statystyki
  czytają zmaterializowane widoki (migracja `V16`):
  * `award_facts` – każde (przetarg, zwycięzca, kwota) liczone raz; zwycięzca kilku części z różnymi cenami ma je
    zsumowane;
  * `tender_award_summary` – jeden wiersz na rozstrzygnięty przetarg: łączna kwota (gdy znana dla wszystkich części),
    maks. liczba ofert, wartość szacunkowa, kraj, CPV, zamawiający.
* Widoki odświeża `MarketRefreshJob` co 30 min (`REFRESH … CONCURRENTLY`, blokada doradcza – jedna instancja);
  ręcznie `POST /api/v1/admin/market/refresh`. Strona przetargu pokazuje też rozstrzygnięcia bez duplikatów.

## Wskaźniki

| Wskaźnik | Jak liczony |
|---|---|
| Rozstrzygnięte postępowania | liczba przetargów z wynikiem w zakresie |
| Mediana liczby ofert, udział z jedną ofertą | tylko wartości 1–500 |
| Cena wygrywająca / wartość szacunkowa (kwartyle) | łączna kwota zwycięska ÷ wartość szacunkowa; tylko 0,1–2,0; **pomijane są przypadki równe 1,00 (±0,5%)** – wiele ogłoszeń o wyniku powtarza cenę jako „wartość szacunkową”, co sztucznie podnosiłoby medianę |
| Mediana wartości zamówienia | z kwot w EUR (kurs EBC z dnia publikacji); **TED – netto, BZP – ceny ofert brutto** |

**VAT:** w BZP „cena oferty wykonawcy” to cena brutto, a „wartość zamówienia” – netto (Prawo zamówień publicznych).
Iloraz brutto/netto zawyżałby wynik o ok. 23%, dlatego stosunek ceny do wartości szacunkowej liczony jest wyłącznie
z rozstrzygnięć opublikowanych w TED (`net_value`, migracja `V17`). Notka pod każdym widokiem to wyjaśnia.

Wskaźnik z mniej niż 5 postępowań nie jest pokazywany („za mało danych”). Zawsze widać liczbę postępowań, z których
policzono wynik, okres i zakres (kraj, CPV). Mediany są odporne na pojedyncze błędy w ogłoszeniach (np. kwota bez
przecinka w źródle) – samych kwot nie poprawiamy, pokazujemy je tak, jak opublikował zamawiający.

**Podobne przetargi** (panel „Rynek i konkurencja” na stronie przetargu): ten sam kraj i najbardziej szczegółowy prefiks
CPV (klasa 5 cyfr → grupa 4 → 3 → dział 2), który ma co najmniej 20 rozstrzygnięć w oknie czasowym. Plus osobno:
historia u tego samego zamawiającego.

## Ekrany i API

| Ekran | API |
|---|---|
| Panel na stronie przetargu | `GET /api/v1/organizations/{org}/market/tenders/{id}` |
| Profil zamawiającego `/authorities/:id` | `GET …/market/authorities/{id}` – ogłoszone, otwarte, unieważnione, konkurencja, kto wygrywa, co kupuje, otwarte przetargi |
| Profil firmy `/companies/:id` | `GET …/market/companies/{id}` – wygrane, łączna wartość, mediana ofert, główni zamawiający, branże, kraje |
| Konkurencja `/competitors` | `GET …/market/companies?q=`, `GET …/market/authorities?q=` (nazwa bez polskich znaków albo NIP/REGON w dowolnym zapisie), `GET/PUT/DELETE …/competitors[/{id}]` |

## Plany

| | FREE | STARTER | PRO | BUSINESS | ENTERPRISE |
|---|---|---|---|---|---|
| Historia wyników | 3 mies. | 24 mies. | 24 | 24 | 24 |
| Obserwowani konkurenci | 1 | 10 | 50 | 200 | 500 |

Ponad limit: `402 competitor_limit_reached`.

## Powiadomienie „Konkurent wygrał” (`COMPETITOR_WON`)

Skaner powiadomień tworzy je z nowych wierszy `tender_results` dla obserwowanych firm (członkowie organizacji,
preferencje jak przy innych typach, domyślnie e-mail dziennie). Tylko rozstrzygnięcia z ostatnich 30 dni – import
starszych ogłoszeń (backfill) nie zasypie nikogo historią; tylko wyniki dodane po rozpoczęciu obserwowania.

## Ograniczenia (świadome)

* Zasięg = zaimportowane ogłoszenia. TED: od uruchomienia importu; BZP: import historyczny po 31 dni na zadanie.
  Notka pod każdym widokiem pokazuje, od kiedy jest większość danych.
* Firmy i zamawiający są łączeni po numerze identyfikacyjnym, a bez niego po nazwie – oddziały (np. GDDKiA w różnych
  miastach) są osobnymi zamawiającymi; różne zapisy nazwy tej samej firmy bez NIP mogą dać dwa profile.
* API BZP blokuje klienta, który wysyła zbyt wiele zapytań (`403 Dostęp zablokowany`). Przerwa między stronami
  wynosi domyślnie 1,5 s (`BZP_PAUSE_BETWEEN_PAGES` w `app.bzp.pause-between-pages`), a ponowienie zadania
  (`POST /api/v1/admin/ingestion-jobs/{id}/retry`) wznawia od miejsca przerwania zamiast pobierać okno od nowa.
