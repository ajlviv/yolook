package com.yolo.detector.tracking

import com.yolo.detector.data.Detection

/**
 * ByteTrack multi-object tracker (pure Kotlin, IoU-only association, no Re-ID model).
 *
 * Algorithm (per frame):
 * 1. Predict all active tracks forward by one step (Kalman predict).
 * 2. Split detections into high-confidence (≥ [HIGH_THRESH]) and low-confidence sets.
 * 3. **Stage 1**: Match high-confidence detections to active CONFIRMED/TENTATIVE tracks
 *    using the Hungarian algorithm on an IoU cost matrix.
 * 4. **Stage 2**: Match remaining low-confidence detections to LOST tracks.
 * 5. Unmatched high-confidence detections spawn new TENTATIVE tracks.
 * 6. Tracks unmatched in Stage 1 are marked LOST; LOST tracks not matched in Stage 2
 *    are removed after [MAX_AGE] frames.
 * 7. Return all CONFIRMED tracks as [Detection] instances with real track IDs.
 *
 * Reference: ByteTrack paper — https://arxiv.org/abs/2110.06864
 */
class ByteTracker {

    companion object {
        /** High-confidence threshold for Stage 1 detections. */
        const val HIGH_THRESH = 0.50f

        /** Low-confidence threshold for Stage 2 detections. */
        const val LOW_THRESH = 0.10f

        /** IoU cost threshold — pairs with cost > this are never matched (cost = 1 − IoU). */
        const val MATCH_THRESH = 0.70f   // i.e. IoU must be ≥ 0.30

        /** Number of consecutive matches required before a track is CONFIRMED. */
        const val MIN_HITS = 3

        /** Number of frames a LOST track is kept before deletion. */
        const val MAX_AGE = 30
    }

    private val activeTracks = mutableListOf<Track>()
    private val lostTracks = mutableListOf<Track>()
    private var nextTrackId = 1

    /**
     * Processes one frame of detections and returns tracked [Detection] instances.
     *
     * @param detections Raw detector output (trackId = -1) for the current frame.
     * @param timestampMs Frame timestamp for emitted detections.
     * @return CONFIRMED tracks, each with their assigned [Detection.trackId].
     */
    fun update(detections: List<Detection>, timestampMs: Long): List<Detection> {
        // ── Step 1: Predict all tracks ─────────────────────────────────────────
        activeTracks.forEach { it.predict() }
        lostTracks.forEach { it.predict() }

        // ── Step 2: Split detections by confidence ─────────────────────────────
        val highDets  = detections.filter { it.confidence >= HIGH_THRESH }
        val lowDets   = detections.filter { it.confidence in LOW_THRESH until HIGH_THRESH }

        // ── Step 3: Stage 1 — high-confidence dets vs active tracks ───────────
        val (matchedHighIdx, unmatchedHighDets, unmatchedActiveTracks) =
            associateDetectionsToTracks(highDets, activeTracks)

        val matchedHighDetSet = matchedHighIdx.map { it.first }.toSet()
        val matchedHighTrackSet = matchedHighIdx.map { it.second }.toSet()

        for ((detIdx, trackIdx) in matchedHighIdx) {
            activeTracks[trackIdx].update(highDets[detIdx])
        }

        // Tracks not matched in Stage 1 → candidates for Stage 2 or LOST
        val unconfirmedTracks = activeTracks.filterIndexed { idx, _ -> idx !in matchedHighTrackSet }
        val lostCandidates = unconfirmedTracks.filter { it.state == TrackState.LOST || it.state == TrackState.CONFIRMED }

        // ── Step 4: Stage 2 — low-confidence dets vs lost tracks ──────────────
        val (matchedLowIdx, _, unmatchedLostTracks) =
            associateDetectionsToTracks(lowDets, lostTracks + lostCandidates)

        for ((detIdx, trackIdx) in matchedLowIdx) {
            val allLostPool = lostTracks + lostCandidates
            allLostPool[trackIdx].update(lowDets[detIdx])
        }

        // ── Step 5: Spawn new tracks from unmatched high-confidence dets ───────
        val unmatchedHighDetections = highDets.filterIndexed { idx, _ -> idx in unmatchedHighDets }
        for (det in unmatchedHighDetections) {
            activeTracks.add(Track(nextTrackId++, det))
        }

        // ── Step 6: Mark/remove tracks ─────────────────────────────────────────
        for (trackIdx in unmatchedActiveTracks) {
            activeTracks[trackIdx].markLost()
        }

        // Move newly LOST tracks to lostTracks
        val newlyLost = activeTracks.filter { it.state == TrackState.LOST }
        lostTracks.addAll(newlyLost)
        activeTracks.removeAll(newlyLost.toSet())

        // Remove stale LOST tracks
        lostTracks.removeAll { it.framesSinceUpdate > MAX_AGE }

        // ── Step 7: Return confirmed tracks ────────────────────────────────────
        return activeTracks
            .filter { it.state == TrackState.CONFIRMED }
            .map { it.toDetection(timestampMs) }
    }

    /** Resets all tracking state. Call when the camera session restarts. */
    fun reset() {
        activeTracks.clear()
        lostTracks.clear()
        nextTrackId = 1
    }

    // ── Association ───────────────────────────────────────────────────────────

    /**
     * Associates [detections] to [tracks] using the Hungarian algorithm on an IoU cost matrix.
     *
     * @return Triple(matchedPairs, unmatchedDetIndices, unmatchedTrackIndices).
     */
    private fun associateDetectionsToTracks(
        detections: List<Detection>,
        tracks: List<Track>,
    ): Triple<List<Pair<Int, Int>>, Set<Int>, Set<Int>> {
        if (detections.isEmpty() || tracks.isEmpty()) {
            return Triple(emptyList(), detections.indices.toSet(), tracks.indices.toSet())
        }

        val detBoxes = detections.map { it.bbox }
        val trackBoxes = tracks.map {
            val (cx, cy, w, h) = it.kalman.stateToBbox()
            android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

        val costMatrix = HungarianMatcher.iouCostMatrix(detBoxes, trackBoxes)
        val assignment = HungarianMatcher.match(costMatrix, MATCH_THRESH)

        val matched = mutableListOf<Pair<Int, Int>>()
        val unmatchedDets = mutableSetOf<Int>()
        val unmatchedTracks = (tracks.indices).toMutableSet()

        for ((detIdx, trackIdx) in assignment.withIndex()) {
            if (trackIdx == -1) {
                unmatchedDets.add(detIdx)
            } else {
                matched.add(detIdx to trackIdx)
                unmatchedTracks.remove(trackIdx)
            }
        }

        return Triple(matched, unmatchedDets, unmatchedTracks)
    }
}
