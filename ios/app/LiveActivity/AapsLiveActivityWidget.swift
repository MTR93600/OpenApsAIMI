import ActivityKit
import Charts
import SwiftUI
import WidgetKit

/// The Live Activity: lock screen, Dynamic Island (expanded, compact, minimal) and the Apple Watch
/// Smart Stack (`.small`).
///
/// Safety: the app may be frozen (force quit, Bluetooth off), and then nothing updates this view.
/// So the age is judged here, at the time of drawing: `context.isStale` (iOS sets it at `staleDate`,
/// which is the reading's `staleAt`) or `ContentState.isStale(at:)`. A stale value is struck out,
/// grey and has no arrow and no delta. The age is always shown with `Text(date, style: .relative)`,
/// which keeps counting without the app.
///
/// Every text comes from Kotlin, already translated. Nothing here is joined into a sentence.
struct AapsLiveActivityWidget: Widget {

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AapsActivityAttributes.self) { context in
            LockScreenView(state: context.state, stale: isStale(context))
                .activityBackgroundTint(Color.black.opacity(0.65))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            let stale = isStale(context)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    GlucoseView(state: state, stale: stale, font: .title)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    DeltaAndAgeView(state: state, stale: stale, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    DetailsView(state: state, stale: stale)
                }
            } compactLeading: {
                GlucoseView(state: state, stale: stale, font: .body)
            } compactTrailing: {
                if stale {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.gray)
                        .accessibilityLabel(Text(state.staleText))
                } else {
                    Text(state.deltaText).font(.caption2).foregroundStyle(.secondary)
                }
            } minimal: {
                GlucoseView(state: state, stale: stale, font: .caption2, showArrow: false)
            }
        }
        .supplementalActivityFamilies([.small])
    }

    private func isStale(_ context: ActivityViewContext<AapsActivityAttributes>) -> Bool {
        context.isStale || context.state.isStale(at: Date())
    }
}

// MARK: - Views

private struct LockScreenView: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    @Environment(\.activityFamily) private var family

    var body: some View {
        switch family {
        case .small:
            // Apple Watch Smart Stack and CarPlay: only what fits.
            HStack(spacing: 6) {
                GlucoseView(state: state, stale: stale, font: .title3)
                AgeView(state: state)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(8)
        default:
            VStack(spacing: 6) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        GlucoseView(state: state, stale: stale, font: .largeTitle)
                        DeltaAndAgeView(state: state, stale: stale, alignment: .leading)
                    }
                    Spacer(minLength: 8)
                    InfoColumn(state: state, stale: stale)
                }
                if state.showChart && !state.chart.isEmpty {
                    MiniChart(state: state, stale: stale)
                        .frame(height: 44)
                }
            }
            .padding(12)
        }
    }
}

/// The reading. Struck out and grey, without an arrow, when stale.
private struct GlucoseView: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    let font: Font
    var showArrow = true

    var body: some View {
        let color = (stale ? AapsActivityAttributes.BgColorClass.stale : state.colorClass(at: Date())).color
        HStack(spacing: 2) {
            Text(state.bgText)
                .font(font)
                .fontWeight(.bold)
                .strikethrough(stale)
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if showArrow && !stale {
                TrendArrowView(trend: state.trend)
                    .font(font)
                    .foregroundStyle(color)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(state.bgText))
        .accessibilityValue(Text(stale ? state.staleText : (state.trendText ?? "")))
    }
}

/// Delta (only for a fresh reading) and the age of the reading, which counts by itself.
private struct DeltaAndAgeView: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    let alignment: HorizontalAlignment

    var body: some View {
        VStack(alignment: alignment, spacing: 1) {
            if !stale {
                Text(state.deltaText).font(.subheadline)
            }
            AgeView(state: state)
                .font(.caption)
                .foregroundStyle(stale ? Color.gray : Color.secondary)
        }
    }
}

private struct AgeView: View {

    let state: AapsActivityAttributes.ContentState

