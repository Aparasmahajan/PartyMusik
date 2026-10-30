package io.mahajan.partymusik.diagnostics

data class HelpArticle(val key: String, val title: String, val steps: List<String>)

/** User-readable guidance keyed by error code (DIA-007) plus the mandatory disclosures (AC9). */
object Troubleshooting {

    private val articles = listOf(
        HelpArticle(
            "RTE_TIMEOUT", "The system didn't confirm the switch",
            listOf(
                "Make sure the device is on, in range and connected in system Bluetooth settings.",
                "Some headsets take a few seconds to switch profiles; try again.",
                "Open Diagnostics to see which output the system reports as active.",
            ),
        ),
        HelpArticle(
            "RTE_OS_REDIRECTED", "The system chose a different output",
            listOf(
                "Another app, a call, or the phone's own audio policy moved the audio.",
                "Select the device again, or use the system output picker.",
                "If it keeps happening, run the compatibility test and share the result.",
            ),
        ),
        HelpArticle(
            "RTE_DEVICE_GONE", "The device disconnected",
            listOf(
                "Check the battery and range of the device.",
                "Your profile's fallback rule decides where audio goes next; change it in Profiles.",
            ),
        ),
        HelpArticle(
            "RTE_NOT_MEDIA_CAPABLE", "This device is for calls only",
            listOf(
                "Car kits and some headsets connect only with the hands-free (calls) profile.",
                "Enable 'Media audio' for the device in system Bluetooth settings if available.",
            ),
        ),
        HelpArticle(
            "RTE_NOT_COMM_CAPABLE", "This device can't carry call audio",
            listOf(
                "Speakers usually have no microphone or hands-free profile.",
                "Pick a headset, earbuds, or the phone earpiece for calls.",
            ),
        ),
        HelpArticle(
            "RTE_UNSUPPORTED_COMBO", "These outputs can't play together",
            listOf(
                "This phone or accessory combination doesn't support simultaneous playback.",
                "Supported combinations are listed in Diagnostics › Compatibility.",
                "LE Audio broadcast (Auracast) or a speaker's own party mode may work instead.",
            ),
        ),
        HelpArticle(
            "RTE_SESSION_CONFLICT", "Another app is using audio",
            listOf("Finish the call or stop playback in the other app, then try again."),
        ),
        HelpArticle(
            "BT_OFF", "Bluetooth is off",
            listOf("Turn on Bluetooth from the banner or the system quick settings."),
        ),
        HelpArticle(
            "PERM", "A permission is missing",
            listOf("Open Permissions in this app to see what each permission is for and grant it."),
        ),
    )

    val disclosures: List<HelpArticle> = listOf(
        HelpArticle(
            "limit_third_party", "Other apps' audio",
            listOf(
                "Routing in PartyMusik applies to audio played by PartyMusik.",
                "Spotify, YouTube and other apps follow the system's output. Use 'Open system output picker' to move them.",
                "PartyMusik never records or re-transmits other apps' audio.",
            ),
        ),
        HelpArticle(
            "limit_cellular", "Phone calls",
            listOf(
                "Normal phone calls are controlled by your phone's dialer.",
                "During a call, use the dialer's audio/speaker button to change where the call is heard.",
                "PartyMusik pauses its own media during calls and restores it afterwards. It never records calls.",
            ),
        ),
        HelpArticle(
            "limit_multi", "Multiple outputs",
            listOf(
                "Simultaneous playback works only on combinations marked supported for your phone.",
                "Bluetooth adds delay; PartyMusik can measure and compensate, but perfect sync is not guaranteed.",
            ),
        ),
    )

    fun articleFor(code: String): HelpArticle =
        articles.firstOrNull { it.key == code }
            ?: articles.firstOrNull { code.startsWith(it.key) }
            ?: HelpArticle(code, "Something went wrong", listOf("Try again, or export diagnostics from the Diagnostics tab."))
}
