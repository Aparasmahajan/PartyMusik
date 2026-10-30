import AVFoundation
import AVKit
import UIKit
import ComposeApp

/// Procedural PCM shared by single- and multi-output playback. The render thread reads these fields;
/// the POC accepts benign races on scalars (production: atomics / lock-free ring).
final class Renderer: @unchecked Sendable {
    var sampleRate: Double = 48_000
    var itemId: String?
    var durationFrames: Int64 = 0
    var position: Int64 = 0
    var playing = false

    /// Owning port UID for every hardware output channel; nil = single output (all channels play).
    var channelOwners: [String]? = nil
    var activeUids = Set<String>()
    var gains: [String: Float] = [:]
    var delayFrames: [String: Int] = [:]

    /// Sync probe: in multi-route mode plays only on channels of `probeUid`; in single mode on the current route.
    var probeSamples: [Float] = []
    var probeUid: String?
    var probeIndex = 0

    private var history = [Float](repeating: 0, count: 48_000 * 2) // mono history for delay lines (2 s)
    private var historyWrite = 0

    func load(_ id: String, durationMs: Int64) {
        itemId = id
        durationFrames = durationMs * Int64(sampleRate) / 1000
        position = 0
    }

    private func sample(at n: Int64) -> Float {
        let t = Double(n) / sampleRate
        switch itemId {
        case "tone-1k":
            return Float(sin(2 * .pi * 1_000 * t) * 0.3)
        case "sweep":
            let total = Double(durationFrames) / sampleRate
            let k = log(1000.0) / total
            return Float(sin(2 * .pi * 20 * (exp(k * t) - 1) / k) * 0.25)
        default:
            let beat = 60.0 / 124
            let tb = t.truncatingRemainder(dividingBy: beat)
            let kick = sin(2 * .pi * (55 + 90 * exp(-tb * 30)) * tb) * exp(-tb * 9)
            let th = (t + beat / 2).truncatingRemainder(dividingBy: beat)
            let hat = th < 0.025 ? Double.random(in: -1...1) * exp(-th * 120) * 0.25 : 0
            return Float((kick * 0.6 + hat) * 0.5)
        }
    }

    func render(frameCount: Int, buffers: UnsafeMutableAudioBufferListPointer) {
        let channels = buffers.count
        for frame in 0..<frameCount {
            var value: Float = 0
            if playing, itemId != nil {
                value = sample(at: position)
                position += 1
                if position >= durationFrames { position = 0; playing = false }
            }
            history[historyWrite] = value
            let probeValue: Float = probeIndex < probeSamples.count ? probeSamples[probeIndex] : 0
            if probeIndex < probeSamples.count { probeIndex += 1 }

            for ch in 0..<channels {
                guard let data = buffers[ch].mData?.assumingMemoryBound(to: Float.self) else { continue }
                guard let owners = channelOwners else {
                    data[frame] = value + probeValue // single output: the probe goes to the current route
                    continue
                }
                let owner = ch < owners.count ? owners[ch] : ""
                var out: Float = 0
                if activeUids.contains(owner) {
                    let d = delayFrames[owner] ?? 0
                    var read = historyWrite - d
                    if read < 0 { read += history.count }
                    out = history[read] * (gains[owner] ?? 1)
                }
                if owner == probeUid { out += probeValue }
                data[frame] = out
            }
            historyWrite = (historyWrite + 1) % history.count
        }
    }
}

/// Owns AVAudioSession and the playback AVAudioEngine (spikes S-02, S-05, S-09).
final class AudioController: NSObject, IosAudioSessionDriver, IosPlayerDriver {
    var sink: IosEventSink?

    private let session = AVAudioSession.sharedInstance()
    private let engine = AVAudioEngine()
    private let renderer = Renderer()
    private var source: AVAudioSourceNode?
    private var statusTimer: Timer?
    private var multiRoute = false
    private var multiTargets: [String] = []
    private(set) var inCall = false

    override init() {
        super.init()
        configureMediaSession()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(routeChanged(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
        nc.addObserver(self, selector: #selector(interrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(mediaServicesReset), name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        nc.addObserver(self, selector: #selector(engineConfigChanged), name: .AVAudioEngineConfigurationChange, object: engine)
    }

    // MARK: Session profiles (docs/04 §2)

    /// Media: playAndRecord so the speaker can be forced and wired/USB inputs are visible; A2DP stays high quality
    /// because the HFP option is not set.
    func configureMediaSession() {
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothA2DP, .allowAirPlay, .defaultToSpeaker])
            try session.setActive(true)
        } catch {
            NSLog("PartyMusik media session error: \(error)")
        }
    }