    var body: some View {
        if let date = state.bgDate {
            Text(date, style: .relative)
                .monospacedDigit()
                .lineLimit(1)
        }
    }
}

/// IOB, COB, carbs required and the loop, each only when the user asked for it.
/// When the reading is stale these values are just as old (the app did not run since), so IOB and
/// COB are struck out and grey, and "carbs required" and the running mode are not shown at all.
private struct InfoColumn: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            if state.showIobCob {
                LabeledValue(label: state.iobLabel, value: state.iobText, stale: stale)
                LabeledValue(label: state.cobLabel, value: state.cobText, stale: stale)
                if !stale, let carbsReq = state.carbsReqText {
                    Text(carbsReq).font(.caption).foregroundStyle(.orange)
                }
            }
            if state.showLoop {
                HStack(spacing: 4) {
                    Text(state.loopLabel).foregroundStyle(.secondary)
                    if let loop = state.lastLoopDate {
                        Text(loop, style: .relative).monospacedDigit()
                    }
                }
                .font(.caption)
                .lineLimit(1)
                if !stale && state.modeActive {
                    Text(state.modeText).font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }
}

private struct LabeledValue: View {

    let label: String
    let value: String
    let stale: Bool

    var body: some View {
        HStack(spacing: 4) {
            Text(label).foregroundStyle(.secondary)
            Text(value)
                .strikethrough(stale)
                .foregroundStyle(stale ? Color.gray : Color.primary)
        }
        .font(.caption)
        .lineLimit(1)
    }
}

/// The expanded Dynamic Island: the same rows as the lock screen, then the chart.
private struct DetailsView: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool

    var body: some View {
        VStack(spacing: 4) {
            HStack {
                InfoColumn(state: state, stale: stale)
                Spacer()
            }
            if state.showChart && !state.chart.isEmpty {
                MiniChart(state: state, stale: stale)
                    .frame(height: 36)
            }
        }
    }
}

/// The readings of the last 3 hours. Real readings only (Kotlin leaves gaps empty).
private struct MiniChart: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool

    var body: some View {
        let points = state.chart.compactMap { pair -> (Date, Double)? in
            guard pair.count == 2 else { return nil }
            return (Date(timeIntervalSince1970: pair[0] / 1000), pair[1])
        }
        Chart(points.indices, id: \.self) { index in
            PointMark(
                x: .value("time", points[index].0),
                y: .value("glucose", points[index].1)
            )
            .symbolSize(12)
            .foregroundStyle(stale ? Color.gray : Color.white.opacity(0.8))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .accessibilityHidden(true)
    }
}

/// Also used by the home widgets (AapsHomeWidget.swift).
struct TrendArrowView: View {

    let trend: String?

    var body: some View {
        if let (symbol, count) = Self.symbol(trend) {
            HStack(spacing: -4) {
                ForEach(0..<count, id: \.self) { _ in
                    Image(systemName: symbol)
                }
            }
        }
    }

    /// Kotlin `TrendArrow` names. Unknown or `NONE` shows no arrow.
    static func symbol(_ trend: String?) -> (String, Int)? {
        switch trend {
        case "TRIPLE_UP": return ("arrow.up", 3)
        case "DOUBLE_UP": return ("arrow.up", 2)
        case "SINGLE_UP": return ("arrow.up", 1)
        case "FORTY_FIVE_UP": return ("arrow.up.right", 1)
        case "FLAT": return ("arrow.right", 1)
        case "FORTY_FIVE_DOWN": return ("arrow.down.right", 1)
        case "SINGLE_DOWN": return ("arrow.down", 1)
        case "DOUBLE_DOWN": return ("arrow.down", 2)
        case "TRIPLE_DOWN": return ("arrow.down", 3)
        default: return nil
        }
    }
}

/// Also used by the home widgets (AapsHomeWidget.swift).
extension AapsActivityAttributes.BgColorClass {

    var color: Color {
        switch self {
        case .low: return .red
        case .inRange: return .green
        case .high: return .yellow
        case .stale: return .gray
        }
    }
}
