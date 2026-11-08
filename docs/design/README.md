# Propozycje stylu interfejsu

Trzy kierunki wizualne tego samego dashboardu, na prawdziwych danych z TED (2–8 lis 2026):

| Plik | Wariant |
|---|---|
| `A-czysta-biel.png` | A · Czysta biel – Inter, cienkie ramki kart, jeden niebieski akcent |
| `B-granat-premium.png` | B · Granat premium – Plus Jakarta Sans, granatowe przyciski, karty z delikatnym cieniem, duże liczby |
| `C-analityczny.png` | C · Analityczny – IBM Plex Sans/Mono, bez kart, gęsty układ tabelaryczny |

**Wybrany i wdrożony: wariant A · Czysta biel.** Zrzuty działającej aplikacji: `app/`.

Zasady stylu (źródło prawdy: tokeny w `frontend/src/styles.css`):

* powierzchnie białe, menu boczne `#F9FAFB`; cienkie obramowania `#EEF0F3` (karty) / `#EAECF0` (pola); zaokrąglenia 10 px karty, 8 px kontrolki;
* tekst: `#0A0F1C` (główny), `#344054` (drugorzędny), `#667085` (opisy);
* akcent `#2457F5` – przyciski główne, aktywny element menu, linki;
* wykresy: paleta kategorii w stałej kolejności `#2457F5`, `#EF7D2D`, `#16A37F`, `#8257E6`, `#D6498F`, `#D9A000` (sprawdzona walidatorem pod kątem daltonizmu); pomarańcz i żółć zawsze z legendą z wartościami;
* układ (wersja 2, wzorowana na nowoczesnych panelach analitycznych): menu boczne ze zwijanymi sekcjami, ścieżka nawigacji (breadcrumbs), karty KPI z opisem, dużą liczbą, zmianą vs poprzedni tydzień i mini-wykresem, karty z tytułem, opisem i przyciskiem odświeżania, wykres pierścieniowy, pasek segmentowy, paski postępu, stopka;
* Inter hostowany lokalnie (`@fontsource-variable/inter`) – bez zapytań do Google Fonts (CSP, RODO);
* klasy wspólne: `eti-h1`, `eti-h2`, `eti-kpi`, `eti-icon-btn`, `eti-eyebrow`, `eti-subtitle`, `eti-card`, `eti-input`, `eti-btn-*`, `eti-table-head`, `eti-note`;
* bez gradientów, emoji, kolorowych pasków przy kartach; status zawsze tekstem, kolor tylko wspiera.
