# Dopasowanie przetargów do profilu firmy (Etap 5a)

Wynik **0–100** pokazuje, jak bardzo przetarg pasuje do profilu firmy. **Nie jest to prawdopodobieństwo wygranej**:
wynik nie uwzględnia konkurencji, ceny ani warunków udziału z dokumentacji. Tę informację pokazujemy w interfejsie przy każdym wyniku.

Silnik jest regułowy i w pełni wyjaśnialny (`backend/.../matching/MatchingEngine.java`, wersja `MatchingEngine.VERSION = 1`).
Każdy punkt ma przyczynę: kod (`CPV_MATCH`, `VALUE_ABOVE_MAX`, …) z parametrami. Interfejs tłumaczy ją na PL/EN (`match.reason.*`).

## Składowe wyniku

| Składowa | Maks. | Reguła |
|---|---|---|
| Branża (CPV) | 45 | Kod przetargu (główny lub dodatkowy) mieści się w kodzie z profilu (np. 45000000 obejmuje 45214210) → 45. Kody pokrewne, liczone po wspólnym prefiksie istotnych cyfr: ≥ 5 cyfr → 36, 4 → 30, 3 → 22, 2 (ten sam dział) → 12. Inna branża → 0. |
| Lokalizacja | 20 | Kraj realizacji na liście krajów z profilu → 20, poza listą → 0. Bez listy: kraj rejestracji firmy → 20, w przeciwnym razie 10 i informacja o braku danych. |
| Wartość | 15 | Wartość szacunkowa w EUR w zakresie min–max → 15, powyżej maksimum → 0, poniżej minimum → 4. Brak wartości w ogłoszeniu lub brak zakresu w profilu → 7 i informacja o braku danych. |
| Rodzaj zamówienia | 10 | Roboty, dostawy lub usługi zgodne z profilem → 10, inne → 0. Brak danych → 5. |
| Doświadczenie | 10 | Referencja z kodem CPV z tego samego działu (pierwsze 2 cyfry) → 10. Referencje tylko z innych działów → 2. Brak referencji → 0. |

**Brak danych nigdy nie liczy się jako dopasowanie.** Daje połowę punktów składowej (albo 0 dla CPV i referencji) i trafia
na listę „Brakujące informacje”, żeby użytkownik wiedział, co uzupełnić.

## Kiedy liczymy

* **Zmiana profilu** (zapis, dodanie/usunięcie referencji lub certyfikatu) → `CompanyProfileChangedEvent` po zatwierdzeniu
  transakcji → przeliczenie wszystkich otwartych przetargów, które mają wspólny dział CPV z profilem.
  Test na żywych danych (17,5 tys. przetargów z TED): ok. 0,4 s, 1 750 zapisanych dopasowań.
* **Nowe lub zmienione ogłoszenie** (`TenderIngestedEvent`) → przeliczenie tego przetargu dla każdej organizacji z profilem.
  Anulowany lub zamknięty przetarg traci dopasowania.
* Obliczenia działają w jednym wątku w tle (`matching`), w kolejności zdarzeń. Import tysięcy ogłoszeń nie zabiera więc
  połączeń z bazą obsłudze zapytań użytkowników.
* W `tender_matches` zapisujemy wyniki ≥ 40 (`MatchingService.MIN_STORED`). Dashboard i lista domyślnie pokazują ≥ 60.
  Wyjaśnienie na stronie przetargu (`GET …/matches/{tenderId}`) liczymy na żywo, także poniżej progu i dla zamkniętych przetargów.

## Prywatność

Profil, referencje, certyfikaty i dopasowania należą do organizacji. Czytać je mogą tylko jej członkowie, a edytować tylko
`COMPANY_ADMIN`. Osoba spoza organizacji dostaje 404. Każda zmiana profilu trafia do audytu (`COMPANY_PROFILE_UPDATED`).

## Ograniczenia i plan

* Wynik nie korzysta z treści dokumentacji (warunki udziału, kryteria oceny). Przyjdą one z analizą AI w etapie 5b.
  Wtedy dodamy składową „warunki udziału” z cytatami i podniesiemy `VERSION`.
* Referencja z tego samego działu CPV (np. drogi i szkoły w dziale 45) liczy się jako doświadczenie w branży.
  Celowo przyjęliśmy szeroką regułę, bo zamawiający zwykle opisują doświadczenie ogólniej niż kodem CPV.
* Zmiana reguł = nowa wersja silnika (`engine_version` w każdym wierszu) i pełne przeliczenie.
