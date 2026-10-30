package io.mahajan.partymusik.engine

import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.Profile

/** Turns a profile's device references into endpoints that are connected right now. */
object ProfileResolver {
    private val mediaRank = listOf(
        EndpointKind.BT_LE_AUDIO, EndpointKind.BT_CLASSIC_MEDIA, EndpointKind.HEARING_AID, EndpointKind.WIRED,
        EndpointKind.USB, EndpointKind.CAR_AUDIO, EndpointKind.AIRPLAY, EndpointKind.HDMI,
        EndpointKind.BT_LE_BROADCAST, EndpointKind.OTHER, EndpointKind.BUILTIN_SPEAKER,
    )
    private val commRank = listOf(
        EndpointKind.BT_LE_AUDIO, EndpointKind.BT_CLASSIC_HFP, EndpointKind.BT_CLASSIC_MEDIA,
        EndpointKind.HEARING_AID, EndpointKind.WIRED, EndpointKind.USB, EndpointKind.CAR_AUDIO,
        EndpointKind.BUILTIN_EARPIECE, EndpointKind.BUILTIN_SPEAKER, EndpointKind.OTHER,
    )

    fun bestMedia(candidates: List<AudioEndpoint>): AudioEndpoint? =
        candidates.filter { it.canCarry(ChannelType.MEDIA) && it.kind != EndpointKind.BUILTIN_EARPIECE }
            .minByOrNull { mediaRank.indexOf(it.kind).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    fun bestComm(candidates: List<AudioEndpoint>): AudioEndpoint? =
        candidates.filter { it.canCarry(ChannelType.COMMUNICATION) }
            .minByOrNull { commRank.indexOf(it.kind).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    fun mediaTargets(profile: Profile, endpoints: List<AudioEndpoint>): List<EndpointId> {
        val media = endpoints.filter { it.canCarry(ChannelType.MEDIA) && it.kind != EndpointKind.BUILTIN_EARPIECE }
        if (profile.multiOutput) {
            val members = media.filter { Capability.MULTI_OUTPUT_MEMBER in it.capabilities }
            val pool = if (profile.mediaTargets.isEmpty()) {
                members
            } else {
                profile.mediaTargets.mapNotNull { ref -> members.firstOrNull { it.stableKey == ref.stableKey } }
            }
            return pool.map { it.id }
        }
        if (profile.mediaTargets.isEmpty()) return listOfNotNull(bestMedia(media)?.id)
        val preferred = profile.mediaTargets.firstNotNullOfOrNull { ref -> media.firstOrNull { it.stableKey == ref.stableKey } }
        return listOfNotNull(preferred?.id)
    }

    fun commTarget(profile: Profile, endpoints: List<AudioEndpoint>): EndpointId? {
        val comm = endpoints.filter { it.canCarry(ChannelType.COMMUNICATION) }
        val preferred = profile.commTarget?.let { ref -> comm.firstOrNull { it.stableKey == ref.stableKey } }
        return (preferred ?: bestComm(comm))?.id
    }
}
