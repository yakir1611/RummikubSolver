package com.example.rummikubsolver.vision;
import com.example.rummikubsolver.Board;
import com.example.rummikubsolver.Hand;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Takes the list of detected tiles and builds the actual game state.
 * board tiles get clustered into sets by geometry.
 * The board is treated as input that must be valid on its own, if what we
 * reconstructed isn't made purely of legal sets, that's a detection error and
 * we flag it. it's the user's job to fix the sets we read off the table.
 */
public final class BoardAssembler {
    // tuned around real tile proportions (26.5x36.5mm, ratio ~1.38).
    // neighbors in a set sit ~1.0-1.1 widths apart, the next row is 1.38+ away
    private static final double NEIGHBOR_DISTANCE_FACTOR = 1.3;
    // a chain has to keep going roughly straight, small bends are fine
    private static final double MAX_ANGLE_DEVIATION_DEG = 30.0;
    private static final int MIN_SET_SIZE = 3;
    // smallest chain worth trying to split
    private static final int MIN_SPLITTABLE_SIZE = 6;


    // public types
    public static final class DetectionResult  {
        public final Board board;
        public final Hand hand;
        public final List<Warning> warnings;

        DetectionResult (Board board, Hand hand, List<Warning> warnings) {
            this.board = board;
            this.hand = hand;
            this.warnings = Collections.unmodifiableList(warnings);
        }

        // true = safe to hand over to the solver
        public boolean isBoardValid() {
            for (Warning w : warnings) {
                if (w.isBlocking()) return false;
            }
            return true;
        }
    }

    public static final class Warning {
        public enum Type { TOO_SMALL, INVALID_SET, SPLIT_APPLIED, LOW_CONFIDENCE }
        public final Type type;
        // keeping DetectedTile (not Tile) so the UI can draw boxes on the photo
        public final List<DetectedTile> tiles;
        public final String message;

        Warning(Type type, List<DetectedTile> tiles, String message) {
            this.type = type;
            this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
            this.message = message;
        }

        public boolean isBlocking() {
            return type == Type.TOO_SMALL || type == Type.INVALID_SET;
        }
    }

    // a chain of physically adjacent tiles, in spatial order
    static final class SetChain {
        final List<DetectedTile> tiles;

        SetChain(List<DetectedTile> tiles) {
            this.tiles = tiles;
        }

        int size() { return tiles.size(); }

        // copy, not a subList view - the recursion slices a lot
        SetChain sub(int from, int to) {
            return new SetChain(new ArrayList<>(tiles.subList(from, to)));
        }
    }

    // entry point
    /**
     * imageAspect = width / height of the photo (e.g., bitmap.getWidth() / bitmap.getHeight())
     * Coordinates are normalized 0-1, but on portrait images the height is larger than width.
     * Without correction, vertical distances appear "squashed" (too small).
     * Divide y by imageAspect to compare distances fairly in both axes.
     */
    public static DetectionResult assemble(List<DetectedTile> detections, double imageAspect) {
        List<Warning> warnings = new ArrayList<>();
        List<DetectedTile> handTiles = new ArrayList<>();
        List<DetectedTile> boardTiles = new ArrayList<>();
        List<DetectedTile> needReview = new ArrayList<>();

        for (DetectedTile t : detections) {
            if (t.needsManualReview() || !convertible(t)) needReview.add(t);
            if (t.getSource() == DetectedTile.Source.HAND) handTiles.add(t);
            else boardTiles.add(t);
        }

        if (!needReview.isEmpty()) {
            warnings.add(new Warning(Warning.Type.LOW_CONFIDENCE, needReview,
                    "some tiles were detected with low confidence, worth double checking"));
        }

        // Tile needs a unique id for equals()
        int[] nextId = {0};

        // hand is just a bag of tiles, no geometry needed
        Hand hand = new Hand();
        for (DetectedTile t : handTiles) {
            if (convertible(t)) hand.addTile(t.toTile(nextId[0]++));
        }

        List<SetChain> chains = buildChains(boardTiles, imageAspect);
        List<SetChain> validChains = validateAndSplit(chains, warnings);

        Board board = new Board();
        for (SetChain c : validChains) {
            board.addSet(toRummiSet(c, nextId));
        }

        return new DetectionResult (board, hand, warnings);
    }

