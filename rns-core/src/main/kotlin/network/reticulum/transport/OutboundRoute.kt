package network.reticulum.transport

import network.reticulum.common.DestinationType
import network.reticulum.common.PacketType

/**
 * Chooses how an outbound packet leaves this instance.
 *
 * Kept as a pure function because the decision, not the networking under it,
 * is where link packets went wrong: a link established one hop away and behind
 * a shared instance had its post-establishment packets wrapped in a transport
 * header addressed to the link id itself, which no transport node will forward
 * (#588).
 */
object OutboundRoute {

    enum class Choice {
        /** Send the packet as packed, on the interface the path names. */
        PATH_DIRECT,

        /** Wrap in a HEADER_2 transport header for the path's next hop. */
        PATH_TRANSPORT,

        /**
         * Fall through to the attached-interface / broadcast branch, which
         * restricts a link packet to the link's own attached interface
         * (Python Transport.py:1031-1035).
         */
        ATTACHED_OR_BROADCAST,
    }

    /**
     * @param destinationType the packet's destination type
     * @param packetType the packet's type
     * @param hasUsablePath whether the path table holds an unexpired entry
     * @param pathHops hop count on that entry (meaningless if [hasUsablePath] is false)
     * @param behindSharedInstance whether this instance reaches the network
     *   through a shared instance rather than its own interfaces
     */
    fun choose(
        destinationType: DestinationType,
        packetType: PacketType,
        hasUsablePath: Boolean,
        pathHops: Int,
        behindSharedInstance: Boolean,
    ): Choice {
        // A link is not a routable destination. Its id names a link, not a
        // node, so a transport header built from a link path carries the link
        // id as its next hop and the packet is addressed to a transport node
        // that does not exist. Python never routes link packets: they go out
        // on the link's attached interface and nowhere else. Observed against
        // v5.87.59 as "Version handshake timed out" whenever the link happened
        // to be one hop away — the link established, then LINKIDENTIFY and the
        // first CHANNEL packet were dropped by the shared instance and the
        // server logged nothing after sending its proof.
        if (destinationType == DestinationType.LINK) return Choice.ATTACHED_OR_BROADCAST

        if (!hasUsablePath) return Choice.ATTACHED_OR_BROADCAST
        if (packetType == PacketType.ANNOUNCE) return Choice.ATTACHED_OR_BROADCAST
        if (destinationType == DestinationType.PLAIN) return Choice.ATTACHED_OR_BROADCAST
        if (destinationType == DestinationType.GROUP) return Choice.ATTACHED_OR_BROADCAST

        // Multi-hop DATA still broadcasts: HEADER_2 transport routing has
        // known trouble with nextHop for data packets.
        if (packetType == PacketType.DATA && pathHops != 1) return Choice.ATTACHED_OR_BROADCAST

        if (pathHops > 1) return Choice.PATH_TRANSPORT

        // One hop away, but behind a shared instance the packet still needs a
        // transport header so the instance can put it on the network
        // (Python Transport.py:993-1011).
        if (behindSharedInstance) return Choice.PATH_TRANSPORT

        return Choice.PATH_DIRECT
    }
}
