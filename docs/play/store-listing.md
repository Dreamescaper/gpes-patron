# Google Play: store listing (English and Ukrainian)

Text to paste into Play Console → Grow → Store presence → Main store listing, plus a custom listing for Ukrainian.
Limits: app name 30, short description 80, full description 4000 characters. Never promise protection; keep the limits in.
Public documents are on GitHub Pages (see [pages.md](pages.md)).

## Fields that are not text

| Field | Value |
|---|---|
| App name | `GPES Patron` |
| Category | Maps & Navigation (not "Developer Tools": the users are drivers) |
| Tags | Navigation, GPS |
| Contact email | the developer's own (shown publicly) |
| Website | `https://dreamescaper.github.io/gpes-patron/` |
| Privacy policy | `https://dreamescaper.github.io/gpes-patron/privacy.html` (one page, English and Ukrainian; `?lang=uk` for Ukrainian) |
| Ads | No |
| Target audience | 18 and over (not for children) |
| Content rating | Answer the IARC questionnaire: no violence, sexual content, drugs, gambling, user-generated content or sharing of user location with other users. Expect "Everyone". |
| Data safety | see [data-safety.md](data-safety.md) |
| Foreground service declaration | see [foreground-service.md](foreground-service.md) |
| App access (review) | "All functions are available without sign-in. To test spoofing the reviewer must select GPES Patron as the mock location app: Settings → System → Developer options → Select mock location app → GPES Patron. Then open the app and press the big button." |

## English

**Short description (≤80)**

Keeps your position when GPS is jammed or spoofed. Works with your navigator.

**Full description**

GPES Patron is an experimental app for drivers who lose their position because GPS is jammed or spoofed.

What it does
• Works out where you are from several kinds of evidence instead of trusting GPS blindly: the satellite signals themselves, the phone's motion sensors, network location, road maps and, if you connect one, the speed from an OBD-II adapter.
• Tells you at a glance whether GPS can be trusted right now, and why not.
• When you turn it on, it gives the position it has worked out to your navigation app, so the navigator keeps guiding you when GPS fails.
• Shows a map with your position, its uncertainty, your recent track and where GPS says you are, so a lying GPS is easy to see.

How to use it
1. Open the app. It starts working out your position by itself while the Drive or Map screen is open.
2. Press "Turn on spoofing". The app asks you to select it as the mock location app in Android's developer options (one-time setup, the app shows how).
3. Use your navigator as usual. Turn spoofing off from the app or from its notification whenever you like.

Please read
• This is an experimental research app. It can be wrong, can lose the position and is not a certified navigation or safety system. The driver is always responsible.
• While spoofing is on, every app on your phone, including safety and emergency apps, sees the app's estimate instead of the system location. Turn it off when you need your true location.
• To notice when GPS comes back, the app can briefly hand the real GPS back to other apps every minute or so (it can be switched off in Settings). If the signal is spoofed, other apps see the spoofed position for those seconds.
• It does not promise protection from jamming or spoofing, and it cannot make a position out of nothing: with no GPS, no network and no speed source, accuracy drops quickly.

Privacy
• No accounts, no ads, no analytics.
• Everything is processed on your phone. The only request that leaves it is for road map data (a square of about 5.6 km around you, sent to a public Overpass API server); you can switch the road map off.
• Recording drives is optional and off by default; recordings stay on the phone until you share them.

Permissions
• Location: to work out the position. • Notifications: to show that the app is running and let you turn it off. • Bluetooth: only if you connect an OBD-II adapter. • Nearby Wi-Fi and mobile cell information: as extra evidence of position.

Open source: github.com/Dreamescaper/gpes-patron. Road data © OpenStreetMap contributors. Not affiliated with any government agency, emergency service or navigation app.

## Українська

**Короткий опис (≤80)**

Зберігає вашу позицію, коли GPS глушать чи підміняють. Працює з навігатором.

**Повний опис**

GPES Patron — експериментальний застосунок для водіїв, які втрачають позицію, бо GPS глушать або підміняють.

Що він робить
• Визначає, де ви, за кількома видами свідчень, а не сліпо довіряє GPS: самі супутникові сигнали, датчики руху телефона, мережева локація, карти доріг і, якщо ви підключите, швидкість з OBD-II-адаптера.
• Одразу показує, чи можна зараз довіряти GPS, а якщо ні, то чому.
• Коли ви його вмикаєте, він віддає свою позицію вашому навігатору, тож той продовжує вести, коли GPS відмовляє.
• Показує карту з вашою позицією, її невизначеністю, недавнім треком і тим, де вас бачить GPS, тож брехливий GPS легко помітити.

Як користуватися
1. Відкрийте застосунок. Він сам починає визначати позицію, поки відкрито екран «Їзда» або «Карта».
2. Натисніть «Увімкнути підміну». Застосунок попросить обрати його застосунком для фіктивних місцезнаходжень у параметрах розробника Android (одноразове налаштування, застосунок покаже як).
3. Користуйтеся навігатором як завжди. Вимкнути підміну можна в застосунку або зі сповіщення будь-коли.

Будь ласка, прочитайте
• Це експериментальний дослідницький застосунок. Він може помилятися, втрачати позицію і не є сертифікованою навігаційною системою чи системою безпеки. За кермо завжди відповідає водій.
• Поки підміна ввімкнена, усі застосунки на телефоні, зокрема застосунки безпеки й екстрені, бачать оцінку застосунку замість системної локації. Вимикайте її, коли потрібна ваша справжня локація.
• Щоб помітити, коли GPS повернувся, застосунок може приблизно раз на хвилину на кілька секунд віддавати іншим застосункам справжній GPS (це вимикається в налаштуваннях). Якщо сигнал підроблений, у ці секунди інші застосунки бачать підроблену позицію.
• Він не обіцяє захисту від глушіння чи спуфінгу і не може створити позицію з нічого: без GPS, без мережі й без джерела швидкості точність швидко падає.

Конфіденційність
• Без облікових записів, реклами й аналітики.
• Усе обробляється на вашому телефоні. Єдиний запит назовні — дані про дороги (квадрат близько 5,6 км навколо вас на публічний сервер Overpass API); карту доріг можна вимкнути.
• Запис поїздок необов'язковий і за замовчуванням вимкнений; записи лишаються на телефоні, доки ви не поділитеся ними.

Дозволи
• Геолокація: щоб визначати позицію. • Сповіщення: щоб показувати, що застосунок працює, і давати вимкнути його. • Bluetooth: лише якщо ви підключаєте OBD-II-адаптер. • Wi-Fi поблизу й дані мобільних вишок: як додаткові свідчення про позицію.

Відкритий код: github.com/Dreamescaper/gpes-patron. Дані про дороги © учасники OpenStreetMap. Не пов'язаний з жодним державним органом, екстреною службою чи навігаційним застосунком.
