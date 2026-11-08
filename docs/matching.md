# Dopasowanie przetargów do profilu firmy (wersja 2)

Wynik **0–100** pokazuje, jak bardzo przetarg pasuje do profilu firmy. **Nie jest to prawdopodobieństwo wygranej**:
wynik nie uwzględnia konkurencji, ceny ani warunków udziału z dokumentacji (o konkurencji i cenach mówi osobny panel –
[market-intelligence.md](market-intelligence.md)). Tę informację pokazujemy w interfejsie przy każdym wyniku.

Silnik jest regułowy i w pełni wyjaśnialny (`backend/.../matching/MatchingEngine.java`, `MatchingEngine.VERSION = 2`).
Każdy punkt ma przyczynę: kod (`CPV_MATCH`, `WITHIN_DISTANCE`, …) z parametrami, tłumaczony w interfejsie (`match.reason.*`).

## Składowe wyniku

| Składowa | Maks. | Reguła |
|---|---|---|
| Branża (CPV) | 35 | Kod główny przetargu w kodzie z profilu → 35. Pokrewne po wspólnym prefiksie istotnych cyfr: ≥ 5 → 28, 4 → 23, 3 → 17, 2 (ten sam dział) → 9. Kod dodatkowy (nie główny) – o 4 pkt mniej. Inna branża → 0. |
| Lokalizacja | 25 | Zob. niżej: odległość od siedziby, promień działania, wybrane województwa, dostawy w całym kraju, kraje. |
| Wartość | 15 | W zakresie → 15, powyżej maksimum → 0, poniżej minimum → 4; brak danych → 7. |
| Rodzaj zamówienia | 10 | Zgodny → 10, inny → 0; brak danych → 5. |
| Słowa kluczowe | 10 | Którekolwiek słowo firmy w tytule lub opisie → 10, żadne → 0; brak słów w profilu → 5. |
| Doświadczenie | 5 | Referencja z tego samego działu CPV → 5, tylko z innych → 1, brak → 0. |

**Brak danych nigdy nie liczy się jako dopasowanie** – daje część punktów i trafia na listę „Brakujące informacje”.

### Lokalizacja (najważniejsza zmiana wersji 2)

* **Miejsce przetargu** = kod NUTS 3 z ogłoszenia (99% otwartych przetargów w PL go ma). W BZP to lokalizacja
  zamawiającego (pole „Lokalizacja NUTS 3”), w TED – miejsce realizacji, a gdy go brak – zamawiającego.
* **Siedziba firmy** = podregion NUTS 3 wybrany w profilu (np. PL213 Miasto Kraków).
* **Odległość** = odległość po kuli ziemskiej między punktami podregionów z Eurostat GISCO
  (tabela `nuts_regions`, migracja `V18`; © EuroGeographics for the administrative boundaries). Ten sam podregion = 0 km
  („w Twoim podregionie”). To przybliżenie (podregion ma kilkadziesiąt km), ale odróżnia „pod Krakowem” od „Szczecina”.
* **Promień działania** (dotyczy robót i usług): do połowy promienia → 25 pkt, dalej liniowo do 40% na granicy,
  poza promieniem → 0. Bez promienia: ≤ 50 km → 25, ≤ 150 → 20, ≤ 300 → 14, dalej 9 (bliżej = lepiej).
* **Tryb „tylko mój obszar”** (`STRICT`): przetarg poza promieniem, wybranymi województwami lub krajami jest
  **wykluczony** – nie trafia do dopasowań ani powiadomień.
* **Dodatkowe województwa** (dowolny poziom NUTS, np. PL22 Śląskie) zawsze liczą się jako obszar firmy (oddziały).
* **Dostawy w całym kraju** (domyślnie włączone): promień nie dotyczy dostaw w kraju siedziby.
* **Kraje**: lista krajów z profilu; bez listy – kraj siedziby (lub rejestracji). Odległość działa też przez granicę
  (np. Szczecin – Görlitz), gdy kraj jest na liście.

### Cała Unia (i EOG)

* Regiony NUTS są dla wszystkich państw UE oraz NO, CH, IS, LI (Eurostat, 2148 regionów). Siedzibę wybiera się:
  kraj → region (NUTS 2) → podregion (NUTS 3); dodatkowe regiony – z kraju siedziby i krajów z listy firmy.
