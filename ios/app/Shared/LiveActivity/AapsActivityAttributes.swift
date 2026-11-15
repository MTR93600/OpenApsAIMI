import ActivityKit
import Foundation
import os

/// The Live Activity data, shared by the app (which starts and updates the activity) and the widget
/// extension (which draws it). Target membership: AAPS, AAPS2 and both Live Activity extensions.
///
/// It imports no Kotlin. The extension is a separate process and cannot load the AapsShared
/// framework, so everything it shows travels in `ContentState`.
struct AapsActivityAttributes: ActivityAttributes {

    /// When this activity was started. The app recreates an activity older than 60 minutes while it
    /// is in the foreground, because iOS ends a Live Activity after 8 hours.
    let createdAt: Date

    /// The state the Kotlin plugin sends: `WidgetStateJson` (in `:ui`) plus the three show flags of
    /// `LiveActivityPayload` (in `:plugins:sync`). The property names are the JSON keys; change both
    /// sides together.
    ///
    /// Glucose is in mg/dL, times are epoch milliseconds. Texts are finished and translated by Kotlin.
    /// No text here depends on the time it is shown: the age comes from `bgTime`.
    struct ContentState: Codable, Hashable {
        let v: Int
        let bgText: String
        let bgMgdl: Double?
        let bgTime: Double?
        /// When the reading turns too old to trust. The app's 9 minute rule, sent by Kotlin, so this
        /// side holds no copy of it. Nil without a reading.
        let staleAt: Double?
        /// Already too old when Kotlin built the state.
        let stale: Bool
        /// `LOW`, `IN_RANGE` or `HIGH`. Says nothing about the age.
        let range: String
        /// A Kotlin `TrendArrow` name, nil when unknown.
        let trend: String?
        let trendText: String?
        let unitText: String
        let deltaMgdl: Double?
        let deltaText: String
        /// Nil when Kotlin had no real number (NaN). `iobText` then holds the "no value" text.
        let iob: Double?
        let iobText: String
        let iobLabel: String
        let cob: Double?
        let cobText: String
        let cobLabel: String
        let carbsReqGrams: Int
        let carbsReqText: String?
        let lastLoopTime: Double?
        let loopLabel: String
        let mode: String
        let modeText: String
        let modeActive: Bool
        let staleText: String
        /// `[[time, mgdl], ...]`, oldest first. Empty unless the chart is shown.
        let chart: [[Double]]
        let showChart: Bool
        let showIobCob: Bool
        let showLoop: Bool

        var bgDate: Date? { bgTime.map { Date(timeIntervalSince1970: $0 / 1000) } }
        var lastLoopDate: Date? { lastLoopTime.map { Date(timeIntervalSince1970: $0 / 1000) } }

        /// The moment iOS should mark the activity stale. Without a reading that is now.
        var staleDate: Date {
            staleAt.map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()
        }

        /// True when the reading must not look fresh at `date`: there is none, it was already old
        /// when built, or it has turned old since. The same edge as Kotlin's
        /// `WidgetState.isStaleAt`: fresh only while strictly before `staleAt`.
        func isStale(at date: Date) -> Bool {
            guard let staleAt else { return true }
            return stale || date.timeIntervalSince1970 * 1000 >= staleAt
        }

        /// The colour class at `date`, as Kotlin's `WidgetState.bgColorClass`. A stale value is
        /// `.stale` whatever the range.
        func colorClass(at date: Date) -> BgColorClass {
            if isStale(at: date) || bgMgdl == nil { return .stale }
            switch range {
            case "LOW": return .low
            case "HIGH": return .high
            default: return .inRange
            }
        }
    }

    enum BgColorClass {
        case low, inRange, high, stale
    }
}

/// The file through which the app hands the widget state to the home and lock screen widgets.
/// Shared by the app (which writes it) and the widget extension (which reads it), so the place of the
/// file is written down only here.
///
/// The file lives in the App Group container. The group id comes from the build setting
/// `AAPS_APP_GROUP_ID` through the Info.plist key `AapsAppGroupId`; nothing is hardcoded here. Without
/// the build setting, or without the App Group entitlement (the Xcode steps are not done yet), there is
/// no container: writing and reading then do nothing and log it once. It never crashes.
///
/// AAPS and AAPS2 may share one App Group (one entitlements file serves both), so the file name holds
/// the bundle id of the app. The widget of AAPS2 can never show the data of AAPS.
///
/// The content is `WidgetStateJson` from Kotlin with the chart and the details: health data. It is
/// written with file protection "until first unlock", the strongest level a lock screen widget can
/// still read.
enum AapsWidgetStore {

    /// The `kind` of the home screen widget. The app reloads timelines of this kind after a write.
    static let kind = "AapsHomeWidget"

    /// The JSON version this code understands (`WidgetStateJson.VERSION`). Another version is shown as
    /// no data, never guessed.
    static let supportedVersion = 1

    static let log = Logger(subsystem: "app.aaps", category: "HomeWidget")

    private static let groupIdKey = "AapsAppGroupId"

    /// The App Group id, or nil when the build setting is not set.
    static var groupId: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: groupIdKey) as? String,
              !id.isEmpty, !id.contains("$(")
        else { return nil }
        return id
    }

    /// The bundle id of the app that owns the data. In the extension (`<app id>.LiveActivity`) it is the
    /// id of the app the extension is embedded in.
    static var appBundleId: String? {
        guard let id = Bundle.main.bundleIdentifier else { return nil }
        guard Bundle.main.bundleURL.pathExtension == "appex" else { return id }
        var parts = id.split(separator: ".")
        guard parts.count > 1 else { return nil }
        parts.removeLast()
        return parts.joined(separator: ".")
    }

    /// The file, or nil without an App Group container.
    static var fileURL: URL? {
        guard let groupId, let app = appBundleId,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
        else { return nil }
        return container.appendingPathComponent("widget-state.\(app).json", isDirectory: false)
    }

    /// Writes the state. Returns false, and writes nothing, without a container or on an error.
    static func write(_ json: String) -> Bool {
        guard let url = fileURL else { return false }
        guard let data = json.data(using: .utf8) else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            log.error("Widget state could not be written: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    /// The last saved file, or nil when there is none or no container. The widget extension decodes it.
    static func readData() -> Data? {
        guard let url = fileURL else { return nil }
        return try? Data(contentsOf: url)
    }
}
