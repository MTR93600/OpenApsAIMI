import ActivityKit
import Foundation

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
