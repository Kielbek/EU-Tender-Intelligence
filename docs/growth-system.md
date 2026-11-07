# System pozyskiwania klientów (growth) – analiza i projekt

> Status: **projekt** (G1). Repozytorium było puste, więc ten moduł jest projektowany jako część platformy
> (wspólny backend Spring Boot, baza, uwierzytelnianie, Stripe), a nie osobna aplikacja.

## 1. Model biznesowy – liczby

* Abonament bazowy: **249 zł netto / mies.** (odpowiada planowi STARTER; ceny w EUR/CZK ustawiane w Stripe per waluta).
* Cel: 20 000 zł MRR → **81 płacących klientów** (81 × 249 = 20 169 zł).
* Lejek startowy (cele edytowalne w panelu): 100 leadów/mies. → 20 rozmów → 10 prezentacji → 5 klientów.
  Przy 5 nowych klientach i churnie 3%/mies. dojście do 81 klientów zajmuje ok. 2 lata; aby skrócić do ~12 mies. potrzeba ok. 8–9 nowych klientów/mies.
  – dlatego darmowy raport i SEO (kanały skalowalne) mają priorytet nad ręcznym outreachem.

## 2. Kanały i moduły

| Kanał | Moduł | Co automatyzujemy | Czego **nie** automatyzujemy |
|---|---|---|---|
| LinkedIn | `growth.linkedin` | rejestr kontaktów (URL profilu, stanowisko), szablony 3-etapowe PL/EN/DE/CZ z personalizacją, przypomnienia o kolejnym kroku, konwersje | wysyłki wiadomości, scrapingu, zaproszeń – sprzedawca kopiuje wiadomość i wysyła ręcznie (regulamin LinkedIn) |
| E-mail | `growth.email` | segmentacja, kampanie, 7 szablonów, personalizacja, rejestr odpowiedzi, wypisy, statystyki | wysyłki do kontaktów bez podstawy prawnej – system blokuje |
| Darmowy raport | `growth.reports` | landing page, double opt-in, cotygodniowy raport z prawdziwych przetargów, streszczenia AI ze źródłem, UTM | – |
| Partnerzy | `growth.partners` | baza partnerów, umowy, przypisanie klientów, prowizje 15–25% od faktycznie opłaconych faktur, korekty przy zwrotach, panel partnera | wypłat – generujemy zestawienie do rozliczenia/faktury partnera |
| SEO | `growth.seo` | strony kraj × branża z danymi na żywo, sitemap, hreflang, canonical, schema.org, panel CMS | publikacji stron bez danych (min. liczba aktywnych przetargów jako warunek publikacji) |
| Polecenia | `growth.referrals` | kody, atrybucja rejestracji i zakupu, nagrody jako saldo klienta w Stripe, antyfraud | – |

## 3. Zgodność prawna (wbudowana w model, nie w dokumentację)

* **Podstawa prawna kontaktu** zapisywana przy każdym kontakcie: `CONSENT`, `LEGITIMATE_INTEREST` (z notatką testu równowagi), `CONTRACT`, `NONE`.
* **Reguła wysyłki per kraj** (`outreach_country_rules`): czy pierwszy e-mail B2B wymaga uprzedniej zgody. Wartości startowe ostrożne:
  PL – zgoda wymagana (Prawo komunikacji elektronicznej), DE – zgoda wymagana (UWG §7), pozostałe – do weryfikacji, domyślnie „wymagana”.
  Admin zmienia regułę po konsultacji prawnej.
* **Suppression list** (hash e-maila i domeny) sprawdzana przed każdą wysyłką; wypis jednym kliknięciem (`List-Unsubscribe` + `List-Unsubscribe-Post`).
* Rejestr sprzeciwów, eksport i usunięcie danych kontaktu, retencja nieaktywnych leadów (np. 24 mies.).
* Darmowy raport: double opt-in, zapis treści zgody i wersji regulaminu, IP i czas.

## 4. CRM – statusy i automatyzacje

Statusy: `NEW_LEAD → CONTACTED → REPLIED → INTERESTED → DEMO_SCHEDULED → TRIAL → PAID`, oraz `LOST`, `DO_NOT_CONTACT` (terminalny, wpisuje do suppression).

Automatyzacje (reguły `automation_rules`, każda z przełącznikiem włącz/wyłącz, logiem wykonań):

