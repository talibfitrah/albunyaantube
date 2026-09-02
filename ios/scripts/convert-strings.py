#!/usr/bin/env python3
"""Android strings*.xml (en, ar, nl) -> ios/FitrahTube/Resources/Localizable.xcstrings.
Rules R1-R9 from docs/superpowers/plans/2026-08-23-ios-phase1-research/strings-assets.md.
Usage: python3 ios/scripts/convert-strings.py [--check]"""
import glob, html, json, os, re, sys, tempfile, xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "android/app/src/main/res")
OUT = os.path.join(ROOT, "ios/FitrahTube/Resources/Localizable.xcstrings")
LOCALES = {"en": "values", "ar": "values-ar", "nl": "values-nl"}
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")

# R8 / §7: keys that cannot convert mechanically -- refused (reported), not silently dropped.
# The only three real hazards: %.1f abbreviated-count strings, superseded by CountFormat.kt and
# already dead on Android (strings-assets.md §4, §7.3).
# share_app_promo: Android's value says "ad-free" (spec D10 / §12 require dropping it; the embed
# rung plays YouTube's own player, ads included). Refused here, re-authored under EXTRA_KEYS.
REFUSE = {"views_count_billions", "views_count_millions", "views_count_thousands", "share_app_promo",
          # Orphaned on iOS (Phase 2 gate, 2026-08-30): no Swift reader. `videoAccessibilityLabel` and
          # `CountFormat`-style formatting replaced them; drop rather than ship dead catalog entries.
          "a11y_video_item", "a11y_playlist_video", "video_views_format", "playlist_metadata_duration_format",
          # Android self-updater island — impossible on iOS (App Store policy), Phase 2 gate 2026-08-31.
          "settings_check_for_updates", "settings_available_updates",
          # Owner ruling 2026-09-01 (Phase 3 Task 5): every user-facing surface says "Save for
          # offline", never "Download". These five are the settings keys SettingsView already
          # renders with Android's "Downloads"/"Download Quality"/"WiFi Only" values -- refused
          # here and re-authored under EXTRA_KEYS. The other download_*/downloads_* Android keys
          # are NOT ported to any iOS caller and stay orphaned in the catalog (pruning them is a
          # converter change with its own blast radius -- out of scope).
          "settings_downloads", "settings_download_quality", "settings_download_quality_title",
          "settings_wifi_only", "settings_wifi_only_desc"}

# The two decoupled-quantity plurals (strings-assets.md §3b / RULINGS 37): the printed arg (%s)
# and the plural-category selector are different values on Android (CountFormat.compactPluralCount).
# Emitted via the xcstrings `substitutions` form below, not the plain plural loop.
SUBSTITUTION_PLURALS = {"video_views", "live_watching_count"}

# §6 dead keys. filter_length_/filter_date_/filter_sort_/locale_settings_ are safe blanket
# prefixes (verified via grep: no live key matches them). list_/error_ are NOT safe blanket
# prefixes -- each cluster is "dead except N keys" per §6, and those survivors
# (list_error_title/description, error_title/unknown/state_generic_headline) are referenced from
# ChannelsFragmentNew.kt, PlaylistsFragmentNew.kt, VideosFragmentNew.kt, SearchFragment.kt,
# DownloadsFragment.kt, PlayerFragment.kt, home_section_error.xml and error_state.xml -- verified
# by grepping android/app/src/main/java + res/layout* for R.string./@string/ references. A blanket
# prefix would silently drop live, phase-1-needed strings.
DEAD_PREFIXES = ("filter_length_", "filter_date_", "filter_sort_", "list_", "locale_settings_", "error_",
                 # Android self-updater island — impossible on iOS (App Store policy), Phase 2 gate 2026-08-31.
                 "update_", "available_versions_")
DEAD_PREFIX_EXCEPTIONS = {
    "list_error_title", "list_error_description",
    "error_title", "error_unknown", "error_state_generic_headline",
}

# about_version_format's %2$ argument is CFBundleVersion on iOS -- a String, not Android's
# versionCode Int (strings-assets.md §7.7). Override the generic %d->%lld rewrite for this one key.
SPECIFIER_OVERRIDES = {
    "about_version_format": lambda v: v.replace("%2$lld", "%2$@"),
}

