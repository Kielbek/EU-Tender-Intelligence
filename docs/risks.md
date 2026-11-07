# Ryzyka techniczne i biznesowe

Skala: P = prawdopodobieństwo, W = wpływ (N/Ś/W – niskie/średnie/wysokie).

## Techniczne

| # | Ryzyko | P | W | Mitygacja |
|---|---|---|---|---|
| T1 | Zmiany w TED API / formacie eForms (nowe wersje SDK, pola) | Ś | W | Warstwa `raw_notices` umożliwia ponowną normalizację; testy kontraktowe na zapisanych odpowiedziach; alert na wzrost błędów parsowania |
| T2 | Portale krajowe bez API lub z restrykcyjnymi warunkami | W | Ś | Adapter `PENDING_CONFIGURATION`, TED jako źródło bazowe, upload dokumentów przez użytkownika |
| T3 | Dokumenty przetargowe za logowaniem/CAPTCHA na platformach zamawiających | W | W | Nie omijamy zabezpieczeń; link + upload użytkownika; komunikacja w UI, że analiza AI wymaga dokumentów |
| T4 | Halucynacje AI w analizie (wymyślone wymagania/terminy) | Ś | W | Structured output, obowiązkowe cytaty, weryfikacja cytatu w chunku, „Nie znaleziono informacji…”, wskazanie źródła, testy regresyjne na zestawie złotych przypadków |
| T5 | Prompt injection w dokumentach przetargowych | Ś | Ś | Dokumenty tylko jako dane, brak narzędzi z efektami ubocznymi w analizie, walidacja schematu, izolacja per organizacja |
| T6 | Koszty LLM/OCR rosnące szybciej niż przychód | W | W | Limity per plan egzekwowane w backendzie, cache po hashu dokumentu, analiza na żądanie (nie dla każdego przetargu), tańszy model do klasyfikacji, licznik kosztów w panelu admina |
| T7 | Wolumen danych (setki tysięcy ogłoszeń rocznie, dokumenty w GB) | Ś | Ś | Indeksy, partycjonowanie wersji, dokumenty tylko dla przetargów z zainteresowaniem (obserwowane/dopasowane), lifecycle S3 |
| T8 | Jakość deduplikacji między TED a portalami krajowymi | Ś | Ś | Twarde klucze (BT-04), rozmyte dopasowania do ręcznej weryfikacji |
| T9 | Wyciek danych między organizacjami (IDOR) | N | W | Izolacja w 3 warstwach (serwis, Hibernate filter, RLS), testy integracyjne cross-tenant, audyt |
| T10 | Waluty i strefy czasowe (błędny termin = utracona oferta) | Ś | W | Przechowywanie oryginalnej strefy, prezentacja „czas zamawiającego + Twój czas”, ostrzeżenie „zawsze sprawdź w oryginalnym ogłoszeniu” |
| T11 | Dostarczalność e-maili (alerty, raporty) | Ś | Ś | Profesjonalny ESP, SPF/DKIM/DMARC, osobna domena dla outreachu, lista suppression |

## Biznesowe i prawne

| # | Ryzyko | P | W | Mitygacja |
|---|---|---|---|---|
| B1 | Silna konkurencja (agregatory przetargów w PL i UE) | W | W | Wyróżniki: analiza AI dokumentów z cytatami, dopasowanie z uzasadnieniem, tłumaczenia, rynek transgraniczny dla MŚP; nisza startowa (np. PL→DE/CZ, budownictwo/IT) |
| B2 | Odpowiedzialność za błędne informacje (np. zły termin) | Ś | W | Regulamin: informacja pomocnicza, wiążące jest oryginalne ogłoszenie; zawsze link do oryginału; oznaczanie tłumaczeń i analiz jako automatycznych |
| B3 | RODO – dane osobowe w ogłoszeniach (osoby kontaktowe) i w CRM/outreachu | Ś | W | Minimalizacja, podstawa prawna (prawnie uzasadniony interes B2B z testem równowagi), obsługa sprzeciwu, suppression list, DPA z podprocesorami, hosting w UE |
| B4 | Marketing elektroniczny – różne przepisy krajowe (PL: zgoda na komunikację elektroniczną w B2B; DE: UWG §7 – w praktyce zgoda) | W | W | Moduł zgód per kraj, domyślnie brak wysyłki bez podstawy prawnej, LinkedIn tylko ręcznie |
| B5 | Regulamin LinkedIn zakazuje automatyzacji/scrapingu | W | Ś | Brak automatycznej wysyłki i scrapingu – CRM wspiera ręczną pracę |
| B6 | Cel 81 klientów × 249 zł wymaga niskiego churnu; przetargi są sezonowe | Ś | W | Plany roczne z rabatem, wartość w alertach i raportach, onboarding z profilem firmy w 10 minut |
| B7 | Podatki przy sprzedaży międzynarodowej (VAT OSS, odwrotne obciążenie B2B) | Ś | Ś | Stripe Tax, walidacja VAT UE (VIES), faktury zgodne z KSeF dla PL (do analizy w Etapie 6) |
| B8 | AI Act – obowiązki przejrzystości | N | Ś | Oznaczenie treści generowanych przez AI, brak decyzji automatycznych o skutkach prawnych |
