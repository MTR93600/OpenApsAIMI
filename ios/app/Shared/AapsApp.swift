import SwiftUI
import AapsShared

/// The app shell, built once into both Xcode targets, AAPS and AAPS2.
///
/// The app is a master phone that runs the loop; the role is set in `IosConfig`, not here. The two
/// targets differ only in bundle id, name and icon. Their bundle ids (`app.aaps.client` and
/// `app.aaps.client2`) still carry the name of the first iOS port, which was a follower. They stay,
/// because a new bundle id is a new app for iOS and the installed settings and data would be lost.
/// The name is read back from the bundle, so this file does not know or care which of the two it is
/// running as.
@main
struct AapsApp: App {

    /// Firebase is started here, before SwiftUI builds anything.
    ///
    /// Crashlytics does not need an `AppDelegate` - an `init()` on the `App` is early enough, and
    /// early is the point: its signal handlers have to be installed before there is anything to
    /// crash. It must also be the first line, before any call into the Kotlin framework below: the
    /// Kotlin runtime starts with that first call and puts its own terminate handler on top of
    /// Crashlytics', and that ordering is what keeps a Kotlin crash readable.
    ///
    /// `start()` never raises. A missing or broken `GoogleService-Info.plist` means no crash
    /// reporting, not no app.

    /// One Connect IQ instance for the life of the process. It is created on first use, which is
    /// the line below that installs it, after Crashlytics. That initializes the SDK with this
    /// target's URL scheme before `startAapsApp` builds the Garmin plugin.
    private static let connectIqCompanion = SwiftConnectIqCompanion()

    init() {
        CrashlyticsCrashReporter.start()
        // Install the Swift AVFoundation implementation before the Kotlin graph can construct an
        // iOS Meal Advisor screen.
        IosMealAdvisorCameraBridgeKt.installIosMealAdvisorCameraFactory(
            factory: SwiftMealAdvisorCameraFactory()
        )
        ConnectIqCompanionKt.installConnectIqCompanion(companion: AapsApp.connectIqCompanion)
        // Must happen here. iOS only lets background task handlers be registered before the app
        // finishes launching, and `makeUIViewController` below runs later. This call needs no
        // graph; the Kotlin side queues the tasks once its graph is built.
        AapsAppHostKt.registerAimiBackgroundTasks()
        // Before any window: iOS can start the app in the background for a Bluetooth event, and
        // then SwiftUI may build no window at all. The sensor, the pump and the loop must run then
        // too. The view calls this again, which does nothing the second time.
        AapsAppHostKt.startAapsApp(nsSocketFactory: SwiftNsSocketFactory(), crashReporter: CrashlyticsCrashReporter.shared)
    }

    var body: some SwiftUI.Scene {
        WindowGroup {
            // onOpenURL is a View modifier, not a Scene one, so it sits on the window's view.
            ShellView()
                .onOpenURL { url in
                    // Garmin Connect returns the chosen watches by opening our URL scheme.
                    _ = AapsApp.connectIqCompanion.handleOpenUrl(url: url.absoluteString)
                }
        }
    }
}

/// Hosts AAPS itself inside SwiftUI.
///
/// Compose Multiplatform hands back a plain UIViewController, so SwiftUI shows it the way it shows
/// any UIKit screen. Nothing here is drawn by SwiftUI: this is the real `AapsAppRoot` from
/// `appshell/commonMain` - the same theme, splash gate, dialog hosts and navigation the Android app
/// runs - built by the Kotlin graph on the other side of this call.
struct AapsAppView: UIViewControllerRepresentable {

    func makeUIViewController(context: UIViewControllerRepresentableContext<AapsAppView>) -> UIViewController {
        // The Nightscout socket is handed to the Kotlin graph from here, because it is written in
        // Swift and cannot carry a Kotlin annotation. Android and desktop contribute their own
        // socket.io client automatically; this is the same project's Swift client, so both platforms
        // speak to Nightscout identically.
        AapsAppHostKt.aapsAppViewController(
            nsSocketFactory: SwiftNsSocketFactory(),
            crashReporter: CrashlyticsCrashReporter.shared
        )
    }

    func updateUIViewController(
        _ controller: UIViewController,
        context: UIViewControllerRepresentableContext<AapsAppView>,
    ) {}
}

/// The whole screen is AAPS.
///
/// The diagnostic page that used to live here - module count, DI check, database round trip - was
/// scaffolding for proving the pieces worked one at a time. They do, so the app shows the app.
/// `ShellInfo`'s checks are still there and still callable if a single layer needs isolating again.
struct ShellView: View {

    var body: some View {
        AapsAppView()
            .ignoresSafeArea()
    }
}