# iOS-only keys with no Android source. A value is either a plain string (identical across every
# locale -- e.g. filter_label_parent_child's separator, which is locale-agnostic) or a
# {locale: text} dict for real translated prose (task-12: the guest Me-tab sign-in card, which
# has no Android equivalent -- spec D11).
#
# filter_label_parent_child (task-11 / RULINGS 25): "Parent › Sub" subcategory filter label, each
# name wrapped in Unicode isolates (U+2068 FSI / U+2069 PDI) so a name's own bidi direction can't
# corrupt the "›"-joined surrounding text.
#
# me_guest_* (task-12, spec D11 "guest Me tab shows local favorites + a sign-in card"): no Android
# source since Android always forces sign-in and has no guest state for this screen.
EXTRA_KEYS = {
    "filter_label_parent_child": "⁨%1$@⁩ › ⁨%2$@⁩",
    # share_app_promo: REFUSED above and re-authored here. Android's value claims "ad-free",
    # which spec D10 / 12 require dropping and which the embed rung (B3) makes false -- rung 3
    # plays YouTube's own player, ads included. The nl verb is "haal" (get), not "download": the
    # ban on that word is stated absolutely and this is the one string outside the offline surface
    # that still rendered it (gstack r1 P2).
    "share_app_promo": {
        "en": "Get FitrahTube for curated Islamic content!",
        "ar": "حمّل فطرة تيوب لمحتوى إسلامي منتقى!",
        "nl": "Haal FitrahTube voor geselecteerde islamitische content!",
    },
    # report_reason_limit (Plan C task 3): the disabled-row hint once 10 reasons are selected.
    # iOS-only -- Android has no cap and eats the backend's 400 (ContentReportController.java:161
    # validates @Size(max = 10) against an 11-value enum).
    "report_reason_limit": {
        "en": "You can select up to 10 reasons",
        "ar": "يمكنك اختيار ١٠ أسباب كحد أقصى",
        "nl": "Je kunt maximaal 10 redenen selecteren",
    },
    # banner_dismiss (gate wave-2 W5): the dismiss affordance on `TransientBanner`. iOS-only --
    # Android's Snackbar always auto-dismisses, so there is no source string for it.
    "banner_dismiss": {
        "en": "Dismiss",
        "ar": "إغلاق",
        "nl": "Sluiten",
    },
    # player_queue_ended (B5 task 2): the terminal card when a playlist runs out. iOS-only --
    # Android's empty-queue terminus is `StreamState.Idle`, a silent stop with no copy at all
    # (`PlayerViewModel.kt:1920-1923`), so there is no source string to port. Deliberately says
    # what happened, never why, and never offers a next step we do not have.
    "player_queue_ended": {
        "en": "You've reached the end of the playlist",
        "ar": "لقد وصلت إلى نهاية قائمة التشغيل",
        "nl": "Je hebt het einde van de afspeellijst bereikt",
    },
    # Degraded browse (Plan C task 2, CF-C3). iOS-only: Android has no degraded mode at all --
    # a bot-checked NewPipe call just surfaces an error -- so there is no source string to port.
    # Copy rule (plan Global Constraints): say WHAT is shown, never why. "Blocked by YouTube"
    # would be both jargon and an invitation to retry into a block.
    "browse_degraded_notice": {
        "en": "Showing recent uploads only",
        "ar": "عرض أحدث المقاطع فقط",
        "nl": "Alleen recente uploads worden getoond",
    },
    "me_guest_title": {
        "en": "Sign in to sync your favorites",
        "ar": "سجّل الدخول لمزامنة مفضلاتك",
        "nl": "Meld je aan om je favorieten te synchroniseren",
    },
    "me_guest_body": {
        "en": "Create a free account to sync your favorites across devices and unlock more features.",
        "ar": "أنشئ حسابًا مجانيًا لمزامنة مفضلاتك عبر أجهزتك والاستفادة من ميزات إضافية.",
        "nl": "Maak een gratis account aan om je favorieten op al je apparaten te synchroniseren en meer functies te ontgrendelen.",
    },
    "me_guest_sign_in": {
        "en": "Sign In",
        "ar": "تسجيل الدخول",
        "nl": "Aanmelden",
    },
    # player_standard_quality (B1 task 7): the persistent rung-2 pill. iOS-only -- Android's rung-2
    # equivalent is a toast on the degradation path, not a standing badge, so there is no source
    # string to port.
    "player_standard_quality": {
        "en": "Standard quality (360p)",
        "ar": "جودة قياسية (360p)",
        "nl": "Standaardkwaliteit (360p)",
    },
    # player_description_more/less (B1 task 8): the player metadata panel's description
    # expand/collapse toggle. iOS-only -- there is no Android `PlayerFragment` equivalent string to
    # port (grepped: no show_more/expand key exists there either).
    "player_description_more": {
        "en": "Show more",
        "ar": "عرض المزيد",
        "nl": "Meer weergeven",
    },
    "player_description_less": {
        "en": "Show less",
        "ar": "عرض أقل",
        "nl": "Minder weergeven",
    },
    # player_error_generic (B1 placeholder, re-purposed in B3): the terminal copy when the embed
    # rung's one reload is spent (`EmbedErrorPolicy`). There is no rung below it -- the owner
    # directive of 2026-08-27 removed every hand-off to YouTube -- so no "yet".
    "player_error_generic": {
        "en": "This video can't be played in the app",
        "ar": "لا يمكن تشغيل هذا الفيديو داخل التطبيق",
        "nl": "Deze video kan niet in de app worden afgespeeld",
    },
    # Safe Mode strings (B3 task 1). These WERE Android keys and were deleted from
    # values/strings.xml on 2026-08-25 (commit 2ffde712, "Remove the fake Safe Mode switch") --
    # Android's switch gated nothing, so it went. iOS keeps the setting because ruling 58 gives it
    # a real effect, so the strings have to be authored here or the row renders its own key.
    # The subtitle is NOT Android's old "Show only family-friendly content": iOS Safe Mode does no
    # content filtering (the catalog is admin-curated), it turns autoplay off. Playback is inside
    # the app for everyone (owner directive 2026-08-27), so the subtitle no longer claims that as a
    # Safe Mode effect. Promising filtering again would re-ship the placebo that got it deleted.
    # CF-B3-14: the autoplay clause is a promise B5 must keep before any user build.
    "settings_content": {"en": "Content", "ar": "المحتوى", "nl": "Inhoud"},
    "settings_safe_mode": {"en": "Safe Mode", "ar": "الوضع الآمن", "nl": "Veilige modus"},
    "settings_safe_mode_desc": {
        "en": "Turn off autoplay",
        "ar": "إيقاف التشغيل التلقائي",
        "nl": "Schakel automatisch afspelen uit",
    },
    # Embed rung (B3 tasks 3-4). iOS-only: Android has no IFrame embed player at all (grep of
    # android/app/src/main: no embed.html, no youtube-nocookie, no IFrame error codes), so there is
    # no source string to port for any of these.
    "player_embed_caption": {
        "en": "Playing in YouTube's player",
        "ar": "يتم التشغيل في مشغّل يوتيوب",
        "nl": "Speelt af in de YouTube-speler",
    },
    "player_embed_removed": {
        "en": "This video was removed",
        "ar": "تمت إزالة هذا الفيديو",
        "nl": "Deze video is verwijderd",
    },
    # No `player_embed_owner_only` / `player_open_in_youtube` / `player_open_in_youtube_confirm`:
    # owner directive 2026-08-27 bans every redirect and hand-off to YouTube, and naming YouTube as
    # the place to watch a video the embed refused is that redirect phrased as copy. IFrame errors
    # 101/150 now land on `player_stream_unavailable` like every other terminal reason.
    "player_embed_replay": {"en": "Replay", "ar": "إعادة التشغيل", "nl": "Opnieuw afspelen"},
    # player_cooldown_retry (B1 task 9, spec §6.6 `.cooldown` row): "Try again in {relative}" over
    # the live countdown `PlayerStateCopy.cooldownText` formats with `Format.duration`. iOS-only --
    # the persisted escalating cooldown (`ExtractionError.cooldown`) is an iOS-side resolver
    # concept with no Android string to port.
    "player_cooldown_retry": {
        "en": "Try again in %1$@",
        "ar": "حاول مرة أخرى خلال %1$@",
        "nl": "Probeer het over %1$@ opnieuw",
    },
    # player_quality_auto / player_quality_data_saver (B1 final review I5): the two word-labels in
    # `QualityOption.label` -- user-visible menu rows and a VoiceOver value, hardcoded English
    # until now. Android's own quality dialog is built from stream labels plus `quality_auto`-less
    # code paths, so there is no source string to port; authored here for all three locales.
    "player_quality_auto": {
        "en": "Auto",
        "ar": "تلقائي",
        "nl": "Automatisch",
    },
    "player_quality_data_saver": {
        "en": "Data Saver",
        "ar": "توفير البيانات",
        "nl": "Databesparing",
    },
    # player_announce_standard_quality (B1 final review M1): the VoiceOver announcement posted on a
    # transition INTO rung 2 (spec §6.6 Transitions row, verbatim). Distinct from
    # `player_standard_quality`, which is the standing pill label -- an event sentence, not a noun
    # phrase. iOS-only, same reason as the pill.
    "player_announce_standard_quality": {
        "en": "Playing in standard quality",
        "ar": "يتم التشغيل بجودة قياسية",
        "nl": "Afspelen in standaardkwaliteit",
    },
    # player_action_not_favorited (B1 task 10, spec §6.11 "Favorite, Not favorited" example): the
    # favorite toolbar button's VoiceOver value when unfavorited -- a constant "Favorite" label
    # (player_action_favorite) plus this value, mirroring player_action_favorited on the other
    # side. iOS-only: Android conveys the toggle through the icon/caption swap alone, with no
    # separate content-description string for the "off" state to port.
    "player_action_not_favorited": {
        "en": "Not favorited",
        "ar": "غير مُضاف للمفضلة",
        "nl": "Niet toegevoegd aan favorieten",
    },
    # Update-required gate (spec D3, wired 2026-09-01): the blocking screen when a published
    # `minAppVersion` exceeds the running build. iOS-only -- Android's `update_*` keys belong to
    # its self-updater island (DEAD_PREFIXES above; impossible on iOS). Named `app_update_*`, not
    # `update_*`: EXTRA_KEYS are never run through is_dead(), but staying outside the dead prefix
    # keeps these from ever shadowing a skipped Android key of the same name.
    "app_update_required_title": {
        "en": "Update required",
        "ar": "التحديث مطلوب",
        "nl": "Update vereist",
    },
    "app_update_required_message": {
        "en": "This version of FitrahTube is no longer supported. Please update to continue.",
        "ar": "هذا الإصدار من فطرة تيوب لم يعد مدعومًا. يرجى التحديث للمتابعة.",
        "nl": "Deze versie van FitrahTube wordt niet meer ondersteund. Werk de app bij om door te gaan.",
    },
    # Shorts chrome (B4 tasks 2-3). Both are iOS-only. Android's kebab content description is a
    # hard-coded "More options" literal in fragment_shorts_player.xml:44 (recorded as a defect in
    # playlist-detail-shorts.md 9.3, not a string resource we can port), and its scrub bar is an
    # ExoPlayer DefaultTimeBar with no accessibility label at all.
    "shorts_more_options_cd": {"en": "More options", "ar": "المزيد من الخيارات", "nl": "Meer opties"},
    "shorts_seek_cd": {"en": "Seek", "ar": "التنقل في المقطع", "nl": "Zoeken in video"},
    # offline_footer_format (Phase 3 Task 3): the Saved screen's storage footer, rendered by
    # `OfflineStorage.footer` -- authored here (ahead of Task 5's offline_* batch) because the
    # footer math ships with the pure engine. Args: %1$ count of saved items, %2$/%3$ localized
    # byte strings. Owner ruling 2026-09-01: "Save for offline" language, never "Download".
    "offline_footer_format": {
        "en": "%1$lld saved • %2$@ used • %3$@ available",
        "ar": "%1$lld محفوظة • %2$@ مستخدمة • %3$@ متاحة",
        "nl": "%1$lld opgeslagen • %2$@ gebruikt • %3$@ beschikbaar",
    },
    # --- Phase 3 Task 5: the Save-for-offline surface (owner ruling 2026-09-01). ---
    # The five refused settings_* keys, re-authored with "Save for offline" language; then the
    # offline_* batch. Copy rules: never "Download", never "ad-free"; refusal copy says WHAT,
    # never why. offline_action_cancel/retry deliberately absent -- the Android generics
    # `cancel` (strings.xml:564) and `retry` (strings.xml:194) already live in the catalog.
    "settings_downloads": {
        "en": "Save for offline",
        "ar": "الحفظ دون اتصال",
        "nl": "Offline opslaan",
    },
    "settings_download_quality": {
        "en": "Offline quality",
        "ar": "جودة الحفظ دون اتصال",
        "nl": "Offline kwaliteit",
    },
    # The quality-picker sheet title (SettingsView.swift:193, Task 6 keeps it) -- same value as
    # the row on purpose; two keys because Android shipped two.
    "settings_download_quality_title": {
        "en": "Offline quality",
        "ar": "جودة الحفظ دون اتصال",
        "nl": "Offline kwaliteit",
    },
    "settings_wifi_only": {
        "en": "Wi-Fi only",
        "ar": "شبكة Wi-Fi فقط",
        "nl": "Alleen wifi",
    },
    "settings_wifi_only_desc": {
        "en": "Only save for offline over Wi-Fi",
        "ar": "الحفظ دون اتصال عبر شبكة Wi-Fi فقط",
        "nl": "Video's alleen via wifi offline opslaan",
    },
    # The player toolbar's fourth button + the save sheet's confirm action.
    "offline_save": {
        "en": "Save for offline",
        "ar": "الحفظ دون اتصال",
        "nl": "Offline opslaan",
    },
    # The Saved screen's title (Task 6 renders it; authored with the batch).
    "offline_saved_title": {
        "en": "Saved",
        "ar": "المحفوظات",
        "nl": "Opgeslagen",
    },
    # Refusal copy: WHAT, never why -- never "the admin hasn't allowed it", never who blocked it.
    "offline_not_saveable": {
        "en": "This video can't be saved for offline",
        "ar": "لا يمكن حفظ هذا الفيديو للمشاهدة دون اتصال",
        "nl": "Deze video kan niet offline worden opgeslagen",
    },
    "offline_quality_title": {
        "en": "Choose quality",
        "ar": "اختر الجودة",
        "nl": "Kies kwaliteit",
    },
    "offline_quality_audio_only": {
        "en": "Audio only",
        "ar": "الصوت فقط",
        "nl": "Alleen audio",
    },
    # States the 360p ceiling (spec §17 risk row); same phrasing as player_standard_quality.
    "offline_quality_standard_ceiling": {
        "en": "Standard quality (360p)",
        "ar": "جودة قياسية (360p)",
        "nl": "Standaardkwaliteit (360p)",
    },
    # Row/button status captions (OfflineStatus, `running` reads as "saving").
    "offline_status_queued": {
        "en": "Waiting",
        "ar": "قيد الانتظار",
        "nl": "In wachtrij",
    },
    "offline_status_saving": {
        "en": "Saving…",
        "ar": "جارٍ الحفظ…",
        "nl": "Bezig met opslaan…",
    },
    "offline_status_paused": {
        "en": "Paused",
        "ar": "متوقف مؤقتًا",
        "nl": "Gepauzeerd",
    },
    "offline_status_completed": {
        "en": "Saved",
        "ar": "محفوظ",
        "nl": "Opgeslagen",
    },
    "offline_status_failed": {
        "en": "Failed",
        "ar": "فشل",
        "nl": "Mislukt",
    },
    "offline_status_cancelled": {
        "en": "Cancelled",
        "ar": "أُلغي",
        "nl": "Geannuleerd",
    },
    # Android's download_error_* meanings (strings.xml:133-141) re-authored without "Download"
    # (the FFmpeg merge codes were dropped with the engine, OfflineManager.ErrorCode).
    "offline_error_403": {
        "en": "Couldn't save this video",
        "ar": "تعذّر حفظ هذا الفيديو",
        "nl": "Kan deze video niet opslaan",
    },
    # WHAT, never WHY (owner directive): "Too many requests" named upstream throttling, i.e. how
    # the app talks to YouTube. `offline_error_network` below stays as it is -- "check your
    # connection" is actionable transport copy, not a refusal reason.
    "offline_error_429": {
        "en": "Couldn't save right now. Try again later",
        "ar": "تعذّر الحفظ الآن. حاول مرة أخرى لاحقًا",
        "nl": "Kan nu niet opslaan. Probeer het later opnieuw",
    },
    "offline_error_network": {
        "en": "Network error. Check your connection",
        "ar": "خطأ في الشبكة. تحقق من اتصالك",
        "nl": "Netwerkfout. Controleer je verbinding",
    },
    "offline_error_no_stream": {
        "en": "This video can't be saved for offline",
        "ar": "لا يمكن حفظ هذا الفيديو للمشاهدة دون اتصال",
        "nl": "Deze video kan niet offline worden opgeslagen",
    },
    "offline_error_invalid": {
        "en": "This video can't be saved",
        "ar": "لا يمكن حفظ هذا الفيديو",
        "nl": "Deze video kan niet worden opgeslagen",
    },
    "offline_error_unknown": {
        "en": "Something went wrong",
        "ar": "حدث خطأ ما",
        "nl": "Er is iets misgegaan",
    },
    "offline_empty_state": {
        "en": "Videos you save for offline appear here",
        "ar": "ستظهر هنا الفيديوهات التي تحفظها للمشاهدة دون اتصال",
        "nl": "Video's die je offline opslaat verschijnen hier",
    },
    # Row action matrix (OfflineStateMachine.actions); cancel/retry reuse the Android generics.
    "offline_action_pause": {
        "en": "Pause",
        "ar": "إيقاف مؤقت",
        "nl": "Pauzeren",
    },
    "offline_action_resume": {
        "en": "Resume",
        "ar": "استئناف",
        "nl": "Hervatten",
    },
    "offline_action_remove": {
        "en": "Remove",
        "ar": "إزالة",
        "nl": "Verwijderen",
    },
    "offline_action_open": {
        "en": "Open",
        "ar": "فتح",
        "nl": "Openen",
    },
    # nl: "Wissen", not "Verwijderen" -- Remove (a failed/cancelled row) and Delete (a saved
    # file) sat on adjacent rows with identical Dutch labels (Task 5 review fold-in 4).
    "offline_action_delete": {
        "en": "Delete",
        "ar": "حذف",
        "nl": "Wissen",
    },
    # Settings rows Task 6 adds under the Save-for-offline section.
    "settings_offline_storage": {
        "en": "Storage",
        "ar": "التخزين",
        "nl": "Opslag",
    },
    "settings_offline_clear": {
        "en": "Clear saved videos",
        "ar": "مسح الفيديوهات المحفوظة",
        "nl": "Opgeslagen video's wissen",
    },
    # The Settings Storage row's value ("Storage    1.2 GB used • 40 GB available") -- args are
    # pre-localized byte strings from `OfflineStorage.byteText`.
    "settings_offline_storage_value": {
        "en": "%1$@ used • %2$@ available",
        "ar": "%1$@ مستخدمة • %2$@ متاحة",
        "nl": "%1$@ gebruikt • %2$@ beschikbaar",
    },
    "settings_offline_clear_confirm": {
        "en": "Remove all saved videos? This can't be undone.",
        "ar": "هل تريد إزالة جميع الفيديوهات المحفوظة؟ لا يمكن التراجع عن ذلك.",
        "nl": "Alle opgeslagen video's verwijderen? Dit kan niet ongedaan worden gemaakt.",
    },
    # --- Phase 3 Task 8: Chromecast + AirPlay (spec §10). ---
    # No `player_action_cast` here: Android already ships it ("Cast to TV", strings.xml:301) and
    # the converter carries it into the catalog, so the toolbar's fifth slot reuses the existing
    # translated key rather than authoring a fourth spelling of the same word.
    # Spec §10: "surface 'Couldn't play on {device}' on failure (Android swallows it)". %@ is the
    # receiver's friendly name. Copy rule: WHAT, never why -- never "your network doesn't support
    # it", never an HTTP status.
    "cast_error_format": {
        "en": "Couldn't play on %@",
        "ar": "تعذّر التشغيل على %@",
        "nl": "Kan niet afspelen op %@",
    },
    # Spec §10's documented ceiling, in About -> Help: the cast stream URL is bound to the phone's
    # public IP, so the receiver can only fetch it from behind the same IPv4 NAT. Stated as the
    # user-visible condition, not the mechanism.
    "cast_help_network": {
        "en": "Casting needs your phone and the TV device on the same Wi-Fi network. It won't work over mobile data, or on networks that only use IPv6.",
        "ar": "يتطلب البث وجود هاتفك وجهاز التلفزيون على شبكة Wi-Fi نفسها. لن يعمل عبر بيانات الجوال أو على الشبكات التي تستخدم IPv6 فقط.",
        "nl": "Casten werkt alleen als je telefoon en het tv-apparaat op hetzelfde wifi-netwerk zitten. Het werkt niet via mobiele data of op netwerken die alleen IPv6 gebruiken.",
    },
}

