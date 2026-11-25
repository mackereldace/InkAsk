package app.inkask.tablet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderIslandGlideTest {
    private val viewport = IntSize(1000, 800)
    private val handle = IntSize(54, 54)

    @Test fun slowReleaseDoesNotLaunch() {
        assertNull(readerIslandLaunch(Offset(250f, 160f), ReaderIslandEdge.Free, 360f, 5200f, 1000f, 2300f))
    }

    @Test fun launchRetainsFingerVelocityUpToTheSafetyCap() {
        val typical = readerIslandLaunch(Offset(2200f, -1650f), ReaderIslandEdge.Free, 360f, 5200f, 1000f, 2300f)!!
        val fast = readerIslandLaunch(Offset(-16_000f, 12_000f), ReaderIslandEdge.Free, 360f, 5200f, 1000f, 2300f)!!

        assertEquals(Offset(2200f, -1650f), typical.velocity)
        assertEquals(5200f, fast.velocity.getDistance(), .01f)
        assertEquals(-4160f, fast.velocity.x, .01f)
        assertEquals(3120f, fast.velocity.y, .01f)
    }

    @Test fun dockedLaunchKeepsItsEdgeUnlessFlickedStronglyIntoIt() {
        val tangent = readerIslandLaunch(Offset(-700f, 1800f), ReaderIslandEdge.Left, 360f, 5200f, 1000f, 2300f)!!
        val outward = readerIslandLaunch(Offset(-1800f, 600f), ReaderIslandEdge.Left, 360f, 5200f, 1000f, 2300f)!!

        assertEquals(ReaderIslandEdge.Left, tangent.edge)
        assertEquals(Offset(0f, 1800f), tangent.velocity)
        assertEquals(ReaderIslandEdge.Free, outward.edge)
        assertEquals(Offset(-1800f, 600f), outward.velocity)
    }

    @Test fun hittingAWallReflectsOnlyThePerpendicularVelocityAndLosesEnergy() {
        val after = ReaderIslandMotion(Offset(945f, 400f), Offset(2000f, 900f))
            .advance(.025f, viewport, handle, 10f)

        assertTrue(after.center.x in 945f..963f)
        assertTrue(after.center.y > 400f)
        assertTrue(after.velocity.x in -1050f..-750f)
        assertTrue(after.velocity.y in 740f..810f)

        val later = after.advance(.025f, viewport, handle, 10f)
        assertTrue(later.center.x < after.center.x)
        assertTrue(later.velocity.getDistance() < after.velocity.getDistance())
    }

    @Test fun aDockedIslandBouncesWhenThePillTouchesTheCorner() {
        val after = ReaderIslandMotion(Offset(37f, 665f), Offset(0f, 1700f))
            .advance(.032f, viewport, IntSize(54, 210), 10f)

        assertEquals(37f, after.center.x, .001f)
        assertEquals(0f, after.velocity.x, .001f)
        assertTrue(after.center.y in 660f..685f)
        assertTrue(after.velocity.y < 0f)
    }

    @Test fun anIslandThatFillsAnAxisRemainsCenteredOnIt() {
        val after = ReaderIslandMotion(Offset(3f, 400f), Offset(-4500f, 900f))
            .advance(.016f, IntSize(64, 800), handle, 10f)

        assertEquals(32f, after.center.x, .001f)
        assertEquals(0f, after.velocity.x, .001f)
        assertTrue(after.center.y > 400f)
    }

    @Test fun onlyAnEspeciallyFastReleaseEntersTheEasterEgg() {
        val normal = readerIslandLaunch(Offset(2299f, 0f), ReaderIslandEdge.Free, 360f, 5200f, 1000f, 2300f)!!
        val egg = readerIslandLaunch(Offset(2300f, 0f), ReaderIslandEdge.Free, 360f, 5200f, 1000f, 2300f)!!

        assertEquals(ReaderIslandGlideMode.Normal, normal.mode)
        assertEquals(ReaderIslandGlideMode.EasterEgg, egg.mode)
    }

    @Test fun theEasterEggBouncesAcrossATabletSeveralTimesAndSlowsToAStop() {
        val tablet = IntSize(1280, 800)
        val mode = ReaderIslandGlideMode.EasterEgg
        var motion = ReaderIslandMotion(Offset(260f, 400f), Offset(5000f, 0f))
        var bounces = 0
        val step = .008f
        for (i in 1..(mode.maxSeconds / step).toInt()) {
            val next = motion.advance(step, tablet, handle, 10f, mode, i * step)
            if (motion.velocity.x * next.velocity.x < 0f) bounces++
            assertTrue(next.center.x in 37f..1243f)
            motion = next
        }

        assertTrue("Expected at least three direction reversals, observed $bounces", bounces >= 3)
        assertTrue(motion.velocity.getDistance() < 38f)
    }

    @Test fun twoRecentSamplesStillGiveAReleaseVelocity() {
        val velocity = ReaderIslandVelocity(100L, Offset(100f, 0f))
        velocity.sample(135L, Offset(275f, 0f))

        assertEquals(Offset(5000f, 0f), velocity.release(140L, Offset(300f, 0f)))
    }

    @Test fun waitingBeforeLiftingStopsTheMomentum() {
        val velocity = ReaderIslandVelocity(0L, Offset.Zero)
        velocity.sample(35L, Offset(175f, 0f))
        velocity.sample(80L, Offset(175f, 0f))

        assertEquals(Offset.Zero, velocity.release(95L, Offset(175f, 0f)))
    }
}
