# Risk Register

| ID | Risk | Impact | Likelihood | Owner | Mitigation | Status |
| --- | --- | --- | --- | --- | --- | --- |
| RSK-001 | YouTube API changes break NewPipeExtractor | High | Medium | Principal Engineer | Monitor upstream releases; maintain fork; build fallback metadata cache. | Open |
| RSK-002 | Localization quality issues in Arabic shaping | Medium | Medium | Localization Lead | Adopt Noto fonts, run pseudo-localization, include Arabic QA early. | Open |
| RSK-003 | Legal challenge for offline downloads | High | Low | Product Lead | Secure legal review, provide EULA, enforce download policy flag. | Open |
| RSK-004 | Moderator backlog exceeding SLA | Medium | Medium | Moderation Lead | Add alerts for >12h pending, scale staffing, implement bulk actions. | Open |
| RSK-005 | Performance degradation on low-end devices | Medium | Medium | Android Lead | Profile using Macrobenchmark, optimize image caching (Coil), enforce 80KB payload budget. | Open |
| RSK-006 | Security breach via stolen refresh token | High | Low | Security Lead | Device binding, refresh rotation, anomaly detection, revoke tokens. | Open |
| RSK-007 | Redis outage causing API latency | Medium | Low | DevOps Lead | Implement cache fallback with circuit breaker, monitor with Prometheus alerts. | Open |

Risks map to mitigation tasks in [`docs/backlog/product-backlog.csv`](backlog/product-backlog.csv) and security controls in [`docs/security/threat-model.md`](security/threat-model.md).
