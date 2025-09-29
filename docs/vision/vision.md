# Albunyaan Tube Vision & Discovery Summary

## Mission
Deliver a trustworthy, ad-free Islamic video experience curated strictly upon Quran and Sunnah per the understanding of the Sahabah. Albunyaan Tube surfaces only pre-approved YouTube IDs, avoids algorithmic rabbit holes, and provides respectful offline-first access for families worldwide.

## Goals
1. **Halal-first catalog**: 100% of surfaced content is vetted by admin/moderation workflows.
2. **Frictionless consumption**: Mid-range Android device cold start under 2.5 seconds, with smooth playback and background audio.
3. **Operational governance**: All catalog mutations audited; moderation turnaround <24h.
4. **Inclusive localization**: Launch with English, Arabic (RTL), and Dutch with full UI parity.

## Success Metrics
- **Adherence**: <0.1% of surfaced items flagged for policy violation (monitored via audit + user reports).
- **Performance**: 95th percentile home feed render <1.2s after data available.
- **Engagement**: ≥60% of sessions start playback; ≥40% use audio-only or downloads weekly.
- **Moderation SLA**: 90% of proposals triaged within 12 hours.
- **Localization Quality**: Zero blocker bugs in L10n QA checklist across en/ar/nl.

## Non-Goals
- No user-generated comments, likes, or algorithmic recommendations.
- No monetization features (ads, subscriptions) in initial scope.
- No content ingestion beyond allow-listed YouTube IDs.

## Stakeholders & Responsibilities
- **Product Lead**: requirements, roadmap, acceptance (refs: [`docs/roadmap/roadmap.md`](../roadmap/roadmap.md)).
- **Principal Engineer**: architecture, security, performance (refs: [`docs/architecture/solution-architecture.md`](../architecture/solution-architecture.md)).
- **Design Lead**: UX/UI fidelity (refs: [`docs/ux/ui-spec.md`](../ux/ui-spec.md)).
- **Localization Lead**: en/ar/nl strategy (refs: [`../i18n/strategy.md`](../i18n/strategy.md)).
- **Moderation Lead**: content policy, audit (refs: [`../security/threat-model.md`](../security/threat-model.md)).

## Assumptions
- Access to NewPipeExtractor and YouTube assets complies with fair-use and platform ToS.
- Content rights for offline downloads granted by Albunyaan Tube policy board.
- Admin and moderator roles staffed 24/7 for SLA compliance.
- Android is primary client at launch; web clients (consumer-facing) are out of scope.

## Risks (see [`docs/risk-register.md`](../risk-register.md))
- YouTube policy changes affecting extraction.
- Localization complexities in Arabic script shaping.
- Potential legal exposure for offline downloads.

## Traceability
This vision maps to detailed acceptance criteria in [`docs/acceptance/criteria.md`](../acceptance/criteria.md) and the backlog in [`docs/backlog/product-backlog.csv`](../backlog/product-backlog.csv).
