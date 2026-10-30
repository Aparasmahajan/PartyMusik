package io.mahajan.partymusik.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.EndpointKind

/**
 * Line icons on a 24-unit grid (1.8 stroke, round caps), drawn here so the app needs no icon dependency.
 * Every icon is tinted by `Icon(tint = …)`, so the path colours below are placeholders.
 */
object PartyIcons {
    val Speaker = icon("speaker", stroke(rect(7f, 2.5f, 10f, 19f, 3f)), stroke(circle(12f, 14.5f, 3f)), stroke(circle(12f, 7f, 1f)))
    val Headphones = icon(
        "headphones", stroke("M4 15v-3a8 8 0 0 1 16 0v3"),
        stroke(rect(3f, 14f, 4.5f, 7f, 1.8f)), stroke(rect(16.5f, 14f, 4.5f, 7f, 1.8f)),
    )
    val Earbuds = icon(
        "earbuds",
        stroke("M7 4a3 3 0 0 1 3 3v3a3 3 0 0 1-6 0V7a3 3 0 0 1 3-3zM7 13v7M17 4a3 3 0 0 1 3 3v3a3 3 0 0 1-6 0V7a3 3 0 0 1 3-3zM17 13v7"),
    )
    val Phone = icon("phone", stroke(rect(6f, 2f, 12f, 20f, 3f)), stroke("M10.5 18.5h3"))
    val Earpiece = icon("earpiece", stroke(rect(6f, 2f, 12f, 20f, 3f)), stroke("M10 5.5h4"))
    val Car = icon(
        "car", stroke("M3 13l2.2-6h13.6l2.2 6v4H3z"), stroke("M3 13h18"),
        stroke(circle(7f, 17f, 1.6f)), stroke(circle(17f, 17f, 1.6f)),
    )
    val Usb = icon("usb", stroke(rect(9f, 2f, 6f, 6f, 1f)), stroke("M6 8h12v8a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4z"))
    val SpeakerWave = icon("speaker-wave", stroke("M4 9v6h4l5 4V5L8 9z"), stroke("M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"))
    val Volume = icon("volume", fill("M4 9v6h4l5 4V5L8 9z"), stroke("M16.5 8.5a5 5 0 0 1 0 7"))
    val Route = icon(
        "route", stroke("M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"), fill("M12 15l5 6H7z"),
    )
    val Call = icon("call", stroke("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"))
    val EndCall = icon(
        "end-call",
        fill(
            "M12 9c-3.3 0-6.4 1-8.7 2.8a1.6 1.6 0 0 0-.3 2.2l1.4 1.8a1.6 1.6 0 0 0 2 .4l2.2-1.2a1.6 1.6 0 0 0 .8-1.5" +
                "l-.1-1.4a12 12 0 0 1 5.4 0l-.1 1.4a1.6 1.6 0 0 0 .8 1.5l2.2 1.2a1.6 1.6 0 0 0 2-.4l1.4-1.8" +
                "a1.6 1.6 0 0 0-.3-2.2C18.4 10 15.3 9 12 9z",
        ),
    )
    val Mic = icon("mic", stroke(rect(9f, 3f, 6f, 11f, 3f)), stroke("M5 11a7 7 0 0 0 14 0M12 18v3"))
    val MicOff = icon("mic-off", stroke(rect(9f, 3f, 6f, 11f, 3f)), stroke("M5 11a7 7 0 0 0 14 0M12 18v3M4 4l16 16"))
    val Play = icon("play", fill("M8 5.2v13.6a1 1 0 0 0 1.5.86l11-6.8a1 1 0 0 0 0-1.72l-11-6.8A1 1 0 0 0 8 5.2z"))
    val Pause = icon("pause", fill(rect(6f, 4.5f, 4f, 15f, 1.2f)), fill(rect(14f, 4.5f, 4f, 15f, 1.2f)))
    val SkipBack = icon("skip-back", stroke("M4 12a8 8 0 1 0 2.4-5.7"), stroke("M4 3.5v4.5h4.5"))
    val SkipForward = icon("skip-forward", stroke("M20 12a8 8 0 1 1-2.4-5.7"), stroke("M20 3.5v4.5h-4.5"))
    val Check = icon("check", stroke("M5 12.5l4.5 4.5L19 7.5"), width = 2.6f)
    val Chevron = icon("chevron", stroke("M9 6l6 6-6 6"), width = 2.4f)
    val ChevronUp = icon("chevron-up", stroke("M6 15l6-6 6 6"), width = 2.2f)
    val ChevronDown = icon("chevron-down", stroke("M6 9l6 6 6-6"), width = 2.2f)
    val Close = icon("close", stroke("M6 6l12 12M18 6L6 18"), width = 2.6f)
    val Plus = icon("plus", stroke("M12 5v14M5 12h14"), width = 2.2f)
    val Queue = icon("queue", stroke("M4 6h16M4 12h16M4 18h10"), width = 2f)
    val Info = icon("info", stroke(circle(12f, 12f, 9.5f)), stroke("M12 11v5M12 7.8v.4"))
    val Warning = icon("warning", stroke("M12 3l10 18H2z"), stroke("M12 10v5M12 17.8v.4"))
    val Clock = icon("clock", stroke(circle(12f, 12f, 9f)), stroke("M12 7v5l3 2"))
    val Waveform = icon("waveform", stroke("M3 12h2M7 8v8M11 5v14M15 8v8M19 11v2"))
    val Bluetooth = icon("bluetooth", stroke("M7 7l10 10-5 5V2l5 5L7 17"))
    val Note = icon(
        "note", stroke("M9 18V5l11-2v13"), stroke(circle(6.5f, 18f, 2.5f)), stroke(circle(17.5f, 16f, 2.5f)),
    )
    val Sliders = icon(
        "sliders", stroke("M4 7h10M18 7h2M4 17h2M10 17h10"), stroke(circle(16f, 7f, 2f)), stroke(circle(8f, 17f, 2f)),
    )

