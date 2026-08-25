package network.reticulum.transport

import io.kotest.matchers.shouldBe
import network.reticulum.common.DestinationType
import network.reticulum.common.PacketType
import network.reticulum.transport.OutboundRoute.Choice
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboundRoute")
class OutboundRouteTest {

    private fun choose(
        destinationType: DestinationType = DestinationType.SINGLE,
        packetType: PacketType = PacketType.DATA,
        hasUsablePath: Boolean = true,
        pathHops: Int = 1,
        behindSharedInstance: Boolean = false,
    ) = OutboundRoute.choose(
        destinationType = destinationType,
        packetType = packetType,
        hasUsablePath = hasUsablePath,
        pathHops = pathHops,
        behindSharedInstance = behindSharedInstance,
    )

    @Nested
    @DisplayName("link packets are never routed (#588)")
    inner class LinkPackets {

        /**
         * The reported failure. A link established one hop away, behind a
         * shared instance: LINKIDENTIFY and CHANNEL were wrapped in a
         * transport header naming the link id as the next hop, the shared
         * instance dropped them, and the session died with "Version handshake
         * timed out" while the server logged nothing after sending its proof.
         */
        @Test
        fun `one hop behind a shared instance is not wrapped in transport`() {
            choose(
                destinationType = DestinationType.LINK,
                pathHops = 1,
                behindSharedInstance = true,
            ) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        /**
         * The case that worked, and must keep working: the same session two
         * hops out already fell through to the attached interface.
         */
        @Test
        fun `two hops behind a shared instance keeps its old route`() {
            choose(
                destinationType = DestinationType.LINK,
                pathHops = 2,
                behindSharedInstance = true,
            ) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `one hop with no shared instance is not routed either`() {
            choose(
                destinationType = DestinationType.LINK,
                pathHops = 1,
                behindSharedInstance = false,
            ) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `a link keepalive is not routed`() {
            choose(
                destinationType = DestinationType.LINK,
                packetType = PacketType.DATA,
                pathHops = 1,
                behindSharedInstance = true,
            ) shouldBe Choice.ATTACHED_OR_BROADCAST
        }
    }

    @Nested
    @DisplayName("non-link routing is unchanged")
    inner class NonLink {

        @Test
        fun `link request one hop behind a shared instance still gets a transport header`() {
            // A LINKREQUEST is addressed to the far SINGLE destination, not to
            // a link, and the shared instance does have to put it on the
            // network. This is the packet that succeeded in both reported logs.
            choose(
                destinationType = DestinationType.SINGLE,
                packetType = PacketType.LINKREQUEST,
                pathHops = 1,
                behindSharedInstance = true,
            ) shouldBe Choice.PATH_TRANSPORT
        }

        @Test
        fun `single destination one hop direct`() {
            choose(pathHops = 1, behindSharedInstance = false) shouldBe Choice.PATH_DIRECT
        }

        @Test
        fun `multi-hop data still broadcasts`() {
            choose(packetType = PacketType.DATA, pathHops = 3) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `multi-hop non-data is inserted into transport`() {
            choose(
                packetType = PacketType.PROOF,
                pathHops = 3,
            ) shouldBe Choice.PATH_TRANSPORT
        }

        @Test
        fun `no path means broadcast`() {
            choose(hasUsablePath = false, pathHops = 0) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `announces are never path-routed`() {
            choose(packetType = PacketType.ANNOUNCE, pathHops = 1) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `plain destinations are never path-routed`() {
            choose(destinationType = DestinationType.PLAIN, pathHops = 1) shouldBe Choice.ATTACHED_OR_BROADCAST
        }

        @Test
        fun `group destinations are never path-routed`() {
            choose(destinationType = DestinationType.GROUP, pathHops = 1) shouldBe Choice.ATTACHED_OR_BROADCAST
        }
    }
}
