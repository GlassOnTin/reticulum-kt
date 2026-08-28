package network.reticulum.resource

import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the atomic Resource.cancel() + terminal-state watchdog
 * guards (fork fix mirroring upstream reticulum-kt 400397a5 / afd646a7).
 *
 * Background: cancel() used to stop-then-status without a monitor, and
 * startWatchdog() had no terminal-state guard. Remote peers drive cancel()
 * from RESOURCE_ICL / RESOURCE_RCL (Link.processResourceIcl /
 * processResourceRcl) while our own watchdog can time out concurrently, so
 * both paths could conclude the same Resource twice, and a fresh watchdog
 * could be installed on an already-failed Resource.
 */
@DisplayName("Resource cancel/watchdog race tests")
class ResourceCancelRaceTest {

    @BeforeEach
    fun setup() {
        Transport.stop()
        Thread.sleep(100)
        Transport.start(enableTransport = false)
    }

    @AfterEach
    fun teardown() {
        Transport.stop()
        Thread.sleep(100)
    }

    @Test
    @DisplayName("startWatchdog on a terminal Resource is a no-op")
    @Timeout(5)
    fun `terminal resource rejects a fresh watchdog`() {
        val resource = makeBareResource()
        resource.cancel() // drives status to FAILED (status setter is private)
        assertEquals(ResourceConstants.FAILED, resource.status)

        resource.startWatchdogForTest()

        assertFalse(
            resource.watchdogActiveForTest(),
            "A terminal Resource must never get a fresh watchdog installed (afd646a7 guard)",
        )
    }

    @Test
    @DisplayName("cancel is idempotent: failed callback fires exactly once")
    @Timeout(5)
    fun `cancel fires failed exactly once`() {
        val resource = makeBareResource()
        var failures = 0
        resource.callbacks.failed = { failures++ }

        resource.cancel()
        resource.cancel()

        assertEquals(ResourceConstants.FAILED, resource.status)
        assertEquals(
            1,
            failures,
            "A second cancel (e.g. peer ICL/RCL racing the watchdog) must not re-deliver failed",
        )
    }

    @Test
    @DisplayName("concurrent cancel and watchdog start leave Resource terminal and stopped")
    @Timeout(10)
    fun `concurrent cancel and watchdog start leave Resource terminal and stopped`() {
        val resource = makeBareResource()

        val terminalPublished = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        resource.setCancelTransitionHookForTest {
            terminalPublished.countDown()
            assertTrue(releaseCancel.await(2, TimeUnit.SECONDS), "Test must release cancellation")
        }

        val cancelThread = thread(start = true, isDaemon = true) { resource.cancel() }
        assertTrue(
            terminalPublished.await(1, TimeUnit.SECONDS),
            "Cancellation must publish FAILED while holding the watchdog monitor",
        )
        assertEquals(ResourceConstants.FAILED, resource.status)

        val restartThread = thread(start = true, isDaemon = true) { resource.startWatchdogForTest() }
        val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (restartThread.state != Thread.State.BLOCKED && restartThread.isAlive && System.nanoTime() < blockedDeadline) {
            Thread.onSpinWait()
        }

        try {
            assertEquals(
                Thread.State.BLOCKED,
                restartThread.state,
                "Watchdog start must block on the atomic cancellation monitor",
            )
        } finally {
            releaseCancel.countDown()
            cancelThread.join(2_000)
            restartThread.join(2_000)
            resource.setCancelTransitionHookForTest(null)
        }

        assertFalse(cancelThread.isAlive, "Cancellation must finish after test release")
        assertFalse(restartThread.isAlive, "Watchdog restart must finish after cancellation")
        assertEquals(ResourceConstants.FAILED, resource.status)
        assertFalse(
            resource.watchdogActiveForTest(),
            "Atomic terminal transition must reject the waiting restart",
        )
    }

    private fun makeBareResource(): Resource {
        val identity = Identity.create()
        val destination = Destination.create(
            identity = identity,
            direction = DestinationDirection.IN,
            type = DestinationType.SINGLE,
            appName = "lifecycle",
            aspects = arrayOf("cancelrace", "test"),
        )
        val link = Link.create(destination)

        // Resource has a private constructor; reflection is lighter than
        // standing up a fully active Link + valid advertisement (same path the
        // upstream LinkResourceDedupTest uses).
        val ctor = Resource::class.java.getDeclaredConstructor(Link::class.java, Boolean::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(link, true) as Resource
    }
}
