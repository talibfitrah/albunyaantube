import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Spec D3 "update required" gate: the pure decision behind `FitrahTubeApp`'s blocking
/// `UpdateRequiredView` overlay, extracted (like `isRemoteConfigRefreshDue`) so it is
/// testable without a running scene.
struct UpdateGateTests {
    private func config(min: String) -> RemoteConfig {
        RemoteConfig(schemaVersion: 1, minAppVersion: min, resolverOrder: ["embed"],
                     manifestCacheSeconds: 0, clients: [:])
    }

    @Test func aMinAppVersionAboveTheCurrentVersionBlocks() {
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: config(min: "1.1.0")))
        // Numeric-segment compare, not lexicographic: "1.0.2" < "1.0.10".
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.2", config: config(min: "1.0.10")))
    }

    @Test func anEqualOrNewerVersionNeverBlocks() {
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.1.0", config: config(min: "1.1.0")) == false)
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.2.0", config: config(min: "1.1.0")) == false)
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.10", config: config(min: "1.0.9")) == false)
    }

    /// An unparseable `minAppVersion` must fail open. (A truly ABSENT `minAppVersion` cannot reach
    /// the gate: the field is non-optional, so a fetched JSON without it fails to decode and
    /// `RemoteConfigStore.refresh()` keeps the previous config.) A missing
    /// `CFBundleShortVersionString` (nil appVersion) must fail open too.
    @Test func garbageOrMissingVersionsNeverBlock() {
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: config(min: "garbage")) == false)
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: config(min: "")) == false)
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: nil, config: config(min: "99.0.0")) == false)
    }

    /// The shipped default (minAppVersion 1.0.0) must never block the 1.0.0 build it ships in.
    @Test func theBundledDefaultNeverBlocksItsOwnVersion() {
        #expect(RemoteConfig.bundledDefault.minAppVersion == "1.0.0")
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: .bundledDefault) == false)
    }

    /// A later refresh that LOWERS `minAppVersion` un-blocks without a reinstall: the gate is
    /// re-evaluated from the new config alone, holding no memory of having blocked.
    @Test func aLoweredMinAppVersionUnblocksOnTheNextEvaluation() {
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: config(min: "2.0.0")))
        #expect(FitrahTubeApp.isUpdateRequired(appVersion: "1.0.0", config: config(min: "1.0.0")) == false)
    }
}
