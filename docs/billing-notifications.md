# Powiadomienia, plany i płatności (Etap 6)

## 1. Powiadomienia

**Źródła** (skaner `NotificationScanner`, domyślnie co 5 min, blokada `pg_try_advisory_xact_lock` – działa jedna instancja naraz,
pozycja zapisana w `notification_cursors`; pierwsze uruchomienie zaczyna od „teraz”, bez powiadomień o historii):

| Typ | Kiedy | Domyślnie |
|---|---|---|
| `NEW_MATCH` | nowy (od ostatniego skanu) otwarty przetarg, opublikowany w ostatnich 14 dniach, z dopasowaniem ≥ progu osoby | w aplikacji + e-mail dziennie, próg 70 |
| `WATCHED_CHANGED` | nowa wersja obserwowanego przetargu (status WATCHING/PREPARING/SUBMITTED) ze zmianą terminu, wartości, waluty, adresu dokumentacji, statusu albo unieważnienie / wynik | w aplikacji + e-mail natychmiast |
| `DEADLINE_APPROACHING` | termin obserwowanego (WATCHING/PREPARING) otwartego przetargu za ≤ 3 dni (przesunięcie terminu = nowe przypomnienie) | w aplikacji + e-mail natychmiast |
| `COMPETITOR_WON` | obserwowany konkurent wygrał (nowe ogłoszenie o wyniku z ostatnich 30 dni) | w aplikacji + e-mail dziennie |
| `SAVED_SEARCH` | zapisane wyszukiwanie z włączonym alertem znalazło przetargi dodane od ostatniego sprawdzenia | w aplikacji; e-mail wg częstotliwości alertu |

* Odbiorcy: aktywni członkowie organizacji (obserwowane są wspólne dla zespołu; zapisane wyszukiwania – osobiste).
* Duplikaty: `UNIQUE (user_id, dedup_key)`. Dzięki temu każdy skan cofa się 15 min (wiersze z długich transakcji
  stają się widoczne później) bez ryzyka podwójnych powiadomień.
* Zmiana profilu firmy przelicza dopasowania wszystkich istniejących przetargów – to **nie** generuje powiadomień
  (te przetargi są na liście „Dopasowane przetargi”); powiadomienie dotyczy tylko przetargów nowych.
* Zmiany tytułu/opisu nie są zgłaszane (szum z korekt ogłoszeń).
* Retencja: powiadomienia starsze niż 180 dni są usuwane (`app.notifications.retention-days`).

**E-maile** (`NotificationMailer`): jeden e-mail na osobę i organizację, łączy wszystko, co jest „do wysłania”:

* `INSTANT` – przy najbliższym skanie (plan bez e-maili natychmiastowych → trafiają do podsumowania dziennego),
* `DAILY` – o 7:00 w strefie czasowej osoby (`NOTIFICATIONS_DIGEST_HOUR`), `WEEKLY` – w poniedziałki o 7:00,
* do 15 pozycji na sekcję + „…i jeszcze N” z linkiem,
* nigdy na niepotwierdzony adres (powiadomienia zostają w aplikacji),
* stopka z linkiem do ustawień i do wypisania; nagłówki `List-Unsubscribe` + `List-Unsubscribe-Post` (RFC 8058,
  wymagane przez Gmail/Yahoo dla wysyłek masowych).

**Wypisanie**: token = HMAC-SHA256 (klucz wyprowadzony z `JWT_SECRET`) z `userId.orgId`; nie da się go podrobić ani
przerobić na inne konto, nie wygasa. Strona `/unsubscribe?token=` wypisuje dopiero po kliknięciu przycisku (skanery
linków w systemach pocztowych nikogo nie wypiszą); klient poczty może wypisać „jednym kliknięciem” (POST).
Wypisanie ustawia e-mail = `OFF` dla wszystkich typów i alertów wyszukiwań tej osoby w tej organizacji.

Podstawa prawna: powiadomienia o własnych ustawieniach użytkownika są częścią usługi (art. 6 ust. 1 lit. b RODO),
z możliwością wyłączenia w każdej chwili. To nie jest marketing – kampanie e-mail (moduł growth) mają osobne zgody.

## 2. Plany i limity

Jedno źródło prawdy: `billing/Plan.java`. Limity egzekwuje backend (frontend tylko je pokazuje), błąd `402` z kodem,
limitem i wykorzystaniem:

| | FREE | STARTER | PRO | BUSINESS | ENTERPRISE |
|---|---|---|---|---|---|
| Użytkownicy (członkowie + otwarte zaproszenia) | 1 | 3 | 10 | 25 | bez limitu |
| Zapisane wyszukiwania na osobę | 3 | 25 | 100 | 100 | 100 |
| Analizy AI / mies. | 2 | 30 | 100 | 300 | 1000 |
| Eksporty CSV/XLSX / mies. | 3 | 100 | 500 | 2000 | bez limitu |
| E-maile natychmiastowe | – | ✓ | ✓ | ✓ | ✓ |
| Zakup online | – | ✓ | ✓ | ✓ | umowa (admin) |

Wartości limitów to propozycja startowa do decyzji biznesowej – zmienia się je w jednym miejscu. Ceny **nie** są w
kodzie: pochodzą ze Stripe (identyfikatory cen w konfiguracji), więc aplikacja nigdy nie pokaże innej kwoty niż ta,
którą Stripe pobierze. Po obniżeniu planu nic nie jest usuwane – blokowane jest tylko dodawanie ponad limit.

