package com.example.rummikubsolver.vision;
import com.example.rummikubsolver.Board;
import com.example.rummikubsolver.Hand;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * takes the list of detected tiles and builds the actual game state
 * hand tiles go straight into the Hand and board tiles get clustered into sets by geometry
 * the board has to be valid on its own so if what we rebuilt isnt made only of legal sets
 * thats a detection error and we flag it its the users job to fix the sets we read off the table
 */
public final class BoardAssembler {
    // tuned around real tile sizes 26.5x36.5mm ratio about 1.38
    // neighbors in a set sit about 1.0 to 1.1 widths apart and the next row is 1.38 or more away
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
            this.warnings = warnings;
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
            this.tiles = new ArrayList<>(tiles);
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

        // copy not a subList view because the recursion slices a lot
        SetChain sub(int from, int to) {
            return new SetChain(new ArrayList<>(tiles.subList(from, to)));
        }
    }

    // entry point
    /**
     * imageAspect is the width divided by the height of the photo like bitmap.getWidth
     * divided by bitmap.getHeight
     * coordinates are normalized 0-1 but on portrait images the height is bigger than the width
     * without fixing this the vertical distances look smaller than they really are
     * so we divide y by imageAspect to compare distances fairly on both axes
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

    /**
     * takes the board tiles and groups them into chains a chain is one set of tiles that
     * sit next to each other it works by geometry not by row so it also handles diagonal sets
     * first it finds every pair of tiles close enough to maybe be neighbors
     * then it connects the closest pairs first with union find while blocking loops
     * capping each tile at 2 neighbors and not letting the chain bend too much
     * at the end it walks each chain from an endpoint to get the tiles in order
     */
    private static List<SetChain> buildChains(List<DetectedTile> tiles, double imageAspect) {
        int n = tiles.size();
        List<SetChain> chains = new ArrayList<>();
        if (n == 0) return chains;

        // find the center of each tile in a fixed space where x and y distances are comparable
        // x is already in image width units and y gets divided by aspect to match
        double[] cx = new double[n];
        double[] cy = new double[n];
        for (int i = 0; i < n; i++) {
            BoundingBox b = tiles.get(i).getBoundingBox();
            cx[i] = b.x + b.width / 2.0;
            cy[i] = (b.y + b.height / 2.0) / imageAspect;
        }

        // collect every pair of tiles thats close enough to maybe be neighbors in a set
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

        // sort by the normDist field using Edge.compareTo so the closest pairs come first
        Collections.sort(candidates);

            // union find to block loops and each tile can have at most 2 neighbors
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            int[][] adj = new int[n][2];   // the neighbors of each tile slots used = degree[i]
            int[] degree = new int[n];     // how many neighbors each tile has so far

            for (Edge e : candidates) {
                if (degree[e.a] == 2 || degree[e.b] == 2) continue;      // a tile has 2 sides max
                // they are already in the same chain this would close a loop
                if (find(parent, e.a) == find(parent, e.b)) continue;
                if (bendsTooMuch(e.a, e.b, adj, degree, cx, cy)) continue;
                if (bendsTooMuch(e.b, e.a, adj, degree, cx, cy)) continue;

            // connect the two tiles as neighbors of each other
            adj[e.a][degree[e.a]] = e.b;
            degree[e.a]++;
            adj[e.b][degree[e.b]] = e.a;
            degree[e.b]++;
            union(parent, e.a, e.b);
        }

        // walk each chain from an endpoint and collect its tiles in order
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            // an endpoint has degree 0 or 1 a tile with degree 2 is in the middle
            // so it never starts a walk
            if (visited[i] || degree[i] == 2) continue;
            List<DetectedTile> chainTiles = new ArrayList<>();
            int prev = -1;
            int cur = i;
            while (cur != -1) {
                visited[cur] = true;
                chainTiles.add(tiles.get(cur));
                // find the neighbor that isnt the one we came from so we keep moving forward
                int next = -1;
                for (int d = 0; d < degree[cur]; d++) {
                    if (adj[cur][d] != prev) {
                        next = adj[cur][d];
                        break;
                    }
                }
                // step forward the current tile becomes the previous and next becomes current
                prev = cur;
                cur = next;
            }
            chains.add(new SetChain(chainTiles));
        }
        return chains;
    }

    // checks that adding to keeps the direction the chain already has at from
    private static boolean bendsTooMuch(int from, int to, int[][] adj, int[] degree,
                                        double[] cx, double[] cy) {
        // no neighbor yet so there is no direction to keep anything goes
        if (degree[from] == 0) return false;
        // degree is exactly 1 here , 2 was already filtered out at buildChains
        int prev = adj[from][0];
        // vector v1 from the old neighbor to 'from' this is the direction we already go
        double v1x = cx[from] - cx[prev];
        double v1y = cy[from] - cy[prev];
        // vector v2 from 'from' to the new tile this is the direction we want to add
        double v2x = cx[to] - cx[from];
        double v2y = cy[to] - cy[from];
        // if the angle between the two directions is too big its bending too much
        return angleBetweenDeg(v1x, v1y, v2x, v2y) > MAX_ANGLE_DEVIATION_DEG;
    }

    /**
     * gives the angle in degrees between two vectors a and b
     * the dot product (a . b) has two forms that give the same number
     * algebraic a . b = ax*bx + ay*by
     * geometric a . b = |a| * |b| * cos(alpha)
     * so cos(alpha) = dot / (|a| * |b|)
     * dot is ax*bx + ay*by it says how much they point the same way
     * lengths is exactly |a| * |b| the two vector lengths multiplied
     * we divide by it to get cos then acos gives the angle
     */
    private static double angleBetweenDeg(double ax, double ay, double bx, double by) {
        double dot = ax * bx + ay * by;
        double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (lengths == 0) return 0;   // two boxes on the exact same spot dont crash
        double cos = dot / lengths;        // cos from the formula
        cos = Math.min(1, cos);            // dont let it go above 1
        cos = Math.max(-1, cos);           // dont let it go below -1
        // acos gives radians toDegrees turns it into degrees
        return Math.toDegrees(Math.acos(cos)); }

    private static double width(DetectedTile t) {
        return t.getBoundingBox().width;
    }

    // finds the group representative of x by climbing up the parents until a tile is its own parent
    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]; // path halving, keeps it fast
            x = parent[x];
        }
        return x;
    }

    // joins two groups the representative of b becomes the parent of a representative
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

    // goes over every chain and keeps only the legal sets splitting or flagging the rest
    private static List<SetChain> validateAndSplit(List<SetChain> chains, List<Warning> warnings) {
        List<SetChain> valid = new ArrayList<>();
        for (SetChain chain : chains) {
            if (chain.size() < MIN_SET_SIZE) {
                warnings.add(new Warning(Warning.Type.TOO_SMALL, chain.tiles,
                        "found " + chain.size() + " tile(s) that don't belong to any set"));
                continue;
            }
            // has a tile we cant read so we cant check if its legal
            if (!allConvertible(chain)) {
                warnings.add(new Warning(Warning.Type.INVALID_SET, chain.tiles,
                        "a set contains an unreadable tile"));
                continue;
            }  // already a legal set keep it as is
            if (isValidSet(chain)) {
                valid.add(chain);
                continue;
            }
            // not legal so maybe it's two (or more) sets that got glued together
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

    // takes a chain that is not a legal set and tries to cut it into smaller sets that are legal
    // if the chain is already a legal set we give it back the same so good sets never get cut
    private static List<SetChain> splitRecursive(SetChain chain) {
        List<SetChain> noSplit = new ArrayList<>();
        noSplit.add(chain);
        // stop early if the chain is already good or too short to cut into two sets of 3
        if (isValidSet(chain)) return noSplit;
        if (chain.size() < MIN_SPLITTABLE_SIZE) return noSplit;

        // try one cut that makes a legal set on the left and a legal set on the right
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

        // if one cut was not enough maybe there are more sets stuck together so cut and try again on each side
        for (int cut = MIN_SET_SIZE; cut <= chain.size() - MIN_SET_SIZE; cut++) {
            List<SetChain> leftParts = splitRecursive(chain.sub(0, cut));
            List<SetChain> rightParts = splitRecursive(chain.sub(cut, chain.size()));
            if (allValid(leftParts) && allValid(rightParts)) {
                List<SetChain> splitResult = new ArrayList<>(leftParts);
                splitResult.addAll(rightParts);
                return splitResult;
            }
        }

        return noSplit;   // we could not make legal sets out of it so the user will fix it
    }

    // returns true when every part in the list is a legal set
    private static boolean allValid(List<SetChain> parts) {
        for (SetChain p : parts) {
            if (!isValidSet(p)) return false;
        }
        return true;
    }

    // returns true when every tile in the chain can become a real Tile
    private static boolean allConvertible(SetChain chain) {
        for (DetectedTile t : chain.tiles) {
            if (!convertible(t)) return false;
        }
        return true;
    }

    // asks the game rules if these tiles make a legal set
    // RummiSet.isValid sorts the tiles itself so the order we pass does not matter
    // the ids here are fake because isValid ignores them the real ids are set later in toRummiSet
    private static boolean isValidSet(SetChain chain) {
        List<Tile> tiles = new ArrayList<>(chain.size());
        int fakeId = 0;
        for (DetectedTile t : chain.tiles) tiles.add(t.toTile(fakeId++));
        return new RummiSet(tiles).isValid();
    }
}