    val TabNow = icon("tab-now", stroke(circle(12f, 12f, 9.5f)), fill("M10 8.2v7.6l6-3.8z"))
    val TabOutputs = icon(
        "tab-outputs", stroke(rect(3f, 4f, 8f, 16f, 2.5f)), stroke(circle(7f, 14f, 2.2f)),
        stroke(rect(13f, 4f, 8f, 16f, 2.5f)), stroke(circle(17f, 14f, 2.2f)),
    )
    val TabProfiles = icon(
        "tab-profiles", stroke(rect(3.5f, 3.5f, 7f, 7f, 2f)), stroke(rect(13.5f, 3.5f, 7f, 7f, 2f)),
        stroke(rect(3.5f, 13.5f, 7f, 7f, 2f)), stroke(circle(17f, 17f, 3.5f)),
    )
    val TabLab = icon("tab-lab", stroke("M9 3h6M10 3v6l-5.2 9.1A2 2 0 0 0 6.5 21h11a2 2 0 0 0 1.7-2.9L14 9V3"), stroke("M7.5 15h9"))

    /** Classic Bluetooth media covers both headsets and speakers; a device without a mic is drawn as a speaker. */
    fun forEndpoint(ep: AudioEndpoint): ImageVector =
        if (ep.kind == EndpointKind.BT_CLASSIC_MEDIA && Capability.MICROPHONE !in ep.capabilities) Speaker else forKind(ep.kind)

    /** Glyph for an output, by what the user would call it. */
    fun forKind(kind: EndpointKind?): ImageVector = when (kind) {
        EndpointKind.BUILTIN_SPEAKER -> Phone
        EndpointKind.BUILTIN_EARPIECE -> Earpiece
        EndpointKind.WIRED, EndpointKind.BT_CLASSIC_MEDIA -> Headphones
        EndpointKind.BT_LE_AUDIO, EndpointKind.HEARING_AID -> Earbuds
        EndpointKind.BT_CLASSIC_HFP, EndpointKind.CAR_AUDIO -> Car
        EndpointKind.USB -> Usb
        EndpointKind.BT_LE_BROADCAST, EndpointKind.AIRPLAY, EndpointKind.HDMI -> Route
        EndpointKind.OTHER, null -> SpeakerWave
    }
}

private class Part(val d: String, val filled: Boolean)

private fun stroke(d: String) = Part(d, filled = false)
private fun fill(d: String) = Part(d, filled = true)

private fun icon(name: String, vararg parts: Part, width: Float = 1.8f): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    parts.forEach { p ->
        b.addPath(
            pathData = addPathNodes(p.d),
            fill = if (p.filled) SolidColor(Color.Black) else null,
            stroke = if (p.filled) null else SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}" +
        "a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"
