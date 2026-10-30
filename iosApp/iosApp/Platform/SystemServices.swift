import AVFoundation
import CallKit
import UIKit
import ComposeApp

/// Permissions, settings, cellular-call observation (CALL-001) and the sync probe bridge.
final class SystemServices: NSObject, IosSystemDriver, CXCallObserverDelegate {
    var sink: IosEventSink?
    private let audio: AudioController
    private let calls: CallController
    private let callObserver = CXCallObserver()

    init(audio: AudioController, calls: CallController) {
        self.audio = audio
        self.calls = calls
        super.init()
        callObserver.setDelegate(self, queue: nil)
    }

    var model: String { UIDevice.current.model }
    var osVersion: String { UIDevice.current.systemVersion }

    func microphonePermission() -> String {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return "granted"
        case .denied: return "denied"
        default: return "undetermined"
        }
    }

    func requestMicrophone(callback: IosStringCallback) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { callback.onResult(value: granted ? "granted" : "denied") }
        }
    }

    /// iOS only allows opening this app's own Settings page; Bluetooth/Sound pages cannot be deep-linked.
    func openSettings(target: String) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        DispatchQueue.main.async { UIApplication.shared.open(url) }
    }

    func showPairingHelp() {
        DispatchQueue.main.async {
            let alert = UIAlertController(
                title: "Pair a Bluetooth device",
                message: "Open Settings › Bluetooth, put the accessory in pairing mode and tap it. PartyMusik will show it as soon as iOS connects it.",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
                .first?
                .present(alert, animated: true)
        }
    }

    func probe(uid: String, signal: KotlinFloatArray, sampleRate: Int32, callback: IosProbeCallback) {
        let samples = (0..<signal.size).map { signal.get(index: $0) }
        audio.probe(uid: uid, signal: samples, sampleRate: Double(sampleRate)) { recording, emitFrame in
            guard let recording else {
                callback.onResult(recording: nil, emitFrame: 0)
                return
            }
            let array = KotlinFloatArray(size: Int32(recording.count))
            for (i, v) in recording.enumerated() { array.set(index: Int32(i), value: v) }
            callback.onResult(recording: array, emitFrame: Int32(emitFrame))
        }
    }

    // MARK: CXCallObserverDelegate — observation only, never control (CALL-002)

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        guard call.uuid != calls.activeCall else { return } // our own VoIP call
        let others = callObserver.calls.filter { $0.uuid != calls.activeCall }
        let state: String
        if others.allSatisfy({ $0.hasEnded }) {
            state = "none"
        } else if others.contains(where: { $0.hasConnected && !$0.hasEnded }) {
            state = "active"
        } else {
            state = "ringing"
        }
        DispatchQueue.main.async { self.sink?.cellularChanged(state: state) }
    }
}
