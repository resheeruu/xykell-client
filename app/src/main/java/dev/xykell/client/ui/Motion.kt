package dev.xykell.client.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator

/**
 * One place for motion: durations, curves, and the reduced-motion rule.
 *
 * **Why a single source.** Before this, durations were literals scattered across
 * fragments — the splash used 400/500/300 inline, and reduced-motion was
 * checked separately in two files. That is how a UI ends up with six slightly
 * different fades and a "reduced motion" setting that only some screens honour.
 * Motion reads as modern when it is *consistent*: the same entrance, the same
 * curve, the same timing everywhere.
 *
 * **The curves.** [emphasized] and [emphasizedDecelerate] approximate the
 * Material emphasis curves — a slow start that commits hard, and the mirror of
 * it for exits. They are expressed as cubic-bezier control points, which is the
 * same shape the platform uses internally, so motion composed from these and
 * motion composed from platform transitions sit together without a seam.
 *
 * **Reduced motion is not optional.** Vestibular disorders are real and a
 * parallax-style sweep is a common trigger. [Motion.reduced] is read from the
 * system animator scale, so a user who has already turned animations off in
 * developer options or accessibility settings gets none of this — we do not ask
 * them again per screen, and no animation here overrides the system setting.
 * When reduced, durations collapse to zero rather than the animations being
 * skipped, so any `withEndAction` still runs and the UI cannot deadlock waiting
 * for an animation that never finishes.
 *
 * No Android view is touched at construction; [Motion.of] is resolved once and
 * reused.
 */
object Motion {

    /** Fast feedback: a press, a toggle, a highlight. */
    const val DURATION_SHORT = 120L

    /** The default entrance for a single element. */
    const val DURATION_MEDIUM = 220L

    /** A larger surface arriving. */
    const val DURATION_LONG = 320L

    /** Delay between items in a staggered list. */
    const val STAGGER_STEP = 28L

    /** Cap on how long a long list may take to finish staggering. */
    const val STAGGER_MAX_STEPS = 8

    /** Distance an entering element travels. Small — this is polish, not theatre. */
    const val ENTER_TRANSLATE_DP = 12f

    /** Material emphasis: fast out, slow in. Used for entrances. */
    val emphasized: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** The mirror of [emphasized]. Used for exits and dismissals. */
    val emphasizedAccelerate: TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** Standard curve, for movement between two on-screen positions. */
    val standard: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    @Volatile
    private var cachedReduced: Boolean? = null

    /**
     * Resolves reduced-motion once. The animator scale is 0 when animations are
     * off system-wide and 1 when they are on; anything in between is treated as
     * on, because a user who has dialled animations down has not asked for them
     * to disappear.
     */
    fun reduced(context: Context): Boolean {
        cachedReduced?.let { return it }
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (e: Exception) {
            1f
        }
        val off = scale == 0f
        cachedReduced = off
        return off
    }

    /** Test seam and a way to re-read after a settings change. */
    fun resetForTesting(context: Context) {
        cachedReduced = null
        reduced(context)
    }

    /** Collapses a duration when motion is reduced, rather than skipping it. */
    fun duration(context: Context, millis: Long): Long =
        if (reduced(context)) 0L else millis
}

/** Stagger an already-populated vertical container's children. */
fun View.staggerEntrance(context: Context) {
    val container = this as? android.view.ViewGroup ?: return
    val reduced = Motion.reduced(context)
    val step = if (reduced) 0L else Motion.STAGGER_STEP
    val enter = if (reduced) 0f else Motion.ENTER_TRANSLATE_DP * resources.displayMetrics.density

    // A long list staggering every row would take longer than the user waits,
    // so only the first few move and the rest simply appear.
    val animated = minOf(container.childCount, Motion.STAGGER_MAX_STEPS)
    for (i in 0 until container.childCount) {
        val child = container.getChildAt(i)
        if (i < animated) {
            child.alpha = 0f
            child.translationY = enter
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(step * i)
                .setDuration(Motion.duration(context, Motion.DURATION_MEDIUM))
                .setInterpolator(Motion.emphasized)
                .start()
        } else if (!reduced) {
            child.alpha = 1f
        }
    }
}

/** Fade a view in, honouring reduced motion. End action always runs. */
fun View.fadeIn(context: Context, delayMs: Long = 0L, onEnd: (() -> Unit)? = null) {
    if (Motion.reduced(context)) {
        alpha = 1f
        onEnd?.invoke()
        return
    }
    animate()
        .alpha(1f)
        .setStartDelay(delayMs)
        .setDuration(Motion.DURATION_MEDIUM)
        .setInterpolator(decelerateForFade())
        .withEndAction { onEnd?.invoke() }
        .start()
}

/**
 * Decelerate curve for an alpha fade on its own. [Motion.emphasized] is the
 * entrance curve, but a pure opacity change reads better decelerating, so the
 * two are separate rather than one reused for both.
 */
private fun decelerateForFade(): TimeInterpolator = DecelerateInterpolator(1.6f)

/** Straight-line interpolator, for continuous progress the user is driving. */
fun linear(): TimeInterpolator = LinearInterpolator()

/** Accelerating curve, for an element leaving. */
fun accelerate(): TimeInterpolator = AccelerateInterpolator(1.6f)