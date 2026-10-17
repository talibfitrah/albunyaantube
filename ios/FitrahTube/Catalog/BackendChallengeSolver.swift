import Foundation
import InnerTubeKit

/// FitrahAPI-backed `ChallengeSolving` (spec §4.4, R20, R21, R24): `GET api/playback/player` and `POST api/playback/solve`
/// over the container's signed transport. Sends only `{playerId, n, s}`: never a video id, URL, visitorData or locale.
/// A pure HTTP mapper; the 30-minute player cache, the timeouts and the skip window live in `StreamResolver`.
///
/// Every refusal is TYPED here: `StreamResolver` reads an untyped error as `.unavailable(nil)`, which would drop the
/// 429's `Retry-After`. An `ACCOUNT_*` 403 is the account's business, posted by `AuthorizedTransport`'s envelope; to
/// this mapper it is one more non-200, like the bare 403, the filter's 503 and a 401 that outlived the refresh.
struct BackendChallengeSolver: ChallengeSolving {
    let transport: HTTPTransport
    let baseURL: URL

    func playerInfo() async throws -> SolverPlayer {
        let response = try await send(HTTPRequest(method: "GET", url: baseURL.appending(path: "api/playback/player"),
                                                  headers: [:], body: nil))
        let wire: PlayerWire = try decode(response)
        return SolverPlayer(id: wire.playerId, signatureTimestamp: wire.sts)
    }

    func solve(playerId: String, n: [String], s: [String]) async throws -> SolvedChallenges {
        let body = try JSONEncoder().encode(SolveBody(playerId: playerId, n: n, s: s))
        return try decode(try await send(HTTPRequest(method: "POST", url: baseURL.appending(path: "api/playback/solve"),
                                                     headers: ["Content-Type": "application/json"], body: body)))
    }

    /// M16: delta-seconds only. An HTTP-date, a sign or anything else is nil, which the resolver reads as 120 s.
    static func retryAfter(_ headers: [String: String]) -> Int? {
        guard let value = headers.first(where: { $0.key.caseInsensitiveCompare("Retry-After") == .orderedSame })?
                .value.trimmingCharacters(in: .whitespaces),
              value.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(value)
    }

    private struct PlayerWire: Decodable { let playerId: String; let sts: Int }
    private struct SolveBody: Encodable { let playerId: String; let n: [String]; let s: [String] }

    private func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        #if DEBUG
        let started = ContinuousClock.now
        defer { print("BackendChallengeSolver: \(request.url.lastPathComponent) \(ContinuousClock.now - started)") }   // Task 23
        #endif
        let response: HTTPResponse
        do {
            response = try await transport.send(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            throw ChallengeSolverError.unavailable(retryAfterSeconds: nil)
        }
        switch response.status {
        case 200: return response
        case 409: throw ChallengeSolverError.unknownPlayer
        default: throw ChallengeSolverError.unavailable(retryAfterSeconds: Self.retryAfter(response.headers))
        }
    }

    private func decode<T: Decodable>(_ response: HTTPResponse) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: response.body) }
        catch { throw ChallengeSolverError.unavailable(retryAfterSeconds: nil) }
    }
}
