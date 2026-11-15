import ActivityKit
import AppIntents
import Foundation
import UIKit
import WidgetKit
import os
import AapsShared

/// The Live Activity on the iPhone: lock screen, Dynamic Island, Apple Watch Smart Stack.
///
/// The Kotlin plugin (`LiveActivityPlugin`) decides when to show what and sends the state as JSON.
/// This class only moves that JSON into ActivityKit. Local updates only, no push server.
///
/// Rules, all on the main actor in `LiveActivityController`:
/// - One activity only. An activity this process did not make is adopted once (the app was
///   restarted), any other is ended.
/// - iOS lets the app start an activity only in the foreground. In the background the newest state is
///   kept and the activity is started when the app becomes active.
/// - An activity older than 60 minutes is replaced, but only while the app is active (a new one
///   could not be started otherwise). iOS ends an activity after 8 hours.
/// - `staleDate` is the reading's `staleAt`: the same 9 minute rule as the rest of the app. The
///   extension also judges the age itself, so an old reading never looks fresh.
/// - `end()` ends every activity: the user switched the Live Activity off.
final class SwiftLiveActivityCompanion: NSObject, LiveActivityCompanion {

    private let lock = NSLock()
    private var callback: LiveActivityCallback?

    func start(callback: LiveActivityCallback) {
        lock.lock()
        self.callback = callback
        lock.unlock()
        Task { @MainActor in
            LiveActivityController.shared.install { [weak self] in self?.askKotlinForRefresh() }
        }
    }

    func update(stateJson: String) {
        guard let data = stateJson.data(using: .utf8),
              let state = try? JSONDecoder().decode(AapsActivityAttributes.ContentState.self, from: data)
        else {
            LiveActivityController.log.error("Live Activity state could not be read")
            return
        }
        Task { @MainActor in await LiveActivityController.shared.show(state) }
    }

    func end() {
        Task { @MainActor in await LiveActivityController.shared.endAll() }
    }

    func stop() {
        lock.lock()
        callback = nil
        lock.unlock()
    }

    private func askKotlinForRefresh() {
        lock.lock()
        let current = callback
        lock.unlock()
        current?.refreshRequested()
    }
}

/// The iOS home and lock screen widgets. The Kotlin plugin (`HomeWidgetPlugin`) decides when; this
/// class writes the JSON to the App Group file (`AapsWidgetStore`) and asks WidgetKit to draw the
/// widgets again.
///
/// Without an App Group container (the Xcode steps are not done yet) nothing is written and nothing
/// is reloaded. That is logged once, never a crash.
///
/// iOS gives an app only a small number of widget reloads per day while it is in the background, so
/// Kotlin (`HomeWidgetPublisher`) asks for a reload only now and then and otherwise only writes the
/// file. A reload that iOS refuses leaves the old file on screen; the widget then turns it grey and
/// struck out at `staleAt` by itself, so it never shows an old value as fresh.
final class SwiftHomeWidgetCompanion: NSObject, HomeWidgetCompanion {

    /// Writes one after the other, off the caller's thread.
    private let queue = DispatchQueue(label: "app.aaps.homewidget", qos: .utility)
    private var reportedNoContainer = false

    func save(stateJson: String, reload: Bool) {
        queue.async { [self] in
            guard AapsWidgetStore.write(stateJson) else {
                if !reportedNoContainer {
                    reportedNoContainer = true
                    AapsWidgetStore.log.info("No App Group container, the home widgets are not updated")
                }
                return
            }
            if reload {
                WidgetCenter.shared.reloadTimelines(ofKind: AapsWidgetStore.kind)
            }
        }
    }
}

/// All ActivityKit work. Main actor only.
@MainActor
final class LiveActivityController {

    static let shared = LiveActivityController()
    nonisolated static let log = Logger(subsystem: "app.aaps", category: "LiveActivity")

    /// Replace an activity this old while the app is active.
    private static let recreateAfter: TimeInterval = 60 * 60

