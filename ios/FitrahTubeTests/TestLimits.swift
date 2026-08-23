import Testing

extension Trait where Self == TimeLimitTrait {
    /// Closest we can express to CLAUDE.md's 30s per-test budget — Swift Testing's
    /// TimeLimitTrait is minute-granular.
    static var perTest: Self { .timeLimit(.minutes(1)) }
}