    func configureVoiceSession() throws {
        // `.allowBluetooth` is named `.allowBluetoothHFP` from the iOS 26 SDK.
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
    }

    func callDidStart() { inCall = true; notifyRoute("call") }

    func callDidEnd() {
        inCall = false
        configureMediaSession()
        notifyRoute("call_ended")
    }

    // MARK: IosAudioSessionDriver

    func currentOutputs() -> [IosPort] {
        session.currentRoute.outputs.map { $0.toIosPort(isInput: false) }
    }

    func availableInputs() -> [IosPort] {
        (session.availableInputs ?? []).filter { $0.portType != .builtInMic }.map { $0.toIosPort(isInput: true) }
    }

    func requestMediaOutput(portType: String, uids: [String]) -> String {
        if inCall { return "conflict" }
        do {
            switch portType {
            case "speaker":
                try session.overrideOutputAudioPort(.speaker)
                return "submitted"
            case "receiver", "bt_hfp":
                return "unsupported"
            default:
                try session.overrideOutputAudioPort(.none)
                if session.currentRoute.outputs.contains(where: { uids.contains($0.uid) }) { return "submitted" }
                if let input = session.availableInputs?.first(where: { uids.contains($0.uid) }) {
                    try session.setPreferredInput(input)
                    return "submitted"
                }
                // A2DP, AirPlay and LE outputs can only be chosen by the user in the system route picker.
                return "picker"
            }
        } catch {
            return "error:\(error.localizedDescription)"
        }
    }

    func clearMediaPreference() {
        try? session.overrideOutputAudioPort(.none)
        try? session.setPreferredInput(nil)
    }

    func requestCommunicationOutput(portType: String, uids: [String]) -> String {
        guard inCall else { return "error:call output can only change during a call on iOS" }
        do {
            switch portType {
            case "speaker":
                try session.overrideOutputAudioPort(.speaker)
            case "receiver", "mic_builtin":
                try session.overrideOutputAudioPort(.none)
                try session.setPreferredInput(session.availableInputs?.first { $0.portType == .builtInMic })
            default:
                guard let input = session.availableInputs?.first(where: { uids.contains($0.uid) }) else { return "picker" }
                try session.overrideOutputAudioPort(.none)
                try session.setPreferredInput(input)
            }
            return "submitted"
        } catch {
            return "error:\(error.localizedDescription)"
        }
    }

    func releaseCommunication() {
        try? session.overrideOutputAudioPort(.none)
        try? session.setPreferredInput(nil)
    }

    func startMultiRoute(uids: [String]) -> String {
        if inCall { return "conflict" }
        do {
            try session.setCategory(.multiRoute)
            try session.setActive(true)
        } catch {
            return "error:\(error.localizedDescription)"
        }
        multiRoute = true
        multiTargets = uids
        rebuildGraph()
        return "submitted"
    }

    func stopMultiRoute() {
        multiRoute = false
        multiTargets = []
        configureMediaSession()
        rebuildGraph()
    }

    func setGain(uid: String, gain: Float) { renderer.gains[uid] = gain }

    func setDelay(uid: String, ms: Int32) { renderer.delayFrames[uid] = Int(Double(ms) / 1000 * renderer.sampleRate) }

    /// EXT-002: the system picker moves any app's audio, including third-party apps.
    func showRoutePicker() {
        DispatchQueue.main.async {
            guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
            let picker = AVRoutePickerView(frame: CGRect(x: -100, y: -100, width: 1, height: 1))
            window.addSubview(picker)
            picker.subviews.compactMap { $0 as? UIButton }.first?.sendActions(for: .touchUpInside)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { picker.removeFromSuperview() }
        }
    }

    // MARK: IosPlayerDriver

    func load(itemId: String, durationMs: Int64) {
        renderer.load(itemId, durationMs: durationMs)
        publishPlayback()
    }

    func play() {
        if renderer.itemId == nil { renderer.load("tone-1k", durationMs: 30_000) }
        startEngineIfNeeded()
        renderer.playing = true
        startStatusTimer()
        publishPlayback()
    }

    func pause() {
        renderer.playing = false
        publishPlayback()
    }

    func seek(positionMs: Int64) {
        renderer.position = max(0, min(renderer.durationFrames, positionMs * Int64(renderer.sampleRate) / 1000))
        publishPlayback()
    }

    // MARK: Engine graph

