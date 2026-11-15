import Charts
import SwiftUI
import WidgetKit

/// The home screen and lock screen widgets: the same content as the Android widgets, from the same
/// shared widget state (`WidgetStateJson` in Kotlin, built with the chart and the details).
///
/// The app writes the last state to the App Group file (`AapsWidgetStore`) and asks WidgetKit to
/// reload. This extension only reads that file. It cannot load the Kotlin framework.
///
/// Safety: the file may be old. The app may be frozen (force quit, Bluetooth off) or iOS may refuse a
/// reload (an app gets only a few per day in the background). So the age is judged here, at the time
/// of each timeline entry, with `ContentState.isStale(at:)`, the same edge as Kotlin's
/// `WidgetState.isStaleAt`. The timeline has a second entry at `staleAt`, so the widget turns stale on
/// time even when nobody reloads it. A stale value is struck out, grey, has a warning sign and no arrow
/// and no delta; on the lock screen, where colours are not shown, the strike and the sign still are.
/// The other values of the same file (IOB, COB, running mode, profile, target, temp basal,
/// sensitivity) are just as old, so when stale they are struck out and grey too, and "carbs required"
/// is not shown at all: an old request to eat must never look like a current one.
/// No file, an unreadable file or an unknown version shows the "no value" state, never old numbers.
///
/// Every text comes from Kotlin, already translated. Nothing here is joined into a sentence.
struct AapsHomeWidget: Widget {

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: AapsWidgetStore.kind, provider: AapsWidgetProvider()) { entry in
            AapsHomeWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color.black }
        }
        // The widget gallery reads these when the app is installed, before any Kotlin runs, so they
        // cannot come from the Kotlin strings. English only, like the App Shortcut texts.
        .configurationDisplayName("AAPS")
        .description("Glucose, IOB, COB and loop")
        .supportedFamilies([
            .systemSmall, .systemMedium, .systemLarge,
            .accessoryCircular, .accessoryRectangular, .accessoryInline,
        ])
    }
}

// MARK: - Data

/// What only the home widgets show: the chips of the Android widget. Decoded from the same file as
/// `AapsActivityAttributes.ContentState`; the keys are listed in the KDoc of `WidgetStateJson`.
/// Every field is optional, so a file without the details still shows the glucose.
struct AapsWidgetDetails: Decodable {
    let targetText: String?
    let targetChip: String?
    let ttReason: String?
    let targetIcon: String?
    let profileText: String?
    let profileModified: Bool?
    let loopIcon: String?
    let sensIcon: String?
    let sensText: String?
    let isfFromText: String?
    let isfToText: String?
    let isfStep: String?
    let isfStepText: String?
    let tbr: String?
    let tbrText: String?
    let iobActive: Bool?
    let cobActive: Bool?
}

struct AapsWidgetEntry: TimelineEntry {
    let date: Date
    /// Nil when there is no usable file.
    let state: AapsActivityAttributes.ContentState?
    let details: AapsWidgetDetails?

    /// True when the value must not look fresh at the time of this entry.
    var isStale: Bool { state?.isStale(at: date) ?? true }
}

struct AapsWidgetProvider: TimelineProvider {

    /// How soon WidgetKit is asked to read the file again by itself. iOS decides the real time. A read
    /// picks up a file the app wrote while its reload was refused.
    private static let rereadAfter: TimeInterval = 15 * 60

    func placeholder(in context: Context) -> AapsWidgetEntry {
        AapsWidgetEntry(date: Date(), state: nil, details: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (AapsWidgetEntry) -> Void) {
        completion(Self.load(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<AapsWidgetEntry>) -> Void) {
        let now = Date()
        let first = Self.load(at: now)
        var entries = [first]
        // A second entry at the moment the reading turns old, so the widget greys it out on time
        // without a reload.
        if let state = first.state, !first.isStale, let staleAt = state.staleAt {
            let staleDate = Date(timeIntervalSince1970: staleAt / 1000)
            if staleDate > now {
                entries.append(AapsWidgetEntry(date: staleDate, state: state, details: first.details))
            }
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(Self.rereadAfter))))
    }