Sprawdzenia blokują wiersz organizacji (`for update`), więc dwa równoległe żądania nie zajmą ostatniego miejsca.
Eksporty: licznik `usage_counters` zwiększany jednym `UPDATE … WHERE value < limit`.

## 3. Stripe

* **Checkout** (tylko `COMPANY_ADMIN`): klient Stripe tworzony raz na organizację (blokada wiersza + klucz
  idempotencji), subskrypcja z metadanymi `organization_id`, wymagany adres, zbieranie NIP (`tax_id_collection`),
  Stripe Tax (`automatic_tax`), kody promocyjne, powrót na `/billing?checkout=success|cancelled`.
  Powrót z Checkout **niczego nie przyznaje** – plan zmienia wyłącznie webhook.
* **Customer Portal**: zmiana planu (proporcjonalne rozliczenie po stronie Stripe), karty, anulowanie, faktury.
  Gdy organizacja ma aktywną subskrypcję, drugiego Checkout nie da się otworzyć (409 `subscription_exists`).
* **Webhook** `POST /api/v1/webhooks/stripe`: weryfikacja `Stripe-Signature` (tolerancja 5 min – ochrona przed
  powtórzeniem), idempotencja po `event.id` (`stripe_events`, w tej samej transakcji co zmiany – przy błędzie Stripe
  ponawia dostawę), a stan subskrypcji/faktury jest **pobierany na nowo ze Stripe** (zdarzenia mogą przyjść
  w złej kolejności). Obsługiwane: `checkout.session.completed`, `customer.subscription.created|updated|deleted|paused|resumed`,
  `invoice.finalized|paid|payment_succeeded|payment_failed|voided|marked_uncollectible`.
* **Plan organizacji** = najlepsza subskrypcja w statusie `active`, `trialing` lub `past_due` (Stripe ponawia
  płatność – plan zostaje); inaczej FREE. Cena spoza konfiguracji → brak planu + ostrzeżenie w logu. Plan `MANUAL`
  (umowa, nadany przez administratora platformy) nie jest nadpisywany przez Stripe; `AUTO` przywraca plan wg Stripe.
* Nieudana płatność → e-mail do administratorów organizacji + audyt `PAYMENT_FAILED`.
* Usunięcie konta jest blokowane (409 `cancel_subscription_first`), gdy usunięta zostałaby organizacja z płatną
  subskrypcją, która nie jest anulowana na koniec okresu – inaczej Stripe dalej pobierałby opłaty.
* Klucz tajny tylko w zmiennej środowiskowej; w logach komunikaty Stripe z zamaskowanymi kluczami.

### Konfiguracja (konto Stripe – do zrobienia przez właściciela)

1. W Stripe (tryb testowy): produkty Starter/Pro/Business, ceny cykliczne miesięczne/roczne w PLN
   (opcjonalnie `currency_options` dla EUR/CZK), **tax behavior: exclusive** (cena netto + VAT).
2. Stripe Tax: aktywacja, rejestracja VAT (PL; dla sprzedaży w UE – OSS), kod podatkowy produktu
   „SaaS – business use”.
3. Customer Portal: włączyć zmianę planu (te same ceny), anulowanie na koniec okresu, faktury.
4. Webhook endpoint `https://<domena>/api/v1/webhooks/stripe` z wymienionymi zdarzeniami → `STRIPE_WEBHOOK_SECRET`.
5. Zmienne `STRIPE_*` z `.env.example`. Status integracji: `GET /api/v1/system/status` (`STRIPE`).
6. Test lokalny: `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe`, zakup kartą `4242 4242 4242 4242`,
   karta odrzucana `4000 0000 0000 0341` (nieudana płatność odnowienia).
7. Faktury w Polsce: od 2026 r. obowiązuje KSeF – faktury Stripe nie są wysyłane do KSeF; potrzebna integracja
   lub biuro rachunkowe (decyzja prawno-księgowa, poza zakresem kodu).

## 4. Co zostało przetestowane

* Testy integracyjne (PostgreSQL w kontenerze): limity, Checkout (atrapa bramki), podpis/powtórka/manipulacja
  webhooków, cykl życia subskrypcji (incomplete → active → upgrade → past_due → canceled), nieznane ceny i klienci,
  nieudana płatność z e-mailem, plan umowny, generowanie powiadomień, progi, e-maile i sloty czasowe, wypisanie,
  izolacja skrzynek między organizacjami.
* Prawdziwy kod SDK Stripe przeciw **stripe-mock** (oficjalna atrapa Stripe walidująca żądania wg OpenAPI):
  klient, Checkout, Portal, ceny, subskrypcje, faktury.
* Przejście w przeglądarce na lokalnej aplikacji ze stripe-mock: wybór planu → przekierowanie do Checkout →
  podpisany webhook → plan Starter aktywny, natychmiastowe e-maile dostępne w ustawieniach; e-mail z powiadomieniami
  dostarczony do Mailpit z nagłówkami wypisania.
* **Nie przetestowano** z prawdziwym kontem Stripe (brak kluczy) – kroki w sekcji „Konfiguracja”.