    private var current: Activity<AapsActivityAttributes>?
    private var latest: AapsActivityAttributes.ContentState?
    /// True after Kotlin sent a state, false after `endAll`. A new process starts false, so a
    /// leftover activity is not brought back before Kotlin has said the switch is on.
    private var wanted = false
    /// Raised by `endAll`. The methods here are async and wait on `activity.end`, so other calls run
    /// in between. A method that waited checks that this did not change before it starts an
    /// activity, so a switch-off during the wait is never undone.
    private var generation = 0
    private var refresh: (() -> Void)?
    private var observer: NSObjectProtocol?

    private var appIsActive: Bool { UIApplication.shared.applicationState == .active }

    func install(refresh: @escaping () -> Void) {
        self.refresh = refresh
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in await LiveActivityController.shared.becameActive() }
        }
    }

    func show(_ state: AapsActivityAttributes.ContentState) async {
        wanted = true
        latest = state
        let started = generation
        await endUnknown()
        guard started == generation else { return }
        if let activity = current, isRunning(activity) {
            if needsRecreation(activity) && appIsActive {
                await activity.end(nil, dismissalPolicy: .immediate)
                guard started == generation else { return }
                current = nil
            } else {
                await activity.update(content(state))
                return
            }
        } else {
            current = nil
        }
        if appIsActive { startActivity(state) }
    }

    func endAll() async {
        generation += 1
        wanted = false
        latest = nil
        current = nil
        for activity in Activity<AapsActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// The App Shortcut: end what is there and start again with a fresh state.
    func restart() async {
        guard wanted else {
            refresh?()
            return
        }
        let started = generation
        for activity in Activity<AapsActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        guard started == generation else { return }
        current = nil
        if let latest, appIsActive { startActivity(latest) }
        refresh?()
    }

    private func becameActive() async {
        guard wanted else { return }
        let started = generation
        await endUnknown()
        guard started == generation else { return }
        if let activity = current, isRunning(activity), !needsRecreation(activity) {
            // Still fine. Kotlin sends a fresh state anyway: the app may have been frozen.
        } else {
            if let activity = current { await activity.end(nil, dismissalPolicy: .immediate) }
            guard started == generation else { return }
            current = nil
            // The kept state may be old. Its staleDate is then in the past, so it shows as stale at
            // once, and Kotlin's fresh state replaces it a moment later.
            if let latest { startActivity(latest) }
        }
        refresh?()
    }

    private func startActivity(_ state: AapsActivityAttributes.ContentState) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            Self.log.info("Live Activities are off in the iOS settings")
            return
        }
        do {
            current = try Activity.request(
                attributes: AapsActivityAttributes(createdAt: Date()),
                content: content(state),
                pushType: nil
            )
        } catch {
            Self.log.error("Live Activity could not start: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Adopts one leftover activity when this process has none (the app was restarted) and ends every
    /// other one.
    private func endUnknown() async {
        let all = Activity<AapsActivityAttributes>.activities
        if current == nil, let leftover = all.first(where: isRunning) {
            current = leftover
        }
        for activity in all where activity.id != current?.id {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    private func content(_ state: AapsActivityAttributes.ContentState) -> ActivityContent<AapsActivityAttributes.ContentState> {
        ActivityContent(state: state, staleDate: state.staleDate)
    }

    private func isRunning(_ activity: Activity<AapsActivityAttributes>) -> Bool {
        activity.activityState == .active || activity.activityState == .stale
    }

    private func needsRecreation(_ activity: Activity<AapsActivityAttributes>) -> Bool {
        !isRunning(activity) || Date().timeIntervalSince(activity.attributes.createdAt) >= Self.recreateAfter
    }
}

/// "Restart Live Activity", for Shortcuts and Siri. Opens the app, because iOS starts an activity
/// only from the foreground.
struct RestartLiveActivityIntent: AppIntent {

    static var title: LocalizedStringResource = "Restart Live Activity"
    static var description = IntentDescription("Ends the AAPS Live Activity and starts it again.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        await LiveActivityController.shared.restart()
        return .result()
    }
}

struct AapsAppShortcuts: AppShortcutsProvider {

    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RestartLiveActivityIntent(),
            phrases: ["Restart \(.applicationName) Live Activity"],
            shortTitle: "Restart Live Activity",
            systemImageName: "arrow.clockwise"
        )
    }
}
