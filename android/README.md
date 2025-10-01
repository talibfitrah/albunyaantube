# Albunyaan Tube Android Skeleton

Phase 5 begins the Android client by defining the app-level navigation graph and bottom navigation contract. This folder holds design-time assets and Kotlin/xml scaffolding to be imported into the future Android Studio project.

## Module Overview
- `app/src/main/res/navigation/app_nav_graph.xml` — canonical graph with splash → onboarding → main shell destinations.
- `app/src/main/res/menu/bottom_nav_menu.xml` — persistent bottom navigation items mapping to primary destinations.
- `app/src/main/res/layout/fragment_onboarding.xml` & `strings_onboarding.xml` — onboarding carousel skeleton with CTA/help placeholders.
- `app/src/main/res/layout/fragment_locale_settings.xml` & LocaleManager stubs — locale switcher strategy aligned with admin UI and i18n plan.
- `app/src/main/java/com/albunyaan/tube/data/paging/*` — Paging 3 repository and source sketches mapping backend cursors to Android paging.
- `app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt` — single-activity shell with `NavHostFragment` placeholder and state hand-off comments.
- `app/src/main/java/com/albunyaan/tube/ui/*Fragment.kt` — stub fragments representing tab destinations.
- `app/src/androidTest/java/com/albunyaan/tube/navigation/NavigationGraphTest.kt` — instrumentation sketch that will verify tab state retention once the project is bootstrapped.

At this stage a minimal Gradle project structure is checked in. Run the following from the `android/` directory to generate a wrapper and assemble the skeleton app:

```sh
gradle wrapper
./gradlew assembleDebug
```

The layouts and Kotlin classes are mostly placeholders, but the build compiles and exercises Paging/filter/list-state stubs so engineers can begin iterating immediately once API integrations start.
