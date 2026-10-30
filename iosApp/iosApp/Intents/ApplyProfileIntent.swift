import AppIntents
import ComposeApp

/// Connection-triggered automation on iOS (CFG-006, ADR-004): the user creates a Shortcuts personal automation
/// "When <Bluetooth device> connects → Apply Audio Profile". The intent runs in the app process.
struct ProfileEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Audio Profile")
    static var defaultQuery = ProfileQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct ProfileQuery: EntityQuery {
    private func all() -> [ProfileEntity] {
        _ = AppServices.shared.core
        return IosBridge.shared.profiles().map { ProfileEntity(id: $0.id, name: $0.name) }
    }

    func entities(for identifiers: [String]) async throws -> [ProfileEntity] {
        all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [ProfileEntity] { all() }
}

struct ApplyProfileIntent: AppIntent {
    static var title: LocalizedStringResource = "Apply Audio Profile"
    static var description = IntentDescription("Applies a PartyMusik routing profile. Manual choices made in the app pause automations for two hours.")

    @Parameter(title: "Profile")
    var profile: ProfileEntity

    func perform() async throws -> some IntentResult & ProvidesDialog {
        _ = AppServices.shared.core
        IosBridge.shared.applyProfile(profileId: profile.id, fromAutomation: true)
        return .result(dialog: "Applying \(profile.name)")
    }
}

struct PartyMusikShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ApplyProfileIntent(),
            phrases: ["Apply audio profile in \(.applicationName)"],
            shortTitle: "Apply Audio Profile",
            systemImageName: "hifispeaker.2"
        )
    }
}