* Kody NUTS Grecji i Wielkiej Brytanii to `EL` i `UK`, a w ogłoszeniach (ISO) `GR` i `GB` – silnik je mapuje.
* Pokrycie kodem regionu otwartych przetargów (8.11.2026): PL 99%, DE 95%, FR 93%, ES 96%, IT 92%, CZ 92%, RO 100%,
  PT 97%, BE 82%, SE 79%, FI 65%, **NL 43%** – przetarg bez regionu liczy się tylko po kraju („brakujące informacje”).
* Sprawdzone na danych: Berlin 100 km → 173 dopasowania (najdalsze 48 km), Paryż 50 km → 178 (38 km),
  Ateny 200 km → 12 (175 km).
* W trybie `STRICT` przeliczanie czyta tylko przetargi z krajów firmy (szybciej przy dużych branżach).
* „Dostawy w całym kraju” działa tylko dla firm, które realizują dostawy (albo nie podały rodzajów zamówień).

**Czego jeszcze brakuje dla firm spoza Polski:** zamówienia krajowe poniżej progów UE mamy tylko z Polski (BZP);
z innych krajów wyłącznie z TED (zamówienia powyżej progów UE i część dobrowolnych publikacji). Interfejs jest po
polsku i angielsku.

### Wykluczenia (twarde reguły)

* **Wykluczone słowa** (np. „most”) w tytule lub opisie,
* **wykluczone kody CPV** (prefiksy, np. 45221 – mosty) jako kod główny,
* **poza obszarem** w trybie `STRICT`.

Wykluczony przetarg ma wynik 0 i flagę `excluded`, a wyjaśnienie na stronie przetargu mówi, która reguła zadziałała.

Słowa porównujemy bez wielkości liter i polskich znaków, **od początku wyrazu** („drog” znajdzie „drogowe”, „drogi”,
ale nie „podróże”). Zapis słów odbywa się przez tablice SQL sterownika (przecinki, cudzysłowy są bezpieczne).

### Czas na przygotowanie oferty

`min_days_to_deadline` (np. 7 dni): przetargi z bliższym terminem znikają z listy dopasowań, licznika na dashboardzie
i powiadomień o nowych dopasowaniach. Nie zmienia to wyniku (termin jest stały, czas płynie), dlatego filtrujemy przy odczycie.

## „Nie pasuje” – informacja zwrotna

Przy każdym dopasowaniu: **Nie pasuje** z powodem (za daleko, nie nasza branża, wartość, rodzaj, warunki, czas, inny).
Przetarg znika z dopasowań organizacji (można cofnąć), a powiadomienia go pomijają. Gdy jeden powód powtarza się
(≥ 3 razy w 90 dni), lista podpowiada konkretną zmianę profilu (np. ustawienie siedziby i promienia).
API: `PUT/DELETE …/matches/{tenderId}/dismissal`, `GET …/matches/dismissal-reasons`.

## Kiedy liczymy

* **Zmiana profilu** → przeliczenie wszystkich otwartych przetargów ze wspólnym działem CPV (w tle, kilka sekund).
* **Nowe lub zmienione ogłoszenie** → przeliczenie tego przetargu dla każdej organizacji z profilem.
* **Nowa wersja silnika** → przy starcie aplikacji przeliczane są organizacje z wynikami starszej wersji.
* W `tender_matches` zapisujemy wyniki ≥ 40 i niewykluczone; lista domyślnie pokazuje ≥ 60.

## Przykład na danych (8.11.2026, firma budowlana, CPV 45, próg 60)

| Ustawienia | Dopasowania |
|---|---|
| Bez siedziby | 1977 (cała Polska) |
| Siedziba Kraków, promień 100 km | 405, najdalsze ok. 64 km |
| + słowa „drog”, „nawierzchni”, bez „most”, min. 7 dni | 247 |

## Prywatność

Profil i dopasowania należą do organizacji; edytuje tylko `COMPANY_ADMIN`; zmiany w audycie (`COMPANY_PROFILE_UPDATED`).

## Ograniczenia

* Miejsce realizacji robót bywa inne niż siedziba zamawiającego (np. GDDKiA zleca prace w całym województwie);
  BZP podaje NUTS zamawiającego. Wybrane województwa i promień łagodzą ten problem.
* Wynik nie korzysta jeszcze z treści dokumentacji (warunki udziału). Karta „startujemy czy nie” połączy dopasowanie,
  analizę AI i konkurencję.