def is_dead(key):
    if key in DEAD_PREFIX_EXCEPTIONS:
        return False
    return key.startswith(DEAD_PREFIXES)

def unescape(s):  # R5
    s = html.unescape(s)
    s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)  # R5.7
    s = re.sub(r"\\(['\"@?\u2019])", r"\1", s)  # R5.2/3/4/5 (\' \" \@ \? and the redundant \’)
    s = s.replace("\\n", "\n").replace("\\t", "\t")
    return s

# cso-F2 / A-M11: the signature regex must see the *whole* conversion set, not just the three
# conversions this converter itself emits. It used to be r"%(\d+)\$\d*(@|lld|f)", so any other
# conversion (%x, %p, %c, %u...) was invisible to arg_signature() and slid past R4's cross-locale
# subset check entirely. Flags/width/precision are captured as their own group so R8 can refuse a
# *numbered* precision specifier (%1$.1f), which the bare-path check below never sees.
SPECIFIER_RE = re.compile(r"%(\d+)\$([-+#0]*[\d.]*)(@|lld|ld|d|f|s|x|X|o|u|c|p)")

# A non-positional (bare) conversion of any kind. Deliberately does not treat a space as a flag, so
# ordinary prose ("50% off", "100%") is not mistaken for a specifier; %% and %#@substitution@ are
# excluded by the lookahead.
BARE_SPECIFIER_RE = re.compile(r"%(?!\d+\$|%|#@)[-+#0]*[\d.]*[@a-zA-Z]")
POSITIONAL_RE = re.compile(r"%\d+\$")