    static func load(at date: Date) -> AapsWidgetEntry {
        guard let data = AapsWidgetStore.readData() else {
            return AapsWidgetEntry(date: date, state: nil, details: nil)
        }
        guard let state = try? JSONDecoder().decode(AapsActivityAttributes.ContentState.self, from: data),
              state.v == AapsWidgetStore.supportedVersion
        else {
            AapsWidgetStore.log.error("Widget state could not be read")
            return AapsWidgetEntry(date: date, state: nil, details: nil)
        }
        let details = try? JSONDecoder().decode(AapsWidgetDetails.self, from: data)
        return AapsWidgetEntry(date: date, state: state, details: details)
    }
}

// MARK: - Views

struct AapsHomeWidgetView: View {

    let entry: AapsWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let state = entry.state {
            content(state)
        } else {
            NoDataView(family: family)
        }
    }

    @ViewBuilder
    private func content(_ state: AapsActivityAttributes.ContentState) -> some View {
        let stale = entry.isStale
        switch family {
        case .accessoryInline:
            Label {
                Text(state.bgText).strikethrough(stale)
            } icon: {
                Image(systemName: stale ? "exclamationmark.triangle" : Self.inlineArrow(state.trend))
            }
            .accessibilityLabel(Text(state.bgText))
            .accessibilityValue(Text(stale ? state.staleText : (state.trendText ?? "")))
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    HomeGlucoseView(state: state, stale: stale, date: entry.date, font: .title3)
                    HomeAgeView(state: state).font(.caption2)
                }
                .widgetAccentable()
            }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    HomeGlucoseView(state: state, stale: stale, date: entry.date, font: .title2)
                    if !stale {
                        Text(state.deltaText).font(.caption)
                    }
                }
                .widgetAccentable()
                HomeAgeView(state: state).font(.caption)
                if !stale, let carbsReq = state.carbsReqText {
                    Text(carbsReq).font(.caption2).lineLimit(1)
                } else {
                    HStack(spacing: 6) {
                        LabeledText(label: state.iobLabel, value: state.iobText, stale: stale)
                        LabeledText(label: state.cobLabel, value: state.cobText, stale: stale)
                    }
                    .font(.caption2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .systemMedium:
            HStack(alignment: .top, spacing: 10) {
                GlucosePanel(state: state, stale: stale, date: entry.date, font: .system(size: 44))
                ChipsPanel(state: state, details: entry.details, stale: stale)
            }
        case .systemLarge:
            VStack(spacing: 10) {
                HStack(alignment: .top, spacing: 10) {
                    GlucosePanel(state: state, stale: stale, date: entry.date, font: .system(size: 52))
                    ChipsPanel(state: state, details: entry.details, stale: stale)
                }
                if !state.chart.isEmpty {
                    HomeChart(state: state, stale: stale)
                }
            }
        default:
            // .systemSmall
            VStack(alignment: .leading, spacing: 4) {
                GlucosePanel(state: state, stale: stale, date: entry.date, font: .system(size: 40))
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    LabeledText(label: state.iobLabel, value: state.iobText, stale: stale)
                    LabeledText(label: state.cobLabel, value: state.cobText, stale: stale)
                }
                .font(.caption2)
                if !stale, let carbsReq = state.carbsReqText {
                    Text(carbsReq).font(.caption2).foregroundStyle(.orange).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// One symbol for the inline lock screen widget, which shows a single picture.
    private static func inlineArrow(_ trend: String?) -> String {
        TrendArrowView.symbol(trend)?.0 ?? "drop"
    }
}

/// No file yet, or one that cannot be read: the "no value" mark and a warning sign, never numbers.
private struct NoDataView: View {

    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryInline:
            Label("---", systemImage: "exclamationmark.triangle")
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "exclamationmark.triangle")
            }
        default:
            VStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle").foregroundStyle(.gray)
                Text("---").font(.title).foregroundStyle(.gray)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// The reading, the delta (only when fresh) and the age, which counts by itself.
private struct GlucosePanel: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    let date: Date
    let font: Font

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HomeGlucoseView(state: state, stale: stale, date: date, font: font)
            if !stale {
                Text(state.deltaText).font(.subheadline).foregroundStyle(.white)
            }
            HStack(spacing: 3) {
                if stale {
                    Image(systemName: "exclamationmark.triangle")
                        .accessibilityLabel(Text(state.staleText))
                }
                HomeAgeView(state: state)
            }
            .font(.caption)
            .foregroundStyle(stale ? Color.gray : Color.secondary)
        }
    }
}

