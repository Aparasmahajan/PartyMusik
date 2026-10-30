import SwiftUI
import ComposeApp

/// Process-wide services. Created lazily so App Intents launched in the background reach the same core.
final class AppServices {
    static let shared = AppServices()

    let drivers = AppDrivers()
    lazy var core: PartyMusikCore = IosBridge.shared.createCore(drivers: drivers)

    private init() {}
}

@main
struct PartyMusikApp: App {
    init() {
        _ = AppServices.shared.core
    }

    var body: some Scene {
        WindowGroup {
            ComposeView(core: AppServices.shared.core)
                .ignoresSafeArea(.keyboard)
        }
    }
}

/// Hosts the shared Compose Multiplatform UI.
struct ComposeView: UIViewControllerRepresentable {
    let core: PartyMusikCore

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(core: core)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