def rewrite_specifiers(s):  # R3 + R4
    s = s.replace("%%", "\u0000PCT\u0000")
    # R4 (cso-F2): mixing the two styles in one string is unrepresentable here -- the bare rewrite
    # below numbers its own finds from 1, so `"Version %1$@ %s"` became `"Version %1$@ %1$@"`,
    # whose signature is a *subset* of en's and therefore passed the cross-locale check while
    # actually consuming one argument twice. Refuse rather than guess which numbering was meant.
    if POSITIONAL_RE.search(s) and BARE_SPECIFIER_RE.search(s):
        raise ValueError(f"mixed positional and non-positional specifiers: {s!r}")
    n = [0]
    def bare(m):
        n[0] += 1
        width = m.group(1)
        if "." in width:  # R8: precision (e.g. %.1f) can't convert mechanically -- refuse, don't guess
            raise ValueError(f"unsupported precision specifier: {m.group(0)!r}")
        conv = {"s": "@", "d": "lld", "f": "f"}[m.group(2)]
        return f"%{n[0]}${width}{conv}"
    # (?!\d+\$) excludes already-numbered refs (e.g. %2$s), not bare zero-padded widths (e.g. %02d)
    # -- those two only differ by the trailing "$", so the lookahead must check for it specifically.
    s = re.sub(r"%(?!\d+\$)(\.?\d*)([sdf])", bare, s)
    s = re.sub(r"%(\d+)\$s", r"%\1$@", s)
    s = re.sub(r"%(\d+)\$(\d*)d", r"%\1$\2lld", s)  # keeps flags/width, e.g. %2$02d -> %2$02lld
    s = s.replace("\u0000PCT\u0000", "%%")
    # cso-F2: the old survivor guard only looked for a leftover bare s/d/f, so a translation could
    # append ` %@ %@ %@` to a one-argument key and pass this gate clean. At runtime
    # `String(format:locale:arguments:)` then over-consumes the CVarArg list and dereferences
    # whatever follows it as an `id` -- an arbitrary-pointer read surfaced into on-screen text, or
    # a crash. With no iOS CI, this local gate is the only thing between a translation PR and the
    # shipped binary.
    survivor = BARE_SPECIFIER_RE.search(s)
    if survivor:
        raise ValueError(f"non-positional specifier survived: {survivor.group(0)!r} in {s!r}")
    # A-M11: R8 above only fires on the bare path. A pre-numbered `%1$.1f` is untouched by both
    # numbered rewrites and used to be invisible to the old SPECIFIER_RE (its `\d*` cannot match a
    # `.`), so arg_signature() returned {} and the cross-locale check silently passed for that key.
    if any("." in flags for _num, flags, _conv in SPECIFIER_RE.findall(s)):
        raise ValueError(f"unsupported precision specifier: {s!r}")
    return s

