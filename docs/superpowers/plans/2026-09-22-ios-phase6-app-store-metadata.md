# FitrahTube — App Store listing (version 1.0.0) — DRAFT for owner review

**DRAFT.** Written 2026-09-21, re-verified against HEAD `7abce4f2` and live probes on 2026-09-22, added to the repo with the owner's approval the same day. Paste-ready text for App Store Connect in English (primary), Arabic and Dutch, plus the review notes, the age-rating answers, the App Privacy worksheet and the export-compliance answer. Every block is for the owner to read, change where they disagree, and paste — nothing here is final until the owner has read it. Companion plan: `docs/superpowers/plans/2026-09-22-ios-phase6-app-store-readiness.md` ("the plan" below; its OWNER-ONLY section says which field each block goes into).

**House rules applied to every field below** (source: `docs/architecture/ios-app-plan.md` §9 checklist; owner directives 2026-08-27 and 2026-09-01):

- The user-facing name is **FitrahTube** (Arabic: **فطرة تيوب**, as the app's own `app_name` string).
- The offline feature is only ever called **"Save for offline"** (`offline_save`: ar "الحفظ دون اتصال", nl "Offline opslaan"). RULE, stated once on this line and nowhere else in this file: the words download / downloads / downloaden / تنزيل / حمّل / حمل and ad-free / ad free / advertentievrij / reclamevrij / بدون إعلانات never appear in any paste-ready block, note or comment (`LocalizationTests.bannedStems`, `ios/FitrahTubeTests/LocalizationTests.swift:95-102`). The scan result is at the bottom.
- The listing never says who the audience is in a way that implies a children's app (guideline 5.1.4(b)): "family" and "the whole family" are used, the k-word is not.
- The listing text does not name the video platform the creators publish on, shows no third-party logos, and never promises that another app will open. The review notes describe the player truthfully and completely, because guideline 2.3.1 requires it.
- Background listening exists in the app but is **not advertised** in the listing (plan §9). It is disclosed in the review notes.

Every character-limited field shows `Count: used/limit`. Counts are Unicode characters as App Store Connect counts them (a line break counts as one).

---

## 1. English (U.S.) — primary language

#### App name — limit 30
```text
FitrahTube: Islamic Videos
```
Count: 26/30

Fallback if the name is taken: `FitrahTube` (10/30).

#### Subtitle — limit 30
```text
Curated halal video library
```
Count: 27/30

#### Promotional text — limit 170
```text
A hand-picked library of Islamic lectures, Quran recitation and learning for the whole family. Every channel, playlist and video is reviewed by our team first.
```
Count: 159/170

#### Keywords — limit 100
```text
islam,quran,muslim,lectures,sunnah,recitation,tafsir,hadith,seerah,dawah,arabic,family,fiqh,khutbah
```
Count: 99/100

(No word repeats the name or subtitle — Apple indexes those already. No spaces after commas: they count.)

#### Description — limit 4000
```text
FitrahTube is a curated Islamic video library for the whole family. Nothing reaches your screen by algorithm: every channel, playlist and video is reviewed and approved by our editorial team before it appears in the app.

WHAT YOU WILL FIND
• Quran recitation, tafsir, hadith, seerah, fiqh and Arabic-language learning
• Lectures and series from trusted scholars and teachers
• Clear categories, so it is easy to find what you are looking for
• English, Arabic (with a full right-to-left layout) and Dutch

A CALMER WAY TO WATCH
• The library is the whole app — there is no endless feed of unrelated suggestions
• Safe Mode turns off autoplay, so one lesson ends before the next begins
• Audio Only mode for lectures and recitation, and to use less data
• Save for offline, where the publisher allows it, to watch later inside the app
• Play on your TV with AirPlay or Chromecast
• Designed for iPhone and iPad, with Dynamic Type and VoiceOver support

NO ACCOUNT NEEDED
Browse, search, watch and keep favourites on your device without signing in. An optional free account (email, Google, or Sign in with Apple) keeps your favourites, subscriptions and saved playlists in sync between your devices. You can delete your account at any time inside the app: Me → Profile → Delete account.

FREE
FitrahTube is free. There are no in-app purchases and no subscriptions.

YOUR PRIVACY
We do not track you across apps or websites, and the app contains no third-party analytics. The full policy is at https://app.fitrahtube.com/privacy

HELP US KEEP THE LIBRARY CLEAN
Every video has a Report option. Our team reads every report.
```
Count: 1620/4000

#### What's New in This Version (1.0.0) — limit 4000
```text
Welcome to FitrahTube on iPhone and iPad.

• A curated library of Islamic channels, playlists and videos, reviewed by our editorial team
• Browse by category, search the library, and keep your favourites
• Audio Only mode, Safe Mode, AirPlay and Chromecast
• Save for offline, where the publisher allows it
• Optional free account to sync between devices, with Sign in with Apple
• English, Arabic and Dutch
```
Count: 407/4000

---

## 2. Arabic — العربية

#### App name — limit 30
```text
فطرة تيوب: مرئيات إسلامية
```
Count: 25/30

#### Subtitle — limit 30
```text
مكتبة مرئية إسلامية منتقاة
```
Count: 26/30

#### Promotional text — limit 170
```text
مكتبة منتقاة بعناية من المحاضرات الإسلامية وتلاوات القرآن والدروس النافعة لكل أفراد الأسرة. كل قناة وقائمة ومقطع يراجعه فريقنا قبل أن يظهر في التطبيق.
```
Count: 150/170

#### Keywords — limit 100
```text
إسلام,قرآن,حلال,مسلم,محاضرات,سنة,تلاوة,تفسير,حديث,سيرة,دعوة,دروس,أسرة,علم,فقه,خطب
```
Count: 81/100

(ASCII commas, not the Arabic comma "،" — App Store Connect splits on the ASCII one.)

#### Description — limit 4000
```text
فطرة تيوب مكتبة مرئية إسلامية منتقاة لكل أفراد الأسرة. لا شيء يصل إلى شاشتك عن طريق الخوارزميات: كل قناة وقائمة تشغيل ومقطع يراجعه فريقنا التحريري ويعتمده قبل أن يظهر في التطبيق.

ماذا ستجد؟
• تلاوات القرآن الكريم، والتفسير، والحديث، والسيرة، والفقه، وتعلّم اللغة العربية
• محاضرات وسلاسل علمية لمشايخ ومعلّمين موثوقين
• تصنيفات واضحة تسهّل الوصول إلى ما تبحث عنه
• العربية (بواجهة كاملة من اليمين إلى اليسار) والإنجليزية والهولندية

مشاهدة أكثر هدوءًا
• المكتبة هي التطبيق كله — لا سيل من الاقتراحات التي لا علاقة لها بما تشاهد
• «الوضع الآمن» يوقف التشغيل التلقائي، فينتهي الدرس قبل أن يبدأ الذي يليه
• وضع «صوت فقط» للمحاضرات والتلاوات ولتوفير البيانات
• «الحفظ دون اتصال» متى أذن الناشر بذلك، لتشاهد لاحقًا داخل التطبيق
• اعرض على التلفاز عبر AirPlay أو Chromecast
• مصمَّم لأجهزة iPhone وiPad، ويدعم تكبير الخط وVoiceOver

لا حاجة إلى حساب
تصفّح وابحث وشاهد واحتفظ بمفضلتك على جهازك دون تسجيل الدخول. والحساب المجاني الاختياري (بالبريد الإلكتروني أو Google أو «تسجيل الدخول مع Apple») يزامن مفضلتك واشتراكاتك وقوائمك المحفوظة بين أجهزتك. ويمكنك حذف حسابك في أي وقت من داخل التطبيق: أنا ← الملف الشخصي ← حذف الحساب.

مجاني
فطرة تيوب مجاني، بلا مشتريات داخل التطبيق وبلا اشتراكات.

خصوصيتك
لا نتتبّعك عبر التطبيقات أو المواقع، ولا يحتوي التطبيق على أدوات تحليلات تابعة لجهات خارجية. سياسة الخصوصية كاملة على: https://app.fitrahtube.com/privacy

ساعدنا في إبقاء المكتبة نظيفة
في كل مقطع خيار «إبلاغ»، وفريقنا يقرأ كل بلاغ.
```
Count: 1424/4000

#### What's New in This Version (1.0.0) — limit 4000
```text
مرحبًا بكم في فطرة تيوب على iPhone وiPad.

• مكتبة منتقاة من القنوات وقوائم التشغيل والمقاطع الإسلامية يراجعها فريقنا التحريري
• تصفّح حسب التصنيف، وابحث في المكتبة، واحتفظ بمفضلتك
• وضع «صوت فقط»، و«الوضع الآمن»، وAirPlay وChromecast
• «الحفظ دون اتصال» متى أذن الناشر بذلك
• حساب مجاني اختياري للمزامنة بين الأجهزة، مع «تسجيل الدخول مع Apple»
• العربية والإنجليزية والهولندية
```
Count: 377/4000

**Needs a native-speaker pass before submission.** Terms taken verbatim from the app's own catalog so the listing matches the UI: الوضع الآمن · صوت فقط · الحفظ دون اتصال · أنا · الملف الشخصي · حذف الحساب · إبلاغ.

---

## 3. Dutch — Nederlands

#### App name — limit 30
```text
FitrahTube: halal video's
```
Count: 25/30

#### Subtitle — limit 30
```text
Islamitische videobibliotheek
```
Count: 29/30

#### Promotional text — limit 170
```text
Een zorgvuldig samengestelde bibliotheek met islamitische lezingen, Koranrecitatie en leerzame video's voor het hele gezin. Ons team beoordeelt alles vooraf.
```
Count: 157/170

#### Keywords — limit 100
```text
islam,koran,moslim,lezingen,soenna,recitatie,tafsir,hadith,seerah,dawah,arabisch,gezin,leren,fiqh
```
Count: 97/100

#### Description — limit 4000
```text
FitrahTube is een zorgvuldig samengestelde islamitische videobibliotheek voor het hele gezin. Niets komt via een algoritme op je scherm: elk kanaal, elke afspeellijst en elke video wordt door onze redactie bekeken en goedgekeurd voordat het in de app verschijnt.

WAT JE VINDT
• Koranrecitatie, tafsir, hadith, seerah, fiqh en lessen Arabisch
• Lezingen en reeksen van betrouwbare geleerden en docenten
• Duidelijke categorieën, zodat je snel vindt wat je zoekt
• Nederlands, Engels en Arabisch (met volledige rechts-naar-links-weergave)

RUSTIGER KIJKEN
• De bibliotheek is de hele app — geen eindeloze stroom suggesties die er niets mee te maken hebben
• Veilige modus zet automatisch afspelen uit: de ene les is afgelopen voordat de volgende begint
• Alleen audio voor lezingen en recitatie, en om data te besparen
• Offline opslaan, waar de maker dat toestaat, om later in de app te kijken
• Kijk op je tv met AirPlay of Chromecast
• Ontworpen voor iPhone en iPad, met ondersteuning voor Dynamic Type en VoiceOver

GEEN ACCOUNT NODIG
Bladeren, zoeken, kijken en favorieten op je toestel bewaren kan zonder in te loggen. Met een optioneel gratis account (e-mail, Google of Log in met Apple) blijven je favorieten, abonnementen en bewaarde afspeellijsten gelijk op al je apparaten. Je kunt je account op elk moment in de app verwijderen: Ik → Profiel → Account verwijderen.

GRATIS
FitrahTube is gratis. Er zijn geen in-app aankopen en geen abonnementen.

JOUW PRIVACY
We volgen je niet over apps of websites heen, en de app bevat geen analysetools van derden. Het volledige beleid staat op https://app.fitrahtube.com/privacy

HELP ONS DE BIBLIOTHEEK SCHOON TE HOUDEN
Elke video heeft een optie Melden. Ons team leest elke melding.
```
Count: 1733/4000

#### What's New in This Version (1.0.0) — limit 4000
```text
Welkom bij FitrahTube op iPhone en iPad.

• Een zorgvuldig samengestelde bibliotheek met islamitische kanalen, afspeellijsten en video's, beoordeeld door onze redactie
• Blader per categorie, doorzoek de bibliotheek en bewaar je favorieten
• Alleen audio, Veilige modus, AirPlay en Chromecast
• Offline opslaan, waar de maker dat toestaat
• Optioneel gratis account om te synchroniseren tussen apparaten, met Log in met Apple
• Nederlands, Engels en Arabisch
```
Count: 458/4000

UI terms taken from the app's catalog: Veilige modus · Alleen audio · Offline opslaan · Ik · Profiel · Account verwijderen · Melden.

---

## 4. URLs (same for all three languages)

| Field | Value | Status on 2026-09-22 (re-probed) |
|---|---|---|
| Privacy Policy URL (required) | `https://app.fitrahtube.com/privacy` | 200 |
| Support URL (required) | `https://app.fitrahtube.com/terms` | 200 — section 11 "Contact" publishes `info@albunyaan.tv`. There is no `/support` page (403) and no public website (`fitrahtube.com` 404). **Weak spot:** a reviewer may want a page that reads as support. If that happens, the fix is one small backend page, not a new site |
| Marketing URL (optional) | leave empty | no public site exists |
| Account-deletion URL (not an App Store field; cite in review notes) | `https://app.fitrahtube.com/delete-account` | 200 |
| Copyright | `© 2026 FitrahTube` | owner to confirm the legal entity name |

Category: **Education** (primary), **Lifestyle** (secondary). Reasoning: the catalog is lectures, recitation and lessons; "Entertainment" invites comparison with general video apps, "Reference" undersells video.

**Open owner question, not decided here (plan Task 2, OQ-1):** the app's About screen currently keeps a "GitHub" row pointing at the public source repository, whose README describes the project in words this listing deliberately avoids. Whether that row stays (and the README is reworded) or goes is the owner's call; this file assumes nothing about it.

---

## 5. App Review Information

**Sign-in required:** Yes (for account features only). **User name / Password:** `<< OWNER ENTERS THE DEMO ACCOUNT HERE, IN APP STORE CONNECT ONLY — never in this file, chat or the repo >>`. The demo account must be: email + password, email already verified, profile completed with an adult date of birth.

**Contact:** owner's name, phone and e-mail (App Store Connect fields).

#### Notes — limit 4000
```text
WHAT THE APP IS
FitrahTube is a free, curated Islamic video library. It has no feed algorithm and no user-to-user features. Every channel, playlist and video in the catalog is proposed and then approved by our own editorial team in a separate moderation dashboard before it becomes visible in the app. The videos themselves are the creators' public videos, hosted by YouTube; the app's catalog (what is listed, in which category) comes from our own server at app.fitrahtube.com.

NO ACCOUNT NEEDED
All five tabs work without signing in: Home, Channels, Me, Playlists, Videos, plus Search, Categories, the player, local favourites and Settings. To test quickly: open Home, tap any video.

ACCOUNT (OPTIONAL) AND ACCOUNT DELETION
Me tab → Sign in. Options: email and password, Google, Sign in with Apple (guideline 4.8). A demo account is provided in the sign-in fields above. After sign-up the app asks for a name, date of birth and phone number; the date of birth is used once to enforce a minimum age of 13 for accounts (younger users simply continue as guests with the full library).
Account deletion is in the app: Me tab → "…" menu (top corner) → Profile → scroll to "Delete account" → confirm. It deletes the server profile and the sign-in, and returns the app to guest mode. The same can be requested on the web at https://app.fitrahtube.com/delete-account

PLAYER — DESCRIBED PRECISELY
Playback is native (AVPlayer) inside the app. If native playback is not possible for a video, the app falls back to YouTube's official embedded player inside a locked web view; in that case YouTube's own branding, and any advertising YouTube chooses to show, can appear. The web view cannot navigate anywhere else. The app sells nothing and shows no advertising of its own.

FEATURES TO BE AWARE OF (guideline 2.3.1)
• Save for offline: on videos where our team has recorded the publisher's permission, a "Save for offline" button stores the video inside the app's private container for playback in the app only. Files are not visible in the Files app, cannot be shared or exported, and are excluded from backup. The feature can be switched off remotely by us. Saved items are listed under Settings → Saved (and on the Me tab when signed in).
• Audio Only and background audio: lectures and recitation keep playing with the screen locked (this is the reason for the audio background mode).
• AirPlay and Chromecast. Chromecast discovery is why the app asks for Local Network access, only after the first tap on the Cast button.
• Safe Mode (Settings): turns off autoplay.
• Import from YouTube (Me → "…" → Import from YouTube, signed-in users): with Google's read-only consent screen, the user can match their existing subscriptions and playlists against our catalog. Only items already in our catalog can be added. The Google token stays on the device and can be forgotten from the same screen.
• Report: every video has a Report action; reports go to our moderators.
• Suggest Content and My Submissions appear only for accounts with a moderator or admin role; the demo account does not have that role.
• About → tapping the version number seven times opens a read-only diagnostics sheet (app version, server address, anonymous device id). It changes nothing.
• If our server sets a newer minimum version, the app shows an "Update required" screen with a button to the App Store.

DATA
No tracking, no third-party analytics, no advertising SDK. The app sends an anonymous, locally generated device id with requests to our server for rate limiting. Privacy policy: https://app.fitrahtube.com/privacy

LANGUAGES
English, Arabic (right-to-left), Dutch. The app follows the iOS per-app language setting.

Thank you for reviewing FitrahTube. Contact: info@albunyaan.tv
```
Count: 3758/4000

**Before pasting, check three statements against the build being submitted** (they are true of the code read on 2026-09-22, but each depends on an open task in the plan):
1. "with a button to the App Store" — true only after plan Task 5 **and** the App Store ID is set. Otherwise delete that clause.
2. "falls back to YouTube's official embedded player" — true only after plan Task 6 is merged to `main`.
3. The Import paragraph — if Google has not verified the `youtube.readonly` scope (plan, OWNER-ONLY item 11), add: "During review this feature may show Google's 'unverified app' notice."

**Content-rights question** ("Does your app contain, show, or access third-party content?"): the truthful answer is **Yes**. App Store Connect then asks you to confirm you have the necessary rights. `ios-app-plan.md` §9 ("Plain reading") records that only the embedded player is covered by the platform's terms and that the owner accepted the remaining exposure knowingly. This draft does not answer that checkbox for the owner — it is the one field in the submission that is a legal statement, not a description.

---

## 6. Age-rating questionnaire

Target outcome: **4+** (plan §9). Apple's 2025 questionnaire; answer every content row **None** unless listed.

| Question | Answer | Reasoning |
|---|---|---|
| Cartoon / realistic / prolonged graphic violence | None | Lectures may *speak about* historical battles (seerah); nothing is depicted. The catalog is editorially approved |
| Profanity or crude humour | None | Excluded by curation |
| Mature or suggestive themes | None | Excluded by curation |
| Horror / fear themes | None | — |
| Medical or treatment information | None | Religious lectures are not medical advice; no health features |
| Alcohol, tobacco or drug use or references | None | Not depicted. A lecture may name them as prohibited; that is not "use or references" in Apple's sense (depiction/encouragement). If the owner prefers zero risk, "Infrequent" moves the rating to 9+ or higher — not recommended |
| Sexual content or nudity / graphic sexual content | None | Excluded by curation |
| Simulated gambling / gambling / contests / loot boxes | None / No | None exist |
| **Unrestricted web access** | **No** | The only web view is the fallback player, navigation-locked to one page (`EmbedNavigationPolicy`); legal links open in Safari, outside the app |
| **User-generated content** | **No** | Users cannot publish anything to other users. Reports go to moderators only; content suggestions are limited to staff roles and are approved before anything changes in the catalog |
| **Messaging / chat / social networking** | **No** | None |
| **Advertising** | **Yes** | The app has no advertising of its own and no advertising SDK. But when the fallback (embedded) player is used, the video host may show its own advertising inside that player. Plan §9 requires declaring it once the fallback rung is published (plan Task 6). Expected to leave the rating at 4+; accept whatever App Store Connect computes |
| **Parental controls** | **No** | Safe Mode is a viewing preference (autoplay off), not a parent-locked restriction. *This departs from plan §9, which listed Safe Mode here; claiming a control that has no PIN or lock is the riskier answer* |
| **Age assurance** | **Yes — declared age only** | A self-declared date of birth at account creation, used to refuse accounts under 13. No ID or estimation. If the form offers no "declared" option, answer No and keep the sentence in the review notes. Confidence in this row: ~60% — the wording of this question changed in 2025 and should be read on screen |
| Made for Kids / Kids category | **No — never** | Guideline 5.1.4: a Kids-category app may not use this kind of sign-in or third-party player. Do not tick it, and do not use the k-word in any field |
| Age-restricted override | none | Leave the computed rating |

---

## 7. App Privacy ("nutrition label") worksheet

Derived row by row from `ios/FitrahTube/Resources/PrivacyInfo.xcprivacy` (`NSPrivacyCollectedDataTypes`, re-read 2026-09-22: seven types, `:43-126`). First screen: **"Yes, we collect data from this app."** Tracking: **No** for every row (`NSPrivacyTracking = false`, no tracking domains).

| Manifest type | App Store Connect category → type | Linked to the user? | Tracking? | Purpose | What it actually is |
|---|---|---|---|---|---|
| `…DeviceID` | Identifiers → **Device ID** | **No** | No | App Functionality | A random id generated on the device, sent as `X-Device-Id` for rate limiting and stored with a report |
| `…UserID` | Identifiers → **User ID** | Yes | No | App Functionality | The account id every profile row is keyed by |
| `…EmailAddress` | Contact Info → **Email Address** | Yes | No | App Functionality | From sign-up or the Google / Apple credential |
| `…Name` | Contact Info → **Name** | Yes | No | App Functionality | Display name on the profile |
| `…PhoneNumber` | Contact Info → **Phone Number** | Yes | No | App Functionality | Entered at profile setup; never verified or messaged |
| `…OtherUserContent` | User Content → **Other User Content** | Yes | No | App Functionality | Report text, staff content suggestions and notes, and the ids/titles matched during Import |
| `…OtherDataTypes` | Other Data → **Other Data Types** | Yes | No | App Functionality | Date of birth (Apple lists no birth-date type) |

Everything not listed is **not collected**: location, contacts, photos, browsing history, search history (kept on the device only), purchases, financial info, health, sensitive info, usage data, diagnostics, advertising data.

**Two checks before saving the label** (not derivable from our manifest):
1. **Bundled SDK manifests.** Firebase Auth, Google Sign-In and Google Cast each ship their own privacy manifest. After the first archive, Xcode → Organizer → right-click the archive → *Generate Privacy Report* lists what they add. If the report shows a type not in the table (for example a diagnostics type from a Google SDK), add that row with Google's published answer. Not run yet — needs a signed archive (plan Task 8).
2. **Video requests go from the phone straight to the video host**, carrying a host-issued session identifier. We never receive it, so under Apple's definition ("data transmitted off the device … that you or your third-party partners retain") it is the host's collection, not ours; the privacy policy is the right place to say so. `ios-app-plan.md` §9 asked for this flow to be disclosed — confirm `/privacy` section on third parties covers it. Confidence that no extra label row is needed: ~70%.

**Drift guard:** the label must always equal the manifest. If a row is added to one, add it to the other in the same change (`FirebaseSeamTests.thePrivacyManifestDeclaresWhatPhase4Collects` pins five of these rows).

---

## 8. Export compliance

**Answer: the app uses no non-exempt encryption.** It makes HTTPS calls through Apple's networking stack and uses the system keychain — both fall under the exemption for encryption that is part of the operating system. There is no proprietary or bundled cryptography, no VPN, no end-to-end messaging.

This is already declared in the build: `ITSAppUsesNonExemptEncryption = false` (`ios/project.yml:134`), so App Store Connect will **not** ask the question at upload and no annual self-classification report is required. If the question does appear, choose **"None of the algorithms mentioned above"**.

---

## 9. Other App Store Connect settings

| Setting | Value |
|---|---|
| Price | Free; no in-app purchases |
| Availability | All territories the owner wants; **EU requires the DSA trader form** (Dutch locale implies NL) |
| iPhone and iPad apps on Apple silicon Mac | **Off** |
| Apple Vision Pro | **Off** |
| Version | 1.0.0 (matches `MARKETING_VERSION`, `ios/project.yml:28`) |
| Version release | "Manually release this version" — lets the owner choose the day and confirm the live settings file first |
| Screenshots | iPhone 6.9" and iPad 13", en / ar / nl, native player only, no third-party logos (plan Task 9) |

---

## 10. Verification run on this file

Run 2026-09-22 by script over this file (Python `len()` on each ```text block's contents, i.e. Unicode code points, a line break counting as one — the way App Store Connect counts).

**Character counts:**

| Section | Field | Used | Limit | |
|---|---|---|---|---|
| English (U.S.) — primary language | App name | 26 | 30 | OK |
| English (U.S.) — primary language | Subtitle | 27 | 30 | OK |
| English (U.S.) — primary language | Promotional text | 159 | 170 | OK |
| English (U.S.) — primary language | Keywords | 99 | 100 | OK |
| English (U.S.) — primary language | Description | 1620 | 4000 | OK |
| English (U.S.) — primary language | What's New in This Version (1.0.0) | 407 | 4000 | OK |
| Arabic — العربية | App name | 25 | 30 | OK |
| Arabic — العربية | Subtitle | 26 | 30 | OK |
| Arabic — العربية | Promotional text | 150 | 170 | OK |
| Arabic — العربية | Keywords | 81 | 100 | OK |
| Arabic — العربية | Description | 1424 | 4000 | OK |
| Arabic — العربية | What's New in This Version (1.0.0) | 377 | 4000 | OK |
| Dutch — Nederlands | App name | 25 | 30 | OK |
| Dutch — Nederlands | Subtitle | 29 | 30 | OK |
| Dutch — Nederlands | Promotional text | 157 | 170 | OK |
| Dutch — Nederlands | Keywords | 97 | 100 | OK |
| Dutch — Nederlands | Description | 1733 | 4000 | OK |
| Dutch — Nederlands | What's New in This Version (1.0.0) | 458 | 4000 | OK |
| App Review Information | Notes | 3758 | 4000 | OK |

**Banned-term scan** (case-insensitive, whole file): the stems named on the RULE line at the top — English, Arabic and Dutch, from `LocalizationTests.bannedStems` — plus the retired `albunyaan` host name and the 5.1.4(b) audience word in three languages. Result: **every banned stem occurs on exactly one line of this file — the RULE line itself — and nowhere else; the retired host name occurs nowhere (only `fitrahtube.com` URLs appear in this file); the audience word appears only on this document's own rule bullet and in the age-rating row that names Apple's field.** The `info@albunyaan.tv` contact address is not a URL and is what `/terms` and `/privacy` publish (`LegalPagesController.java:35`).