    private static RummiSet toRummiSet(SetChain chain, int[] nextId) {
        List<Tile> tiles = new ArrayList<>(chain.size());
        for (DetectedTile t : chain.tiles) tiles.add(t.toTile(nextId[0]++));
        return new RummiSet(tiles);
    }

    // toTile() throws unless the tile is a joker or has both number and color
    private static boolean convertible(DetectedTile t) {
        return t.isJoker() || (t.getNumber() != null && t.getColor() != null);
    }

    // geometry -> chains

    private static List<SetChain> buildChains(List<DetectedTile> tiles, double imageAspect) {
        int n = tiles.size();
        List<SetChain> chains = new ArrayList<>();
        if (n == 0) return chains;

        // centers in a corrected space where x and y distances are comparable.
        // x is already in "image width" units, y gets divided by aspect to match
        double[] cx = new double[n];
        double[] cy = new double[n];
        for (int i = 0; i < n; i++) {
            BoundingBox b = tiles.get(i).getBoundingBox();
            cx[i] = b.x + b.width / 2.0;
            cy[i] = (b.y + b.height / 2.0) / imageAspect;
        }

        // every pair close enough to possibly be neighbors in a set
        List<Edge> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double scale = (width(tiles.get(i)) + width(tiles.get(j))) / 2.0;
                double dist = Math.hypot(cx[i] - cx[j], cy[i] - cy[j]);
                if (dist < NEIGHBOR_DISTANCE_FACTOR * scale) {
                    candidates.add(new Edge(i, j, dist / scale));
                }
            }
        }
        // in line 260: Uses Edge.compareTo() to sort by normDist field (closest pairs first)
        Collections.sort(candidates);
        // union-find to block cycles, adjacency capped at 2 per tile
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int[][] adj = new int[n][2]; // slots used = degree[i]
        int[] degree = new int[n];

        for (Edge e : candidates) {
            if (degree[e.a] == 2 || degree[e.b] == 2) continue; // a tile has 2 sides max
            if (find(parent, e.a) == find(parent, e.b)) continue; // would close a loop
            if (bendsTooMuch(e.a, e.b, adj, degree, cx, cy)) continue;
            if (bendsTooMuch(e.b, e.a, adj, degree, cx, cy)) continue;

            adj[e.a][degree[e.a]] = e.b;
            degree[e.a]++;
            adj[e.b][degree[e.b]] = e.a;
            degree[e.b]++;
            union(parent, e.a, e.b);
        }

        // walk each path from an endpoint, collecting tiles in order
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (visited[i] || degree[i] == 2) continue; // mid-chain tiles never start a walk
            List<DetectedTile> chainTiles = new ArrayList<>();
            int prev = -1;
            int cur = i;
            while (cur != -1) {
                visited[cur] = true;
                chainTiles.add(tiles.get(cur));
                int next = -1;
                for (int d = 0; d < degree[cur]; d++) {
                    // Find the neighbor that isn't the previous one (move forward, not backward)
                    if (adj[cur][d] != prev) {
                        next = adj[cur][d]; break; }
                }// Move one step forward, current becomes previous, next becomes current
                prev = cur;
                cur = next;
            }
            chains.add(new SetChain(chainTiles));
        }
        return chains;
    }

    // checks that going from->to keeps the direction the chain already has at 'from'
    private static boolean bendsTooMuch(int from, int to, int[][] adj, int[] degree,
                                        double[] cx, double[] cy) {
        if (degree[from] == 0) return false; // no direction yet, anything goes
        int prev = adj[from][0]; // degree is exactly 1 here, 2 was filtered earlier
        double v1x = cx[from] - cx[prev];
        double v1y = cy[from] - cy[prev];
        double v2x = cx[to] - cx[from];
        double v2y = cy[to] - cy[from];
        return angleBetweenDeg(v1x, v1y, v2x, v2y) > MAX_ANGLE_DEVIATION_DEG;
    }

    private static double angleBetweenDeg(double ax, double ay, double bx, double by) {
        double dot = ax * bx + ay * by;
        double mags = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (mags == 0) return 0; // two boxes on the exact same spot, don't crash
        double cos = Math.max(-1, Math.min(1, dot / mags)); // acos hates 1.0000001
        // acos calculates angle in radians, toDegrees converts to degrees
        return Math.toDegrees(Math.acos(cos));
    }

    private static double width(DetectedTile t) {
        return t.getBoundingBox().width;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]; // path halving, keeps it fast
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static final class Edge implements Comparable<Edge> {
        final int a, b;
        final double normDist; // distance in "tile widths", size independent

        Edge(int a, int b, double normDist) {
            this.a = a;
            this.b = b;
            this.normDist = normDist;
        }

        @Override
        public int compareTo(Edge o) {
            return Double.compare(normDist, o.normDist);
        }
    }

    //  validate chains, split glued sets

    private static List<SetChain> validateAndSplit(List<SetChain> chains, List<Warning> warnings) {
        List<SetChain> valid = new ArrayList<>();
        for (SetChain chain : chains) {
            if (chain.size() < MIN_SET_SIZE) {
                warnings.add(new Warning(Warning.Type.TOO_SMALL, chain.tiles,
                        "found " + chain.size() + " tile(s) that don't belong to any set"));
                continue;
            }
            if (!allConvertible(chain)) {
                warnings.add(new Warning(Warning.Type.INVALID_SET, chain.tiles,
                        "a set contains an unreadable tile"));
                continue;
            }
            if (isValidSet(chain)) {
                valid.add(chain); // legal as-is, don't touch it
                continue;
            }
            // not legal - maybe it's two (or more) sets that got glued together
            List<SetChain> parts = splitRecursive(chain);
            if (parts.size() == 1) {
                warnings.add(new Warning(Warning.Type.INVALID_SET, chain.tiles,
                        "these tiles don't form a legal set"));
                continue;
            }
            warnings.add(new Warning(Warning.Type.SPLIT_APPLIED, chain.tiles,
                    "two sets were touching, split them apart"));
            valid.addAll(parts);
        }
        return valid;
    }

    // tries to break an invalid chain into legal sets.
    // a chain that is already legal is returned untouched, so valid sets never get split
    private static List<SetChain> splitRecursive(SetChain chain) {
        List<SetChain> noSplit = new ArrayList<>();
        noSplit.add(chain);
        // If chain is already valid or too small to split, return it unchanged
        if (isValidSet(chain)) return noSplit;
        if (chain.size() < MIN_SPLITTABLE_SIZE) return noSplit;

        // one clean cut into two legal sets (the common case, cheap)
        for (int cut = MIN_SET_SIZE; cut <= chain.size() - MIN_SET_SIZE; cut++) {
            SetChain left = chain.sub(0, cut);
            SetChain right = chain.sub(cut, chain.size());
            if (isValidSet(left) && isValidSet(right)) {
                List<SetChain> splitResult = new ArrayList<>();
                splitResult.add(left);
                splitResult.add(right);
                return splitResult;
            }
        }

        // maybe 3+ sets glued together, recurse on both halves
        for (int cut = MIN_SET_SIZE; cut <= chain.size() - MIN_SET_SIZE; cut++) {
            List<SetChain> leftParts = splitRecursive(chain.sub(0, cut));
            List<SetChain> rightParts = splitRecursive(chain.sub(cut, chain.size()));
            if (allValid(leftParts) && allValid(rightParts)) {
                List<SetChain> splitResult = new ArrayList<>(leftParts);
                splitResult.addAll(rightParts);
                return splitResult;
            }
        }

        return noSplit; // couldn't fix it, the user will have to
    }

    private static boolean allValid(List<SetChain> parts) {
        for (SetChain p : parts) {
            if (!isValidSet(p)) return false;
        }
        return true;
    }

    private static boolean allConvertible(SetChain chain) {
        for (DetectedTile t : chain.tiles) {
            if (!convertible(t)) return false;
        }
        return true;
    }

    // reuses the existing game rules. RummiSet.isValid() sorts internally, so we don't care
    // about direction here' just whether these tiles can legally form a group or run.
    // ids are throwaway (validity ignores them); real ids get assigned later in toRummiSet.
    private static boolean isValidSet(SetChain chain) {
        List<Tile> tiles = new ArrayList<>(chain.size());
        int fakeId = 0;
        for (DetectedTile t : chain.tiles) tiles.add(t.toTile(fakeId++));
        return new RummiSet(tiles).isValid();
    }
}
