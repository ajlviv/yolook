package com.yolo.detector.alerts

import com.yolo.detector.data.Detection

/**
 * Global detection-alert state machine.
 *
 * States:
 *  - [State.Idle]        no object detected recently.
 *  - [State.Detecting]   an object has been present, tracked by consecutive found-frames.
 *  - [State.Cooldown]     an alert was just fired; new detections are ignored until
 *                         [cooldownMs] elapses.
 *
 * Transition rules, given one inference result per call to [onDetections]:
 *  1. Idle + object found                      → Detecting(1).
 *  2. Detecting(n) + object found              → Detecting(n+1); once n reaches
 *                                               [confirmFrames], fire the trigger and
 *                                               move to Cooldown(now + cooldownMs).
 *  3. Detecting + no object for [resetLostFrames]
 *     consecutive calls                        → back to Idle (no alert).
 *  4. Cooldown + detections                    → ignored until the timer expires, then Idle.
 *
 * The class is deliberately Android-free so the state logic can be unit-tested in
 * isolation (see DetectionThrottlerTest). All state mutation runs on the single
 * analysis thread that feeds YOLO frames; [reset] may be called from any thread on
 * teardown.
 *
 * Clock requirement: [onDetections] timestamps must come from a **monotonic** clock
 * (e.g. `SystemClock.elapsedRealtime()`). Wall-clock time can jump backwards or
 * forwards (NTP, user changes, timezone), which would expire or stretch COOLDOWN
 * and cause duplicate or missing alerts. Production callers pass elapsed-realtime;
 * tests use synthetic increasing values.
 *
 * @param initialCooldownMs default quiet period between alerts (ms).
 * @param confirmFrames     consecutive found-frames required before firing.
 * @param resetLostFrames   consecutive empty frames that abort an in-progress confirm.
 */
class DetectionThrottler(
    initialCooldownMs: Long = 60_000L,
    private val confirmFrames: Int = 3,
    private val resetLostFrames: Int = 3,
) {

    init {
        require(confirmFrames in 2..10) { "confirmFrames must be in 2..10" }
        require(resetLostFrames >= 1) { "resetLostFrames must be >= 1" }
    }

    /** "Don't spam me" quiet period after an alert. Applies to the next trigger. */
    @Volatile
    var cooldownMs: Long = initialCooldownMs
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    sealed interface State {
        data object Idle : State
        data class Detecting(val consecutiveFound: Int) : State
        data class Cooldown(val untilMs: Long) : State
    }

    private var state: State = State.Idle
    private var consecutiveEmpty = 0

    /** Current machine state (read-only; useful for diagnostics/tests). */
    val currentState: State get() = state

    /**
     * Feeds one inference result into the state machine.
     *
     * @param detections this frame's detections (already class-filtered upstream).
     * @param nowMs      monotonic timestamp (elapsed-realtime base) used for cooldown
     *                   bookkeeping. Must never go backwards; see the class KDoc.
     * @return an [AlertTrigger] the caller should act on, or null to keep watching.
     */
    fun onDetections(detections: List<Detection>, nowMs: Long): AlertTrigger? {
        val found = detections.isNotEmpty()
        when (val s = state) {
            is State.Idle -> {
                if (found) {
                    consecutiveEmpty = 0
                    state = State.Detecting(1)
                }
            }

            is State.Detecting -> {
                if (found) {
                    consecutiveEmpty = 0
                    val n = s.consecutiveFound + 1
                    if (n >= confirmFrames) {
                        state = State.Cooldown(nowMs + cooldownMs)
                        return AlertTrigger(
                            timestampMs = nowMs,
                            detectedClassIds = detections.mapTo(mutableSetOf()) { it.classId },
                        )
                    }
                    state = State.Detecting(n)
                } else {
                    consecutiveEmpty++
                    if (consecutiveEmpty >= resetLostFrames) state = State.Idle
                }
            }

            is State.Cooldown -> {
                if (nowMs >= s.untilMs) state = State.Idle
            }
        }
        return null
    }

    /** Unconditionally returns to [State.Idle] (e.g. camera teardown). */
    fun reset() {
        state = State.Idle
        consecutiveEmpty = 0
    }
}