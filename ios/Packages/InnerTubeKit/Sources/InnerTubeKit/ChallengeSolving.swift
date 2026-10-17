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

/// The app's backend answers sts and solves sig/n for the `tv_samsung` HD upgrade (spec §4.4, R20). InnerTubeKit never
/// learns about the backend or its auth; `nil` in `InnerTube.init` keeps HD off.
public protocol ChallengeSolving: Sendable {
    /// `{playerId, sts}` of the player version the backend solves with (R26).
    func playerInfo() async throws -> SolverPlayer
    /// Only `{playerId, n, s}` leave the device (R21).
    func solve(playerId: String, n: [String], s: [String]) async throws -> SolvedChallenges
}

public struct SolverPlayer: Sendable, Equatable {
    public var id: String
    public var signatureTimestamp: Int

    public init(id: String, signatureTimestamp: Int) {
        self.id = id
        self.signatureTimestamp = signatureTimestamp
    }
}

public enum ChallengeSolverError: Error, Sendable, Equatable {
    /// 429, 5xx, a stopped solver, a body that does not decode: the skip window opens for `retryAfterSeconds` clamped to
    /// [0, 120] s, else 120 s (R23).
    case unavailable(retryAfterSeconds: Int?)
    /// 409: the backend no longer holds that player. The player cache is cleared and no window opens (M14).
    case unknownPlayer
}