def arg_signature(s):
    """R4: {argNum: conversion} used for the cross-locale subset check -- every occurrence, not
    just the last (gate wave-4 V4). The dict comprehension this replaces silently kept the last
    conversion for a repeated argNum, so a hostile `"%1$@ %1$lld"` against en `"%1$lld"` collapsed
    to {1: "lld"} and passed check_arg_subset clean, while the surviving `%1$@` made
    `String(format:)` read that Int64 argument as an object pointer at runtime. One argument can
    only have one type, so a repeated argNum with two conversions is refused here, at the one place
    every caller (plain strings, plural forms, substitutions) builds its signature."""
    sig = {}
    for num, _flags, conv in SPECIFIER_RE.findall(s):
        num = int(num)
        if sig.setdefault(num, conv) != conv:
            raise ValueError(f"argument %{num}$ used as both {sig[num]!r} and {conv!r}: {s!r}")
    return sig

def check_arg_subset(key, en_sig, loc, loc_sig):
    # R4: a translation's argument list must be a subset of the source's (same argNum -> same
    # conversion); anything else (extra arg, or a differing conversion for a shared argNum) is a
    # divergence the generator must refuse to guess at.
    for num, conv in loc_sig.items():
        if en_sig.get(num) != conv:
            raise ValueError(f"{key}: {loc} specifier %{num}${conv} has no matching en specifier ({en_sig})")

def plural_union_keys(en_plurals, ar_plurals, nl_plurals):
    # R4: the union, not just en_plurals.keys() -- a plural group that only exists in ar/nl must
    # still be visited so main() can refuse it (report it), instead of the old `for key, forms in
    # en_plurals.items()` loop silently never seeing it at all.
    return set(en_plurals) | set(ar_plurals) | set(nl_plurals)

def apply_overrides(key, value):
    fn = SPECIFIER_OVERRIDES.get(key)
    return fn(value) if fn else value

def locale_fallback(en_value, loc_value):
    """R7 as amended 2026-08-23 (strings-assets.md:303-313), verified with `xcstringstool compile`:
    an omitted `ar`/`nl` entry is NOT compiled into that locale's `Localizable.strings`, and
    Foundation does not fall back per key to the source language -- the raw key renders on screen.
    So a locale that has no value of its own gets en's, marked `needs_review` (the Xcode backlog
    stays visible). Never omit.

    Gate wave-2 W10: a value *equal* to en's is marked `needs_review` too. The old split called it
    a loanword translation ("Downloads", "YouTube"), which holds for a one-word brand term but not
    for the ~26 ar entries carrying whole English sentences -- and nothing here can tell the two
    apart, so the state said "translated" about prose no one has ever translated. Runtime output is
    identical either way (the value is the same string); only the backlog changes, and a truthful
    backlog is the point of the state field. A genuinely-fine loanword is cleared by a human in
    Xcode, which is exactly what `needs_review` asks for.

    Takes and returns either a plain string or a whole per-locale plural-forms dict -- the
    fallback is all-or-nothing per locale in both cases."""
    if loc_value is None or loc_value == en_value:
        return en_value, "needs_review"
    return loc_value, "translated"

def maybe_rewrite(name, text):
    # REFUSE keys (e.g. the %.1f abbreviated-count strings) are filtered out downstream in main()
    # and their value is never used -- don't run them through rewrite_specifiers, which now raises
    # (R8) on the precision specifier they contain, or load() would crash before main() gets a
    # chance to refuse them.
    return text if name in REFUSE else rewrite_specifiers(text)