/// The reading. Struck out and grey, without an arrow, when stale.
private struct HomeGlucoseView: View {

    let state: AapsActivityAttributes.ContentState
    let stale: Bool
    let date: Date
    let font: Font

    var body: some View {
        let color = (stale ? AapsActivityAttributes.BgColorClass.stale : state.colorClass(at: date)).color
        HStack(spacing: 2) {
            Text(state.bgText)
                .font(font)
                .fontWeight(.bold)
                .strikethrough(stale)
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            if !stale {
                TrendArrowView(trend: state.trend)
                    .font(.headline)
                    .foregroundStyle(color)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(state.bgText))
        .accessibilityValue(Text(stale ? state.staleText : (state.trendText ?? "")))
    }
}

private struct HomeAgeView: View {

    let state: AapsActivityAttributes.ContentState

    var body: some View {
        if let date = state.bgDate {
            Text(date, style: .relative)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }
}

/// A label and its value. When stale the value is struck out and grey, like the reading.
private struct LabeledText: View {

    let label: String
    let value: String
    let stale: Bool

    var body: some View {
        HStack(spacing: 3) {
            Text(label).foregroundStyle(.secondary)
            Text(value)
                .strikethrough(stale)
                .foregroundStyle(stale ? Color.gray : Color.primary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
    }
}

/// The chips of the Android widget: running mode, profile, target, temp basal, IOB, COB, sensitivity
/// and ISF. A chip with no text is left out, as on Android.
///
/// When stale every chip is grey and struck out, because it comes from the same old file as the
/// reading, and "carbs required" is left out.
private struct ChipsPanel: View {

    let state: AapsActivityAttributes.ContentState
    let details: AapsWidgetDetails?
    let stale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Chip(
                symbol: HomeWidgetStyle.loopSymbol(details?.loopIcon),
                text: state.modeText,
                color: HomeWidgetStyle.modeColor(state.mode),
                colorText: state.modeActive,
                stale: stale
            )
            if let profile = details?.profileText, !profile.isEmpty {
                let modified = details?.profileModified ?? false
                Chip(
                    symbol: "person.crop.circle",
                    text: profile,
                    color: modified ? .orange : .secondary,
                    colorText: modified,
                    stale: stale
                )
            }
            if let target = details?.targetText, !target.isEmpty {
                let color = HomeWidgetStyle.targetColor(chip: details?.targetChip, reason: details?.ttReason)
                Chip(
                    symbol: HomeWidgetStyle.targetSymbol(details?.targetIcon),
                    text: target,
                    color: color,
                    colorText: false,
                    stale: stale
                )
            }
            HStack(spacing: 8) {
                Chip(
                    symbol: "syringe",
                    text: state.iobText,
                    color: (details?.iobActive ?? false) ? .blue : .secondary,
                    colorText: false,
                    stale: stale
                )
                .accessibilityLabel(Text(state.iobLabel))
                .accessibilityValue(Text(state.iobText))
                Chip(
                    symbol: "fork.knife",
                    text: state.cobText,
                    color: state.carbsReqText != nil ? .orange : ((details?.cobActive ?? false) ? .yellow : .secondary),
                    colorText: false,
                    stale: stale
                )
                .accessibilityLabel(Text(state.cobLabel))
                .accessibilityValue(Text(state.cobText))
            }
            if !stale, let carbsReq = state.carbsReqText {
                Text(carbsReq).font(.caption2).foregroundStyle(.orange).lineLimit(1)
            }
            HStack(spacing: 8) {
                if let details {
                    SensitivityChip(details: details, stale: stale)
                    if let tbr = details.tbr {
                        Image(systemName: HomeWidgetStyle.tbrSymbol(tbr))
                            .font(.caption)
                            .foregroundStyle(stale ? Color.gray : Color.secondary)
                            .accessibilityLabel(Text(details.tbrText ?? ""))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A symbol and a text. When stale both are grey and the text is struck out.
private struct Chip: View {

    let symbol: String
    let text: String
    let color: Color
    let colorText: Bool
    let stale: Bool

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: symbol).foregroundStyle(stale ? Color.gray : color)
            Text(text)
                .strikethrough(stale)
                .foregroundStyle(stale ? Color.gray : (colorText ? color : Color.white))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .font(.caption)
    }
}

/// The autosens direction (crossed out when autosens is off), its percentage, and the ISF change.
private struct SensitivityChip: View {

    let details: AapsWidgetDetails
    let stale: Bool

    var body: some View {
        let off = details.sensIcon?.hasPrefix("OFF_") ?? false
        HStack(spacing: 3) {
            Image(systemName: HomeWidgetStyle.sensitivitySymbol(details.sensIcon))
                .overlay {
                    if off { Image(systemName: "line.diagonal") }
                }
                .foregroundStyle(.secondary)
            if let sens = details.sensText, !sens.isEmpty {
                Text(sens)
            }
            if let from = details.isfFromText, !from.isEmpty {
                Text(from)
                Image(systemName: HomeWidgetStyle.isfStepSymbol(details.isfStep))
                    .accessibilityLabel(Text(details.isfStepText ?? ""))
                Text(details.isfToText ?? "")
            }
        }
        .font(.caption2)
        .foregroundStyle(stale ? Color.gray : Color.secondary)
        .strikethrough(stale)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }
}

/// The readings of the last 3 hours. Real readings only (Kotlin leaves gaps empty).
private struct HomeChart: View {

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
            .symbolSize(14)
            .foregroundStyle(stale ? Color.gray : Color.white.opacity(0.85))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .accessibilityHidden(true)
    }
}

/// Kotlin enum names to SF Symbols and colours. The colours follow the meaning of the Android widget
/// colours (green closed loop, red stopped, orange attention); they are not the exact Android values.
private enum HomeWidgetStyle {

    /// `WidgetLoopIcon` names.
    static func loopSymbol(_ icon: String?) -> String {
        switch icon {
        case "CLOSED": return "arrow.triangle.2.circlepath.circle.fill"
        case "OPEN": return "arrow.triangle.2.circlepath.circle"
        case "LGS": return "arrow.down.to.line.circle"
        case "SUPER_BOLUS": return "bolt.circle"
        case "DISCONNECTED": return "personalhotspot.slash"
        case "PAUSED": return "pause.circle"
        case "DISABLED": return "xmark.circle"
        default: return "circle.dashed"
        }
    }

    /// `RM.Mode` names.
    static func modeColor(_ mode: String) -> Color {
        switch mode {
        case "CLOSED_LOOP", "RESUME": return .green
        case "CLOSED_LOOP_LGS": return .teal
        case "OPEN_LOOP": return .orange
        case "SUPER_BOLUS": return .yellow
        case "DISCONNECTED_PUMP", "DISABLED_LOOP", "SUSPENDED_BY_PUMP", "SUSPENDED_BY_USER", "SUSPENDED_BY_DST":
            return .red
        default: return .secondary
        }
    }

    /// `WidgetTargetChip` and `TT.Reason` names.
    static func targetColor(chip: String?, reason: String?) -> Color {
        switch chip {
        case "TEMP":
            switch reason {
            case "EATING_SOON": return .orange
            case "ACTIVITY": return .green
            case "HYPOGLYCEMIA": return .red
            default: return .purple
            }
        case "ADJUSTED": return .cyan
        default: return .secondary
        }
    }

    /// `WidgetTargetIcon` names.
    static func targetSymbol(_ icon: String?) -> String {
        switch icon {
        case "EATING_SOON": return "fork.knife.circle"
        case "ACTIVITY": return "figure.run"
        case "HYPOGLYCEMIA": return "exclamationmark.circle"
        case "MANUAL": return "slider.horizontal.3"
        default: return "scope"
        }
    }

    /// `WidgetSensitivityIcon` names. The `OFF_` kinds get a line drawn over them.
    static func sensitivitySymbol(_ icon: String?) -> String {
        switch icon {
        case "ABOVE", "OFF_ABOVE": return "arrow.up"
        case "BELOW", "OFF_BELOW": return "arrow.down"
        default: return "equal"
        }
    }

    /// `IsfStep` names.
    static func isfStepSymbol(_ step: String?) -> String {
        switch step {
        case "UP": return "arrow.up.right"
        case "DOWN": return "arrow.down.right"
        default: return "arrow.right"
        }
    }

    /// `WidgetTbrState` names.
    static func tbrSymbol(_ tbr: String) -> String {
        switch tbr {
        case "ABOVE_PROFILE": return "arrow.up.square"
        case "BELOW_PROFILE": return "arrow.down.square"
        default: return "minus.square"
        }
    }
}
