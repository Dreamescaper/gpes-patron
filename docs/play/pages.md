# GitHub Pages: the public documents

`site/` is published at `https://dreamescaper.github.io/gpes-patron/` by `.github/workflows/pages.yml` on every push to `main`
that changes `site/`. Pages is set to "GitHub Actions" as the source (Settings → Pages, or `gh api -X POST repos/OWNER/REPO/pages -f build_type=workflow`).

| Page | URL | Used by |
|---|---|---|
| Home | `/` | Play listing "Website" |
| Privacy policy | `/privacy.html` (`?lang=uk` for Ukrainian) | Play Console privacy policy field, the app (Settings → About) |
| Terms of use | `/terms.html` (`?lang=uk`) | the app (Settings → About), the listing |

Each page holds both languages in one file. The language comes from `?lang=en|uk`, otherwise from the browser, and both languages are visible
without JavaScript. When you change one language, change the other. When the app's data handling changes (a new network call, an SDK, a new
permission), update the privacy policy, its "effective" date, the data safety answers ([data-safety.md](data-safety.md)) and the permissions table.