def load(locale_dir):
    strings, plurals = {}, {}
    # R2: fixed merge order, hard-fail on duplicate key.
    for fname in ("strings.xml", "strings_list_states.xml", "strings_locale.xml", "strings_onboarding.xml"):
        for path in sorted(glob.glob(os.path.join(RES, locale_dir, fname))):
            root = ET.parse(path).getroot()
            for el in root:
                name = el.get("name")
                if el.get("translatable") == "false" or name is None:
                    continue
                if el.tag == "string":
                    if name in strings or name in plurals:
                        raise ValueError(f"duplicate key {name} in {path}")
                    text = "".join(el.itertext())
                    strings[name] = maybe_rewrite(name, unescape(text))
                elif el.tag == "plurals":
                    if name in strings or name in plurals:
                        raise ValueError(f"duplicate key {name} in {path}")
                    plurals[name] = {item.get("quantity"): maybe_rewrite(name, unescape("".join(item.itertext())))
                                     for item in el.findall("item")}
    return strings, plurals

def main(check=False):
    data = {loc: load(d) for loc, d in LOCALES.items()}
    en_strings, en_plurals = data["en"]
    out = {"sourceLanguage": "en", "version": "1.0", "strings": {}}
    skipped, refused = [], []

    for key, en in en_strings.items():
        if key in REFUSE:
            refused.append(key); continue
        if is_dead(key):
            skipped.append(key); continue
        en_val = apply_overrides(key, en)
        locs = {"en": {"stringUnit": {"state": "translated", "value": en_val}}}
        en_sig = arg_signature(en_val)
        for loc in ("ar", "nl"):
            raw = data[loc][0].get(key)
            v, state = locale_fallback(en_val, apply_overrides(key, raw) if raw is not None else None)
            check_arg_subset(key, en_sig, loc, arg_signature(v))
            locs[loc] = {"stringUnit": {"state": state, "value": v}}
        entry = {"localizations": locs}
        if key == "app_name":  # R7: brand name -- don't flag as needing translation
            entry["shouldTranslate"] = False
        out["strings"][key] = entry

    # EXTRA_KEYS: authored here, so every locale is deliberate -- a plain string is identical
    # across all three on purpose; a dict carries real per-locale text. Nothing to fall back to.
    for key, value in EXTRA_KEYS.items():
        # A-M9: assigning unconditionally meant that if Android ever shipped a key of the same
        # name, its real translations would be silently replaced by these hand-authored ones.
        if key in out["strings"]:
            raise ValueError(f"{key}: EXTRA_KEYS collides with an Android key")
        locs = value if isinstance(value, dict) else {loc: value for loc in ("en", "ar", "nl")}
        out["strings"][key] = {
            "localizations": {loc: {"stringUnit": {"state": "translated", "value": v}} for loc, v in locs.items()}
        }

    # R4: iterate the union of en/ar/nl plural keys, not just en_plurals -- a plural group that
    # exists only in ar/nl has no en source to key an xcstrings entry off of, so it must be
    # refused (reported below), not silently absent because the old loop never visited it.
    for key in sorted(plural_union_keys(en_plurals, data["ar"][1], data["nl"][1])):
        if key in REFUSE:
            refused.append(key); continue
        if key in SUBSTITUTION_PLURALS:
            continue  # emitted via the substitutions block below
        forms = en_plurals.get(key)
        if forms is None:
            refused.append(key); continue  # ar/nl-only plural group -- no en source
        if is_dead(key):
            skipped.append(key); continue
        locs = {}
        for loc in ("en", "ar", "nl"):
            # R7 (amended): a locale with no forms of its own falls back to en's whole forms dict,
            # marked needs_review -- omitting it would render the raw key at runtime. en is the
            # *source*, never a fallback of itself: running it through `locale_fallback` marked 22
            # English source units `needs_review` under W10's equal-to-en rule (gate wave-3 D3).
            # The plain-string loop above has always hardcoded `translated` for en.
            f, state = (forms, "translated") if loc == "en" else locale_fallback(forms, data[loc][1].get(key) or None)
            for cat, val in f.items():
                # H5: when en has no `cat` form (e.g. ar `few` with no en `few`), compare against
                # en's `other` -- never against the translation's own value, which would trivially
                # match itself and never catch a divergence.
                en_reference = forms.get(cat, forms["other"])
                check_arg_subset(f"{key}[{cat}]", arg_signature(en_reference), loc, arg_signature(val))
            plural = {cat: {"stringUnit": {"state": state, "value": f[cat]}} for cat in PLURAL_CATEGORIES if cat in f}
            locs[loc] = {"variations": {"plural": plural}}
        out["strings"][key] = {"localizations": locs}

    # §3b / RULINGS 37: substitutions form for the two decoupled-quantity plurals. Each locale's
    # top-level value is just the substitution token; the substitution's plural variations carry
    # the full text (which already contains only %1$@ after rewrite_specifiers numbered the single
    # bare %s arg as 1) and select their category from a synthetic arg 2 (the clamped selector int,
    # never rendered).
    for key in SUBSTITUTION_PLURALS:
        # A-M10: `en_plurals[key]` used to be a bare subscript, so renaming or removing
        # `video_views`/`live_watching_count` on Android killed the script with a traceback instead
        # of taking the `refused` path the rest of this file is careful to use.
        en_forms = en_plurals.get(key)
        if en_forms is None:
            refused.append(key); continue
        locs = {}
        en_other_sig = arg_signature(en_forms["other"])
        for loc in ("en", "ar", "nl"):
            # R7 (amended), same all-or-nothing per-locale fallback as the plain plural loop above,
            # and the same en-is-the-source exemption (gate wave-3 D3).
            forms, state = (en_forms, "translated") if loc == "en" else locale_fallback(en_forms, data[loc][1].get(key) or None)
            # R4: each category is checked against en's `other` (not its own-category en form --
            # these two keys emit a single shared substitution arg, so `other` is the one true
            # reference signature for every category in every locale, including en's own zero/two/
            # few/many forms).
            for cat, val in forms.items():
                check_arg_subset(f"{key}[{cat}]", en_other_sig, loc, arg_signature(val))
            plural = {cat: {"stringUnit": {"state": state, "value": forms[cat]}} for cat in PLURAL_CATEGORIES if cat in forms}
            locs[loc] = {
                "stringUnit": {"state": state, "value": "%#@arg@"},
                "substitutions": {
                    "arg": {"argNum": 2, "formatSpecifier": "lld", "variations": {"plural": plural}},
                },
            }
        out["strings"][key] = {"localizations": locs}

    if refused:
        # Two distinct refuse reasons share this list: R8/§7 (can't convert mechanically, e.g. a
        # %.1f precision specifier) and R4 (an ar/nl-only plural group with no en source).
        print(f"refused (cannot convert mechanically, or no en source): {sorted(refused)}", file=sys.stderr)

    verify(out)

    if check:
        # R5: regenerate into a temp file and diff against the committed catalog byte-for-byte --
        # never trust "no exception raised" alone, since a stale committed file with today's rules
        # applied would still raise nothing but no longer match what a real run would produce.
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as tmp:
            dump_catalog(out, tmp)
            tmp_path = tmp.name
        try:
            with open(tmp_path, "rb") as fresh, open(OUT, "rb") as committed:
                up_to_date = fresh.read() == committed.read()
        finally:
            os.remove(tmp_path)
        if not up_to_date:
            print("catalog out of date — run ios/scripts/convert-strings.py", file=sys.stderr)
            return 1
        print(f"{len(out['strings'])} keys, {len(skipped)} skipped, {len(refused)} refused")
        return 0
    with open(OUT, "w", encoding="utf-8") as fh:
        dump_catalog(out, fh)
    print(f"wrote {OUT}: {len(out['strings'])} keys; skipped {len(skipped)} dead; refused {len(refused)}")
    return 0

