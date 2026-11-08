/**
 * Terms of service and privacy policy. They describe what the system actually does (data sources, AI analysis,
 * stored data, user rights implemented in the app). Operator details are inserted from the server configuration.
 * The texts are a draft to be reviewed by a lawyer before commercial launch (the pages say so).
 */
export interface LegalSection {
  heading: string;
  paragraphs: string[];
  /** Render the configured list of processors below the paragraphs. */
  processors?: boolean;
}

export interface LegalDoc {
  title: string;
  sections: LegalSection[];
}

export const LEGAL_TEXTS: Record<'pl' | 'en', Record<'terms' | 'privacy', LegalDoc>> = {
  pl: {
    terms: {
      title: 'Regulamin serwisu',
      sections: [
        {
          heading: 'Postanowienia ogólne',
          paragraphs: [
            'Regulamin określa zasady korzystania z serwisu EU Tender Intelligence – narzędzia do wyszukiwania i analizy zamówień publicznych. Usługodawcą jest operator wskazany powyżej.',
            'Korzystanie z serwisu wymaga konta i akceptacji regulaminu. Regulamin jest dostępny bezpłatnie w każdej chwili pod tym adresem.',
          ],
        },
        {
          heading: 'Konto i organizacja',
          paragraphs: [
            'Konto zakłada osoba fizyczna działająca w imieniu firmy. Każde konto należy do co najmniej jednej organizacji; administrator organizacji zarządza jej członkami i profilem firmy.',
            'Użytkownik odpowiada za poufność hasła. Konto można w każdej chwili usunąć w ustawieniach („Moje konto”), a dane pobrać w formacie JSON.',
          ],
        },
        {
          heading: 'Dane o przetargach',
          paragraphs: [
            'Ogłoszenia pochodzą z publicznych źródeł: TED (Dziennik Urzędowy UE) i Biuletynu Zamówień Publicznych (platforma e-Zamówienia). Serwis porządkuje je i przelicza wartości na euro według kursów EBC.',
            'Serwis nie gwarantuje kompletności ani bezbłędności danych. Wiążąca jest treść ogłoszenia i dokumentacji zamawiającego – w serwisie zawsze podajemy link do źródła.',
          ],
        },
        {
          heading: 'Dopasowanie i analiza AI',
          paragraphs: [
            'Wynik dopasowania (0–100) wskazuje zgodność przetargu z profilem firmy. Nie jest prawdopodobieństwem wygranej.',
            'Analiza dokumentacji wykonywana jest przez model sztucznej inteligencji (Claude, Anthropic) na żądanie użytkownika. Każdy punkt analizy ma cytat ze źródła, jednak analiza ma charakter pomocniczy i może zawierać błędy – przed złożeniem oferty należy sprawdzić dokumentację.',
          ],
        },
        {
          heading: 'Plany i limity',
          paragraphs: [
            'Dostępne funkcje i limity (np. liczba analiz AI w miesiącu) zależą od planu organizacji. Opłaty za plany płatne zostaną uruchomione wraz z modułem płatności; do tego czasu dostępny jest plan bezpłatny.',
          ],
        },
        {
          heading: 'Zasady korzystania',
          paragraphs: [
            'Zabronione jest obchodzenie limitów i zabezpieczeń, automatyczne pobieranie danych z serwisu poza udostępnionymi funkcjami eksportu oraz wgrywanie treści naruszających prawo lub prawa osób trzecich.',
            'Pliki wgrywane do analizy są widoczne wyłącznie dla członków organizacji, która je wgrała.',
          ],
        },
        {
          heading: 'Odpowiedzialność i reklamacje',
          paragraphs: [
            'Operator dokłada starań, aby serwis działał nieprzerwanie, ale nie odpowiada za decyzje podjęte wyłącznie na podstawie danych lub analiz z serwisu ani za przerwy w dostępności źródeł danych.',
            'Reklamacje można zgłaszać na adres e-mail operatora. Odpowiadamy w ciągu 14 dni.',
          ],
        },
        {
          heading: 'Zmiany regulaminu i prawo właściwe',
          paragraphs: [
            'O zmianach regulaminu informujemy z wyprzedzeniem. Wersja regulaminu zaakceptowana przy rejestracji jest zapisywana na koncie.',
            'Do regulaminu stosuje się prawo polskie.',
          ],
        },
      ],
    },
    privacy: {
      title: 'Polityka prywatności',
      sections: [
        {
          heading: 'Administrator danych',
          paragraphs: [
            'Administratorem danych osobowych użytkowników serwisu jest operator wskazany powyżej. W sprawach danych osobowych można pisać na podany adres e-mail.',
          ],
        },
        {
          heading: 'Jakie dane przetwarzamy',
          paragraphs: [
            'Dane konta: adres e-mail, imię i nazwisko, język i strefa czasowa, skrót hasła (samego hasła nie przechowujemy), daty logowania i akceptacji regulaminu.',
            'Dane bezpieczeństwa: adres IP i przeglądarka przy logowaniu oraz w dzienniku zdarzeń (np. zmiana hasła, eksport danych).',
            'Dane organizacji: profil firmy, obserwowane przetargi i notatki, zapisane wyszukiwania, wgrane dokumenty, historia analiz AI i zużycie limitów.',
            'Ogłoszenia o zamówieniach to dane publiczne. Mogą zawierać dane kontaktowe pracowników zamawiających – przetwarzamy je tak, jak zostały opublikowane w źródle.',
          ],
        },
        {
          heading: 'Cele i podstawy prawne',
          paragraphs: [
            'Świadczenie usługi na podstawie umowy (art. 6 ust. 1 lit. b RODO); bezpieczeństwo serwisu i dochodzenie roszczeń – prawnie uzasadniony interes (art. 6 ust. 1 lit. f); obowiązki prawne, np. rozliczenia – art. 6 ust. 1 lit. c.',
          ],
        },
        {
          heading: 'Odbiorcy danych',
          paragraphs: [
            'Korzystamy z dostawców, którzy przetwarzają dane w naszym imieniu na podstawie umów powierzenia. Dokumenty przetargu i treść ogłoszenia trafiają do dostawcy modelu AI wyłącznie wtedy, gdy użytkownik uruchomi analizę. Przekazanie danych poza EOG odbywa się na podstawie standardowych klauzul umownych lub decyzji o adekwatności.',
          ],
          processors: true,
        },
        {
          heading: 'Jak długo przechowujemy dane',
          paragraphs: [
            'Dane konta przechowujemy do jego usunięcia. Po usunięciu konta usuwamy dane osobowe i organizacje, których użytkownik był jedynym członkiem; wpisy dziennika bezpieczeństwa pozostają bez adresu IP i informacji o przeglądarce.',
            'Dane organizacji, w której pozostają inni członkowie, należą do organizacji i pozostają w niej.',
          ],
        },
        {
          heading: 'Twoje prawa',
          paragraphs: [
            'Masz prawo dostępu do danych i ich przenoszenia (w aplikacji: „Moje konto” → „Pobierz moje dane”), sprostowania (edycja profilu), usunięcia („Usuń konto”), ograniczenia przetwarzania i sprzeciwu, a także prawo skargi do Prezesa Urzędu Ochrony Danych Osobowych.',
          ],
        },
        {
          heading: 'Pliki cookie i pamięć przeglądarki',
          paragraphs: [
            'Używamy jednego niezbędnego pliku cookie (sesja logowania, HttpOnly) oraz pamięci przeglądarki dla ustawień interfejsu (np. wybrana organizacja). Nie używamy cookies reklamowych ani analitycznych firm trzecich.',
          ],
        },
      ],
    },
  },
  en: {
    terms: {
      title: 'Terms of service',
      sections: [
        {
          heading: 'General',
          paragraphs: [
            'These terms govern the use of EU Tender Intelligence, a tool for finding and analysing public procurement. The service is provided by the operator named above.',
            'Using the service requires an account and acceptance of these terms, which are available free of charge at this address at any time.',
          ],
        },
        {
          heading: 'Account and organization',
          paragraphs: [
            'An account is created by a person acting for a company. Every account belongs to at least one organization; its administrator manages members and the company profile.',
            'Users keep their password confidential. The account can be deleted at any time under “My account”, and the data downloaded as JSON.',
          ],
        },
        {
          heading: 'Tender data',
          paragraphs: [
            'Notices come from public sources: TED (Official Journal of the EU) and the Polish Public Procurement Bulletin (e-Zamówienia). The service organizes them and converts values to euro using ECB rates.',
            'Completeness and accuracy are not guaranteed. The notice and the authority’s documents are binding – the service always links to the source.',
          ],
        },
        {
          heading: 'Matching and AI analysis',
          paragraphs: [
            'The match score (0–100) shows how well a tender fits the company profile. It is not a probability of winning.',
            'Document analysis is performed on request by an AI model (Claude, Anthropic). Every point cites its source, but the analysis is an aid and may contain errors – check the documents before bidding.',
          ],
        },
        {
          heading: 'Plans and limits',
          paragraphs: [
            'Available features and limits (e.g. AI analyses per month) depend on the organization’s plan. Paid plans start with the billing module; until then the free plan is available.',
          ],
        },
        {
          heading: 'Acceptable use',
          paragraphs: [
            'Circumventing limits or security, automated harvesting of data beyond the export features, and uploading unlawful content or content infringing third-party rights are prohibited.',
            'Files uploaded for analysis are visible only to members of the organization that uploaded them.',
          ],
        },
        {
          heading: 'Liability and complaints',
          paragraphs: [
            'The operator strives for uninterrupted service but is not liable for decisions based solely on data or analyses from the service, or for outages of the data sources.',
            'Complaints can be sent to the operator’s e-mail address. We reply within 14 days.',
          ],
        },
        {
          heading: 'Changes and governing law',
          paragraphs: [
            'Changes to these terms are announced in advance. The version accepted at registration is recorded on the account.',
            'These terms are governed by Polish law.',
          ],
        },
      ],
    },
    privacy: {
      title: 'Privacy policy',
      sections: [
        {
          heading: 'Controller',
          paragraphs: ['The controller of users’ personal data is the operator named above, reachable at the e-mail address given.'],
        },
        {
          heading: 'Data we process',
          paragraphs: [
            'Account data: e-mail address, full name, language and time zone, a password hash (never the password), sign-in and terms-acceptance dates.',
            'Security data: IP address and browser at sign-in and in the event log (e.g. password change, data export).',
            'Organization data: company profile, watched tenders and notes, saved searches, uploaded documents, AI analysis history and usage.',
            'Procurement notices are public data. They may include contact details of authority staff – we process them as published by the source.',
          ],
        },
        {
          heading: 'Purposes and legal bases',
          paragraphs: [
            'Providing the service under the contract (Art. 6(1)(b) GDPR); security and legal claims – legitimate interest (Art. 6(1)(f)); legal obligations such as accounting – Art. 6(1)(c).',
          ],
        },
        {
          heading: 'Recipients',
          paragraphs: [
            'We use providers that process data on our behalf under data processing agreements. Tender documents and notice text are sent to the AI model provider only when a user starts an analysis. Transfers outside the EEA rely on standard contractual clauses or adequacy decisions.',
          ],
          processors: true,
        },
        {
          heading: 'Retention',
          paragraphs: [
            'Account data is kept until the account is deleted. Deleting an account removes personal data and organizations the user was the only member of; security log entries remain without IP address and browser details.',
            'Data of an organization that still has other members belongs to that organization and stays with it.',
          ],
        },
        {
          heading: 'Your rights',
          paragraphs: [
            'You have the right of access and portability (in the app: “My account” → “Download my data”), rectification (edit profile), erasure (“Delete account”), restriction and objection, and to lodge a complaint with a data protection authority (in Poland: the President of UODO).',
          ],
        },
        {
          heading: 'Cookies and browser storage',
          paragraphs: [
            'We use one essential cookie (sign-in session, HttpOnly) and browser storage for interface settings (e.g. the selected organization). No advertising or third-party analytics cookies.',
          ],
        },
      ],
    },
  },
};
