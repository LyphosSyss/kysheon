import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            // Compose handles the safe areas itself (safeDrawingPadding).
            ContentView().ignoresSafeArea(.all)
        }
    }
}
