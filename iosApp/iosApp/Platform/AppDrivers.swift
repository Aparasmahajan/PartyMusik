import AVFoundation
import Foundation
import ComposeApp

/// Composes the Swift drivers that the Kotlin `IosPlatform` adapts into shared ports.
final class AppDrivers: NSObject, IosDrivers {
    let audio = AudioController()
    lazy var callController = CallController(audio: audio)
    lazy var systemServices = SystemServices(audio: audio, calls: callController)

    var session: IosAudioSessionDriver { audio }
    var player: IosPlayerDriver { audio }
    var calls: IosCallDriver { callController }
    var system: IosSystemDriver { systemServices }

    func attach(sink: IosEventSink) {
        audio.sink = sink
        callController.sink = sink
        systemServices.sink = sink
    }
}

/// Normalised port types shared with Kotlin (see IosDrivers.kt).
extension AVAudioSessionPortDescription {
    var normalisedType: String {
        switch portType {
        case .builtInSpeaker: return "speaker"
        case .builtInReceiver: return "receiver"
        case .builtInMic: return "mic_builtin"
        case .headphones, .headsetMic, .lineOut, .lineIn: return "wired"
        case .usbAudio: return "usb"
        case .HDMI, .displayPort: return "hdmi"
        case .bluetoothA2DP: return "bt_a2dp"
        case .bluetoothHFP: return "bt_hfp"
        case .bluetoothLE: return "bt_le"
        case .airPlay: return "airplay"
        case .carAudio: return "car"
        default: return "other"
        }
    }

    func toIosPort(isInput: Bool) -> IosPort {
        IosPort(uid: uid, name: portName, portType: normalisedType, channels: Int32(channels?.count ?? 0), isInput: isInput)
    }
}