def dump_catalog(out, fh):
    # Single call site for both the --check temp-file write and the real write, so they can never
    # drift apart (different json.dump kwargs would make the byte-for-byte check above meaningless).
    json.dump(out, fh, ensure_ascii=False, indent=2, sort_keys=True)

def check_bare_specifier_rewrite():
    """H6 self-check: width/precision digits in a bare specifier. %02d keeps its zero-padded
    width; %.1f (a precision specifier) must raise (R8) rather than silently convert."""
    assert rewrite_specifiers("%02d") == "%1$02lld", rewrite_specifiers("%02d")
    try:
        rewrite_specifiers("%.1f")
    except ValueError:
        pass
    else:
        raise AssertionError("rewrite_specifiers('%.1f') should have raised (R8), not converted")

def check_plural_fallback_uses_other():
    """H5 self-check: when en has no `few` form, the cross-locale arg check must compare against
    en's `other` form -- never against the translation's own value (which would always match
    itself and silently swallow a real divergence). Synthetic ar `few` %s vs en `other` %1$lld."""
    en_forms = {"other": rewrite_specifiers("%d")}  # "%1$lld"
    ar_val = rewrite_specifiers("%s")  # "%1$@"
    en_reference = en_forms.get("few", en_forms["other"])
    try:
        check_arg_subset("synthetic[few]", arg_signature(en_reference), "ar", arg_signature(ar_val))
    except ValueError:
        pass
    else:
        raise AssertionError("ar `few` %s vs en `other` %d should have been caught, not skipped")

def check_plural_union_catches_ar_only_group():
    """R4 self-check: a plural key present only in ar (never in en) must show up in the union so
    main()'s `forms = en_plurals.get(key)` / `if forms is None: refused.append(key)` branch
    actually sees it. Iterating en_plurals.items() alone (the old code) would never visit it."""
    keys = plural_union_keys({"video_count": {"other": "x"}}, {"ar_only_group": {"other": "y"}}, {})
    assert "ar_only_group" in keys, keys
    assert keys == {"video_count", "ar_only_group"}, keys

def check_substitution_plural_specifier_mismatch_caught():
    """R4 self-check: a substitution-plural category's specifier is checked against en's `other`
    form -- a divergent conversion for the shared arg must raise, not pass through unchecked the
    way the substitutions loop used to (it built `locs` straight from `forms[cat]` with no
    check_arg_subset call at all)."""
    en_other_sig = arg_signature(rewrite_specifiers("%s"))  # {1: "@"}
    bad_ar_val = rewrite_specifiers("%d")  # {1: "lld"} -- wrong conversion for the shared arg
    try:
        check_arg_subset("synthetic_sub[other]", en_other_sig, "ar", arg_signature(bad_ar_val))
    except ValueError:
        pass
    else:
        raise AssertionError("substitution-plural ar specifier mismatch vs en other should raise")

def check_repeated_argnum_conflict_refused():
    """Gate wave-4 V4 self-check: one argument, two conversions. The signature must refuse it
    rather than collapse to the last one -- collapsed, `"%1$@ %1$lld"` is a clean subset of en's
    `"%1$lld"` and R4 passes a translation that reads an Int64 as a pointer. A repeat with the
    *same* conversion (`"%1$@ %1$@"`) is legal positional usage and must still pass."""
    try:
        arg_signature("%1$@ %1$lld")
    except ValueError:
        pass
    else:
        raise AssertionError("arg_signature('%1$@ %1$lld') should have raised (one arg, two types)")
    assert arg_signature("%1$@ %1$@") == {1: "@"}, arg_signature("%1$@ %1$@")
    assert arg_signature("%2$lld of %1$@") == {1: "@", 2: "lld"}

def check_locale_fallback_never_omits():
    """R7 self-check (amended rule): a locale absent from Android must still emit -- en's value
    marked `needs_review`, never nothing. Omitting it keeps the key out of that locale's compiled
    `Localizable.strings` and Foundation does not fall back per key, so the raw key renders. A
    locale value equal to en's is flagged too (W10): nothing mechanical can tell a loanword from
    untranslated prose, so the state stays honest and a human clears it. Plural forms take the same
    all-or-nothing path (the whole per-locale forms dict falls back at once); en itself never takes
    this path at all -- it is the source language (wave-3 D3)."""
    assert locale_fallback("Downloads", None) == ("Downloads", "needs_review")
    # W10: equal-to-en is indistinguishable from untranslated, so it is flagged, not blessed.
    assert locale_fallback("Downloads", "Downloads") == ("Downloads", "needs_review")
    assert locale_fallback("Downloads", "Downloaden") == ("Downloaden", "translated")
    en_forms = {"one": "%1$lld video", "other": "%1$lld videos"}
    assert locale_fallback(en_forms, None) == (en_forms, "needs_review")
    assert locale_fallback(en_forms, en_forms) == (en_forms, "needs_review")

def check_non_positional_specifier_refused():
    """cso-F2 self-check: a bare `%@`/`%x`/`%p` -- conversions this converter never emits and the
    old survivor guard (which only looked for s/d/f) never saw -- must be refused, not passed
    through invisibly to `String(format:)`. Ordinary prose containing a `%` must still convert."""
    for hostile in ("Hi %@ %@", "%x %p", "Version %1$@ (%2$@) %@ %@ %@"):
        try:
            rewrite_specifiers(hostile)
        except ValueError:
            pass
        else:
            raise AssertionError(f"rewrite_specifiers({hostile!r}) should have raised")
    assert rewrite_specifiers("50% off, 100% halal") == "50% off, 100% halal"