| Zdarzenie domenowe | Akcja |
|---|---|
| `UserRegistered` | utwórz/połącz `crm_company` + `crm_contact`, źródło z UTM / kodu polecenia / partnera |
| `ReportSubscribed` | lead ze źródłem `FREE_REPORT` |
| `TrialStarted` | status `TRIAL`, monitorowanie aktywności (logowania, wyszukiwania, alerty) |
| `TrialEnding` (−3 dni) | e-mail przypomnienia, **jeśli** komunikacja dozwolona |
| `SubscriptionActivated` (webhook Stripe) | status `PAID`, przeliczenie MRR |
| `ReferralConverted` | nagroda dla polecającego po pierwszej opłaconej fakturze |
| `SubscriptionCanceled` | status `LOST`/churn + powód z ankiety rezygnacji |
| `InvoicePaid` klienta partnera | naliczenie prowizji; `ChargeRefunded` → korekta |

## 5. Metryki (dashboard)

Liczone z prawdziwych danych (Stripe + CRM), nigdy wpisywane ręcznie:
leady, nowe kontakty, odpowiedzi, prezentacje, triale, nowe abonamenty, **MRR** (suma znormalizowanych do miesiąca aktywnych subskrypcji),
**churn** (utraceni klienci w okresie / klienci na początku okresu), **CAC** (koszty kanału wpisane przez admina / nowi klienci z kanału),
**LTV** (ARPA / churn), konwersja trial → paid. Atrybucja kanału: first-touch zapisany w `crm_companies.acquisition_source` + last-touch;
ranking kanałów po **przychodzie i retencji klientów**, nie po liczbie rejestracji.

## Model danych

| Tabela | Najważniejsze kolumny |
|---|---|
| `crm_companies` | name, country, industry, website, size, language, interests (CPV/kraje), acquisition_source, status, owner_user_id, organization_id (po rejestracji) |
| `crm_contacts` | company_id, full_name, job_title, email, phone, linkedin_url, language, legal_basis, legal_basis_note, data_source (skąd pozyskano – wymóg art. 14 RODO) |
| `contact_consents` | contact_id, channel, purpose, granted_at, withdrawn_at, text_version, ip, evidence |
| `suppression_list` | email_hash / domain_hash, reason, created_at |
| `crm_activities` | company_id, contact_id, channel (LINKEDIN/EMAIL/CALL/MEETING/NOTE), direction, template_id, content, occurred_at, user_id |
| `crm_tasks` | company_id, assignee, due_at, type, status |
| `message_templates` | channel, stage/purpose, language, subject, body (placeholdery `{{company}}`, `{{country}}`, `{{industry}}`, `{{first_name}}`, `{{example_tender}}`) |
| `email_campaigns`, `email_messages` | segment (filtry), template, status; per wiadomość: status, provider_message_id, opened/clicked/replied/bounced |
| `report_subscriptions` | email, industries, countries, language, frequency, confirmed_at, unsubscribed_at, utm |
| `partners`, `partner_agreements`, `partner_commissions` | stawka %, okres; prowizja per opłacona faktura (`stripe_invoice_id UNIQUE`), korekty |
| `referral_codes`, `referrals`, `referral_rewards` | kod, polecający, polecony, status (REGISTERED/PAID/REWARDED/REJECTED), powód odrzucenia (fraud) |
| `seo_pages` | slug, locale, country, cpv_group, title, meta_description, content (sekcje), status, min_tenders_to_publish, canonical, alternates |
| `growth_goals` | metryka, okres, wartość docelowa (edytowalne w panelu) |
| `automation_rules`, `automation_runs` | trigger, akcje, enabled; log wykonań |

## 6. Panel administratora – zakładki

Overview, Leads, LinkedIn, Email Campaigns, Free Reports, Partners, SEO Content, Referrals, Customers, Subscriptions, Analytics, Automations, Settings –
w Angularze jako lazy-loaded `features/admin/growth/*`, dostęp tylko dla `PLATFORM_ADMIN` (i przyszłej roli `SALES`).

## 7. Czego potrzebuję od właściciela produktu

* Konto Stripe (klucze test/live, Stripe Tax, ceny w PLN/EUR/CZK).
* Dostawca e-mail transakcyjnego i marketingowego (np. Postmark/Brevo/Amazon SES) + domena z SPF/DKIM/DMARC; osobna subdomena do outreachu.
* Klucz dostawcy LLM (OpenAI / Anthropic / inny) – region przetwarzania i DPA.
* Opinia prawna dot. reguł marketingu B2B w krajach docelowych (tabela `outreach_country_rules`).
* Teksty regulaminu, polityki prywatności, umowy partnerskiej.
* Google Search Console i narzędzie analityczne (preferowane bezcookie’owe, np. Plausible/Matomo self-hosted – mniej problemów z banerem zgód).
