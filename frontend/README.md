# EU Tender Intelligence – frontend

Angular 22 (standalone components, signals), Tailwind CSS 4, Transloco (PL domyślnie, EN), ikony `@lucide/angular`.

```bash
npm ci
npm start                       # http://localhost:4200 (proxy /api → http://localhost:8080)
npx ng test --watch=false       # testy jednostkowe (Vitest)
npx ng build                    # build produkcyjny → dist/eti-frontend
```

Struktura:

* `src/app/core` – API, i18n, layout (sidebar, topbar)
* `src/app/shared/ui` – komponenty UI (badge, skeleton, …)
* `src/app/features` – ekrany (feature-first, lazy-loaded)
* `public/i18n/*.json` – tłumaczenia; nowy język = plik tłumaczeń + wpis w `core/i18n/languages.ts` + `locale-data.ts`