def check_mixed_specifier_styles_refused():
    """cso-F2 self-check: `"%1$@ %s"` used to rewrite to `"%1$@ %1$@"`, whose arg signature is a
    subset of en's -- so R4 accepted a translation that consumes one argument twice."""
    try:
        rewrite_specifiers("Version %1$@ %s")
    except ValueError:
        pass
    else:
        raise AssertionError("mixed positional + bare specifiers should have raised")

def check_numbered_precision_refused():
    """A-M11 self-check: R8 refuses `%.1f` on the bare path; `%1$.1f` slipped past both numbered
    rewrites and was invisible to the old SPECIFIER_RE, so its key's args went unchecked."""
    try:
        rewrite_specifiers("%1$.1f")
    except ValueError:
        pass
    else:
        raise AssertionError("rewrite_specifiers('%1$.1f') should have raised (R8/A-M11)")
    # ...and a numbered *width* (no precision) still converts, e.g. %2$02lld.
    assert rewrite_specifiers("%2$02d") == "%2$02lld", rewrite_specifiers("%2$02d")

def verify(out):
    """R9's one runnable check: spot-assert the hazards the doc calls out by name."""
    check_bare_specifier_rewrite()
    check_non_positional_specifier_refused()
    check_mixed_specifier_styles_refused()
    check_numbered_precision_refused()
    check_repeated_argnum_conflict_refused()
    check_locale_fallback_never_omits()
    check_plural_fallback_uses_other()
    check_plural_union_catches_ar_only_group()
    check_substitution_plural_specifier_mismatch_caught()
    def value_of(entry, loc):
        l = entry["localizations"].get(loc)
        if l is None:
            return None
        if "stringUnit" in l:
            return l["stringUnit"]["value"]
        return None

    # (b) the 6 %% keys survive as %%
    for key in ("dev_settings_generous_crop_desc", "download_item_content_description",
                "download_notification_progress", "player_download_in_progress",
                "playlist_video_downloading", "update_progress_percent"):
        entry = out["strings"].get(key)
        if entry is None:
            continue  # dead/out-of-scope keys may be absent; only assert when present
        v = value_of(entry, "en")
        assert v is not None and "%%" in v, f"{key}: expected %% to survive, got {v!r}"

    # (c)/(d) shorts_channel_handle: LRM (U+200E) survives, not stripped -- ar-only (R5.7)
    handle = out["strings"].get("shorts_channel_handle")
    if handle is not None:
        v = value_of(handle, "ar")
        assert v is not None and "\u200e" in v, f"shorts_channel_handle[ar]: LRM missing, got {v!r}"

    # video_count / video_views round-trip against the exact strings this task's tests assert on
    vc = out["strings"]["video_count"]["localizations"]["en"]["variations"]["plural"]
    assert vc["one"]["stringUnit"]["value"] == "%1$lld video", vc["one"]
    assert vc["other"]["stringUnit"]["value"] == "%1$lld videos", vc["other"]

    app_name = out["strings"]["app_name"]
    assert app_name.get("shouldTranslate") is False
    assert app_name["localizations"]["ar"]["stringUnit"]["value"] == "\u0641\u0637\u0631\u0629 \u062a\u064a\u0648\u0628"
    # R7 (amended) + W10: nl app_name is present in Android but identical to en, so it carries the
    # value under `needs_review` (omitting it would render the raw key under nl). `shouldTranslate:
    # false` above is what keeps the brand name out of the translation backlog.
    assert app_name["localizations"]["nl"]["stringUnit"] == {"state": "needs_review", "value": "FitrahTube"}

    # R7 (amended): about_version_format is absent from ar/nl on Android -> both locales carry the
    # English value under `needs_review`, so the runtime shows English instead of the raw key.
    avf = out["strings"]["about_version_format"]["localizations"]
    assert avf["en"]["stringUnit"]["value"] == "Version %1$@ (%2$@)", avf
    for loc in ("ar", "nl"):
        assert avf[loc]["stringUnit"] == {"state": "needs_review", "value": "Version %1$@ (%2$@)"}, avf[loc]

    # R7 (amended), the two keys the Task 13 review proved broken on screen.
    for key in ("dev_settings_title", "settings_downloads"):
        for loc in ("ar", "nl"):
            unit = out["strings"][key]["localizations"][loc]["stringUnit"]
            assert unit["value"], f"{key}[{loc}] must carry a value, not be omitted"

    # Every emitted key carries all three locales -- the invariant the amended R7 exists to hold.
    for key, entry in out["strings"].items():
        assert set(entry["localizations"]) == {"en", "ar", "nl"}, f"{key}: {sorted(entry['localizations'])}"

    # Task 5 review fold-in 4: Remove and Delete are adjacent Saved-row actions -- their labels
    # must differ in every locale, or the row shows two identical buttons.
    for loc in ("en", "ar", "nl"):
        remove = value_of(out["strings"]["offline_action_remove"], loc)
        delete = value_of(out["strings"]["offline_action_delete"], loc)
        assert remove != delete, f"offline_action_remove == offline_action_delete in {loc}: {remove}"

    # A refused key re-authored under EXTRA_KEYS (share_app_promo) is emitted from there, not Android.
    for key in REFUSE - set(EXTRA_KEYS):
        assert key not in out["strings"], f"{key} should have been refused, not emitted"
    assert out["strings"]["share_app_promo"]["localizations"]["en"]["stringUnit"]["value"] == EXTRA_KEYS["share_app_promo"]["en"]

    # EXTRA_KEYS: all three locales present and identical, with the FSI/PDI isolates intact.
    plc = out["strings"]["filter_label_parent_child"]["localizations"]
    assert set(plc) == {"en", "ar", "nl"}, plc
    plc_values = {loc: value_of(out["strings"]["filter_label_parent_child"], loc) for loc in ("en", "ar", "nl")}
    assert plc_values["en"] == plc_values["ar"] == plc_values["nl"] == "⁨%1$@⁩ › ⁨%2$@⁩", plc_values

    for key in SUBSTITUTION_PLURALS:
        entry = out["strings"][key]
        en = entry["localizations"]["en"]
        assert en["stringUnit"]["value"] == "%#@arg@"
        sub = en["substitutions"]["arg"]
        assert sub["argNum"] == 2 and sub["formatSpecifier"] == "lld"
        for cat, v in sub["variations"]["plural"].items():
            val = v["stringUnit"]["value"]
            assert "%2$" not in val, f"{key}[{cat}]: substitution variation must not reference arg 2: {val!r}"

if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv))
