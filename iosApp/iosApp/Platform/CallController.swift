import AVFoundation
import CallKit
import ComposeApp

/// In-app VoIP through CallKit (spike S-12). The POC call is an echo loopback: mic → voice processing → 250 ms delay
/// → call output. Audio is never stored (DIA-006).
final class CallController: NSObject, IosCallDriver, CXProviderDelegate {
    var sink: IosEventSink?
    private let audio: AudioController
    private let provider: CXProvider
    private let callController = CXCallController()
    private let voiceEngine = AVAudioEngine()
    private let echo = AVAudioUnitDelay()
    private(set) var activeCall: UUID?

    init(audio: AudioController) {
        self.audio = audio
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }

    // MARK: IosCallDriver

    func startCall(name: String, uri: String) -> String {
        guard activeCall == nil else { return "conflict" }
        let id = UUID()
        activeCall = id
        let action = CXStartCallAction(call: id, handle: CXHandle(type: .generic, value: uri))
        action.contactIdentifier = name
        callController.request(CXTransaction(action: action)) { [weak self] error in
            if let error {
                self?.activeCall = nil
                self?.emit("ended:\(error.localizedDescription)")
            }
        }
        emit("dialing")
        return "submitted"
    }

    func hangup() {
        guard let id = activeCall else { return }
        callController.request(CXTransaction(action: CXEndCallAction(call: id))) { _ in }
    }

    func setMuted(muted: Bool) {
        guard let id = activeCall else { return }
        callController.request(CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted))) { _ in }
    }

    // MARK: CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        stopVoice()
        activeCall = nil
        emit("idle")
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        do {
            try audio.configureVoiceSession() // configure here; start audio only in didActivate
            action.fulfill()
            provider.reportOutgoingCall(with: action.callUUID, connectedAt: Date())
        } catch {
            action.fail()
        }
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audio.callDidStart()
        startVoice()
        emit("active")
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        stopVoice()
        activeCall = nil
        action.fulfill()
        emit("ended:local")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.emit("idle") }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audio.callDidEnd() // VOIP-005: back to the media session; the shared engine restores media routes
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        voiceEngine.inputNode.isVoiceProcessingInputMuted = action.isMuted
        DispatchQueue.main.async { self.sink?.mutedChanged(muted: action.isMuted) }
        action.fulfill()
    }

    // MARK: Echo loopback

    private func startVoice() {
        do {
            try voiceEngine.inputNode.setVoiceProcessingEnabled(true)
            echo.delayTime = 0.25
            echo.feedback = 0
            echo.wetDryMix = 100
            if voiceEngine.attachedNodes.contains(echo) == false { voiceEngine.attach(echo) }
            let format = voiceEngine.inputNode.outputFormat(forBus: 0)
            voiceEngine.connect(voiceEngine.inputNode, to: echo, format: format)
            voiceEngine.connect(echo, to: voiceEngine.mainMixerNode, format: format)
            voiceEngine.prepare()
            try voiceEngine.start()
        } catch {
            NSLog("PartyMusik voice engine failed: \(error)")
        }
    }

    private func stopVoice() {
        voiceEngine.stop()
        try? voiceEngine.inputNode.setVoiceProcessingEnabled(false)
    }

    private func emit(_ state: String) {
        DispatchQueue.main.async { self.sink?.callStateChanged(state: state) }
    }
}
