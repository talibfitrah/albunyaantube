# Albunyaan Tube Android Skeleton

Phase 5 begins the Android client by defining the app-level navigation graph and bottom navigation contract. This folder holds design-time assets and Kotlin/xml scaffolding to be imported into the future Android Studio project.

## Module Overview
- `app/src/main/res/navigation/app_nav_graph.xml` — canonical graph with splash → onboarding → main shell destinations.
- `app/src/main/res/menu/bottom_nav_menu.xml` — persistent bottom navigation items mapping to primary destinations.
- `app/src/main/java/com/albunyaan/tube/ui/MainActivity.kt` — single-activity shell with `NavHostFragment` placeholder and state hand-off comments.
- `app/src/main/java/com/albunyaan/tube/ui/*Fragment.kt` — stub fragments representing tab destinations.
- `app/src/androidTest/java/com/albunyaan/tube/navigation/NavigationGraphTest.kt` — instrumentation sketch that will verify tab state retention once the project is bootstrapped.

At this stage no Gradle files are provided; the intent is to unblock design/architecture documentation and give engineering a concrete blueprint when the Android project is initialized.
