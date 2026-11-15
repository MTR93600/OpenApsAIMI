import SwiftUI
import WidgetKit

/// Entry point of the Live Activity widget extension. Only the Live Activity for now; the home and
/// lock screen widgets (step 3 of docs/kmp-migration/widget-live-activity.md) join this bundle later.
@main
struct AapsLiveActivityBundle: WidgetBundle {

    var body: some Widget {
        AapsLiveActivityWidget()
    }
}
