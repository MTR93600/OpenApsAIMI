import SwiftUI
import WidgetKit

/// Entry point of the widget extension: the Live Activity (step 2 of
/// docs/kmp-migration/widget-live-activity.md) and the home and lock screen widgets (step 3).
@main
struct AapsLiveActivityBundle: WidgetBundle {

    var body: some Widget {
        AapsLiveActivityWidget()
        AapsHomeWidget()
    }
}
