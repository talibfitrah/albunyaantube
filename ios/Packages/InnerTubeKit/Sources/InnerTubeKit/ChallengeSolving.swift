import Foundation

/// The backend's answers for one HD upgrade (spec §4.5 `/solve`): challenge -> solved value, per kind.
public struct SolvedChallenges: Sendable, Equatable, Decodable {
    public var n: [String: String]
    public var s: [String: String]

    public init(n: [String: String], s: [String: String]) {
        self.n = n
        self.s = s
    }
}
