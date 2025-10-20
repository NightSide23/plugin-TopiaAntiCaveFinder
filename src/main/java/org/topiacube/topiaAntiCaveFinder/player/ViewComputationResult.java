package org.topiacube.topiaAntiCaveFinder.player;

import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;

import java.util.List;

public record ViewComputationResult(
    PlayerViewSession session,
    PlayerSnapshot snapshot,
    List<TrackedBlock> trackedBlocks,
    double[] distanceSquares,
    int size
) {
}