    private func rebuildGraph() {
        let wasRunning = engine.isRunning
        engine.stop()
        if let source { engine.detach(source) }

        let hw = engine.outputNode.outputFormat(forBus: 0)
        renderer.sampleRate = hw.sampleRate > 0 ? hw.sampleRate : 48_000
        let channels = max(hw.channelCount, 2)
        let layout = AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_DiscreteInOrder | UInt32(channels))!
        let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: renderer.sampleRate, interleaved: false, channelLayout: layout)

        if multiRoute {
            // Hardware channels are the route's output channels in order; tag each with its owning port.
            let owners = session.currentRoute.outputs.flatMap { port in (port.channels ?? []).map { _ in port.uid } }
            renderer.channelOwners = owners
            renderer.activeUids = Set(multiTargets).intersection(owners)
            sink?.multiRouteChanged(activeUids: Array(renderer.activeUids))
        } else {
            renderer.channelOwners = nil
            renderer.activeUids = []
            sink?.multiRouteChanged(activeUids: [])
        }

        let r = renderer
        let node = AVAudioSourceNode(format: format) { _, _, frameCount, bufferList in
            r.render(frameCount: Int(frameCount), buffers: UnsafeMutableAudioBufferListPointer(bufferList))
            return noErr
        }
        engine.attach(node)
        engine.connect(node, to: engine.outputNode, format: format)
        source = node
        if wasRunning || multiRoute || renderer.playing { startEngineIfNeeded() }
    }

    private func startEngineIfNeeded() {
        if source == nil { rebuildGraph() }
        guard !engine.isRunning else { return }
        do {
            try session.setActive(true)
            engine.prepare()
            try engine.start()
        } catch {
            NSLog("PartyMusik engine start failed: \(error)")
        }
    }

    private func startStatusTimer() {
        guard statusTimer == nil else { return }
        statusTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.publishPlayback()
        }
    }

    private func publishPlayback() {
        let sr = renderer.sampleRate
        sink?.playbackChanged(
            itemId: renderer.itemId,
            playing: renderer.playing,
            positionMs: Int64(Double(renderer.position) / sr * 1000),
            durationMs: Int64(Double(renderer.durationFrames) / sr * 1000)
        )
    }

    // MARK: Sync probe (MED-007, S-10)

    /// Plays `signal` on the channels of `uid` and records the microphone. Recording stays in memory.
    func probe(uid: String, signal: [Float], sampleRate: Double, completion: @escaping ([Float]?, Int) -> Void) {
        startEngineIfNeeded()
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0 else { completion(nil, 0); return }
        let hwRate = renderer.sampleRate
        var recorded: [Float] = []
        var emitFrame = -1
        let total = Int(inFormat.sampleRate)

        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
            guard let self, let ch = buffer.floatChannelData?[0] else { return }
            recorded.append(contentsOf: UnsafeBufferPointer(start: ch, count: Int(buffer.frameLength)))
            if emitFrame < 0 && recorded.count >= total / 10 {
                emitFrame = recorded.count
                self.renderer.probeSamples = Self.resample(signal, from: sampleRate, to: hwRate)
                self.renderer.probeUid = uid
                self.renderer.probeIndex = 0
            }
            if recorded.count >= total {
                input.removeTap(onBus: 0)
                self.renderer.probeSamples = []
                let down = Self.resample(Array(recorded.prefix(total)), from: inFormat.sampleRate, to: sampleRate)
                let emit = Int(Double(emitFrame) * sampleRate / inFormat.sampleRate)
                DispatchQueue.main.async { completion(down, emit) }
            }
        }
    }

    static func resample(_ x: [Float], from: Double, to: Double) -> [Float] {
        guard from != to, !x.isEmpty else { return x }
        let n = Int(Double(x.count) * to / from)
        return (0..<n).map { i in
            let pos = Double(i) * from / to
            let j = Int(pos), f = Float(pos - Double(j))
            return j + 1 < x.count ? x[j] * (1 - f) + x[j + 1] * f : x[min(j, x.count - 1)]
        }
    }

    // MARK: Notifications

    private func notifyRoute(_ reason: String) {
        DispatchQueue.main.async { self.sink?.routeChanged(reason: reason) }
    }

    @objc private func routeChanged(_ note: Notification) {
        let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        let reason = raw.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
        if reason == .oldDeviceUnavailable { /* engine fallback policy decides; nothing to do here */ }
        if multiRoute { DispatchQueue.main.async { self.rebuildGraph() } }
        notifyRoute(String(describing: raw ?? 0))
    }

    @objc private func interrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            renderer.playing = false
            DispatchQueue.main.async { self.publishPlayback() }
        }
        // Resumption after interruptions is driven by the shared routing engine (RTE-006/007).
    }

    @objc private func mediaServicesReset() {
        DispatchQueue.main.async {
            self.source = nil
            self.configureMediaSession()
            self.rebuildGraph()
            self.sink?.mediaServicesReset()
        }
    }

    @objc private func engineConfigChanged() {
        DispatchQueue.main.async { self.rebuildGraph() }
    }
}
