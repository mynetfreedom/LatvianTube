# LatvianTube

**An Android TV client that shows only YouTube content in the Latvian language.**

YouTube does not expose a dependable field saying what language a video is in, so
LatvianTube *scores* every video against several signals — Latvian diacritics,
Latvian function words, and a curated registry of verified Latvian channels — and
admits only what passes. The home screen is built from those curated channels and
from Latvian-scoped search, because YouTube's trending endpoint is unusable.

Built by reverse-engineering the architecture of
[SmartTube](https://github.com/yuliskov/SmartTube) and reimplementing it for one
language instead of all of them. Written in Java for Android TV 5.0+ (API 21),
free of Google Play Services, and requiring no account.

**[Detailed English documentation →](README.en.md)** ·
**[Full investigation and evidence →](ANALYSIS.md)** ·
**[MIT licensed](LICENSE)**

![Home screen](screenshots/01-home.png)

---

## Latviski

### Kas ir LatvianTube?

LatvianTube ir bezmaksas, atvērtā koda YouTube klients Android TV televizoriem
un TV uztvērējiem, kas rāda **tikai video latviešu valodā**. Tā vietā, lai
piedāvātu visu YouTube saturu, aplikācija apzināti ierobežo sevi ar vienu valodu.

Aplikācija ir rakstīta Java valodā, tā darbojas uz Android TV 5.0 (API 21) un
jaunākām versijām, neizmanto Google Play pakalpojumus un **neprasa nekādu
kontu** — nav pieteikšanās, nav abonementu, nav vēstures.

### Kāpēc tas pastāv

YouTube pats nesniedz uzticamu veidu, kā noteikt video valodu. Pārbaudot
atklājās trīs problēmas:

- lauks `defaultAudioLanguage`, kas norāda audio valodu, vairumā gadījumu
  **vispār nav pieejams**;
- kanāla izcelsmes valsts vairs netiek atklāta;
- kanāla nosaukums latviešu valodā **nekā negarantē** — kanāls ar nosaukumu
  "Latvijas Ziņas" publicē tikai krieviski.

Tāpēc valodu nevar vienkārši nolasīt. Tā ir **jānovērtē**.

### Kā darbojas valodas filtrs

Katrs video tiek novērtēts pēc vairākām pazīmēm, un katrs vērtējums tiek
aprēķināts punktos:

| Pazīme | Punkti |
| --- | --- |
| Latviešu valodai raksturīgās diakritiskās zīmes `ā ē ī ū ō ņ ķ ļ ģ` | +4 par katru |
| Kopīgās diakritiskās zīmes `š ž č` (arī čehu, horvātu, turku valodā) | +2 par katru |
| Latviešu palīgvārdi (*un, ar, kas, ...*) | +2 par katru |
| **Kanāls ir pārbaudītajā sarakstā** | **+6** |
| Kirilica | **−25** |
| Krievu palīgvārdi | −3 par katru |
| Angļu valodas marķieri | −1 par katru |
| **Video tiek pielaists, ja** | **≥ 4 punkti** |

Divi svarīgākie principi:

1. **Pārbaudītais kanālu saraksts ir spēcīgākā pazīme.** Tas ir tas, kas
   izglābj īstus latviešu video, kuros nav ne diakritisko zīmju, ne palīgvārdu —
   piemēram, "DZIESMAS JAUNAI DIENAI" pēc teksta vien iegūst tikai 2 punktus.
2. **Katrs lēmums ir izskaidrojams.** Katrs video saglabā pazīmes, pēc kurām tas
   tika pielaists, tāpēc kļūdainu lēmumu var izmeklēt ierīcē, nevis tikai
   reproducēt testos.

### Satura avoti

Tā kā YouTube "Trending" (populārākais) galapunkts atgriež HTTP 400 kļūdu un
lapas vietā tiek parādīts paziņojums par sīkdatnēm, aplikācija veido sākumlapu no
diviem avotiem, kas patiešām darbojas:

- **17 pārbaudīti latviešu kanāli** 8 kategorijās: Ziņas, Mūzika, Sports,
  Virtuve, Komēdija, Izglītība, Kino, Pilsētas;
- **meklēšana**, kas ierobežota uz latviešu valodu.

Katru kanālu pārbauda automātiski (`tools/verify-registry.mjs`), un tas tiek
iekļauts sarakstā tikai tad, ja vismaz 80 % tā jaunāko video iztur valodas filtru.
Tā tika atklāti un izslēgti vairāki kanāli, tostarp krieviski rakstošais
"Latvijas Ziņas".

### Funkcijas

- Sākumlapa ar 8 kategoriju rindām, kas ielādējas pakāpeniski
- Meklēšana latviešu valodā, kas sniedzas arī ārpus pārbaudītā kanālu saraksta
- Atskaņošana ar YouTube iebūvēto atskaņotāju un latviešu subtitriem
- Pilnībā latviska saskarne neatkarīgi no televizora sistēmas valodas
- Kešatmiņa, lai ierobežotu pieprasījumu skaitu un darbotos ātrāk
- Bez konta, bez Google Play pakalpojumiem, bez reklāmām

### Statuss

Aplikācija ir **uzbūvēta un pārbaudīta** uz Android TV 14 emulatora un uz īsta
televizora (Grundig Android TV, Android 11). Pārbaudīts, ka:

- visas 8 rindas ielādējas un valodas filtrs darbojas uz dzīviem datiem;
- atskaņošana darbojas ar latviešu subtitriem;
- meklēšana atgriež latviešu rezultātus;
- nav neviena kritiena.

Sīkāka informācija un visi pierādījumi ir failā [ANALYSIS.md](ANALYSIS.md).

### Kā uzbūvēt

Nepieciešams JDK 17 un Android SDK (34. platforma):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Lai palaistu Android TV emulatorā:

```bash
./run-tv.sh
```

### Ierobežojumi

1. **Atskaņošana notiek WebView pārlūkā**, nevis ar aparatūras dekodētāju. Lētos
   televizoros tas ir lētākā vieta — tāpēc atskaņotājs tiek renderēts zemākā
   izšķirtspējā un mērogots uz augšu.
2. **Audio valoda netiek pārbaudīta.** Ja video nosaukums ir latviski, bet audio —
   krieviski, filtrs to pielaidīs. To nevar noteikt ar YouTube pieejamajiem
   datiem.
3. **Pārklājums ir ierobežots ar kanālu sarakstu un meklēšanas vaicājumiem.**
   Latviešu video no nepārbaudīta kanāla ar nosaukumu bez diakritiskajām zīmēm
   aplikācijā neparādīsies.
4. **Nav "Trending" sadaļas**, jo YouTube to nepieļauj (skatīt augstāk).
5. Pārbaudīts tikai uz vienas televizora markas un viena emulatora.

### Licence

Šis projekts ir licencēts saskaņā ar [MIT licenci](LICENSE).

LatvianTube ir neatkarīgs klients publiski pieejamam YouTube saturam. Tas
nelejupielādē, nepārpublicē un neizplata video, un tam nav vajadzīgs YouTube
konta. InnerTube ir nedokumentēta saskarne, un tās izmantošana var būt pretrunā
ar YouTube lietošanas noteikumiem. Projekts ir paredzēts personīgai un
izglītojošai lietošanai.

SmartTube ir atsevišķs Juri Liskova projekts ar Apache-2.0 licenci. No tā netika
pārkopēts neviens koda fragments — tika pētīta tikai arhitektūra.
