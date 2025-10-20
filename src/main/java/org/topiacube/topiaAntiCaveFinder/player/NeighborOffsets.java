package org.topiacube.topiaAntiCaveFinder.player;

final class NeighborOffsets {
    static final int[][] REVEAL_DIRECTIONS = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 1, 0},
        {0, -1, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    static final int[][] LATERAL_OFFSETS_X = {
        {0, 1, 0},
        {0, -1, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    static final int[][] LATERAL_OFFSETS_Y = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    static final int[][] LATERAL_OFFSETS_Z = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 1, 0},
        {0, -1, 0}
    };
    static final int[][] DIAGONAL_OFFSETS_X = {
        {0, 1, 1},
        {0, 1, -1},
        {0, -1, 1},
        {0, -1, -1}
    };
    static final int[][] DIAGONAL_OFFSETS_Y = {
        {1, 0, 1},
        {1, 0, -1},
        {-1, 0, 1},
        {-1, 0, -1}
    };
    static final int[][] DIAGONAL_OFFSETS_Z = {
        {1, 1, 0},
        {1, -1, 0},
        {-1, 1, 0},
        {-1, -1, 0}
    };

    private NeighborOffsets() {
    }
}
