# Propozycje stylu interfejsu

Trzy kierunki wizualne tego samego dashboardu, na prawdziwych danych z TED (2–8 lis 2026):

| Plik | Wariant |
|---|---|
| `A-czysta-biel.png` | A · Czysta biel – Inter, cienkie ramki kart, jeden niebieski akcent |
| `B-granat-premium.png` | B · Granat premium – Plus Jakarta Sans, granatowe przyciski, karty z delikatnym cieniem, duże liczby |
| `C-analityczny.png` | C · Analityczny – IBM Plex Sans/Mono, bez kart, gęsty układ tabelaryczny |

**Wybrany i wdrożony: wariant A · Czysta biel.** Zrzuty działającej aplikacji: `app/`.

Zasady stylu (źródło prawdy: tokeny w `frontend/src/styles.css`):

* wszystkie powierzchnie białe, cienkie obramowania `#EEF0F3` (karty) / `#EAECF0` (pola), zaokrąglenia 14 px karty, 10 px kontrolki;
* tekst: `#0A0F1C` (główny), `#344054` (drugorzędny), `#667085` (opisy);
* jeden akcent `#2457F5` – przyciski główne, aktywny element menu, linki i znaczniki na wykresach (kontrast sprawdzony walidatorem);
* Inter hostowany lokalnie (`@fontsource-variable/inter`) – bez zapytań do Google Fonts (CSP, RODO);
* klasy wspólne: `eti-h1`, `eti-h2`, `eti-eyebrow`, `eti-subtitle`, `eti-card`, `eti-input`, `eti-btn-*`, `eti-table-head`, `eti-note`;
* bez gradientów, emoji, kolorowych pasków przy kartach; status zawsze tekstem, kolor tylko wspiera.
