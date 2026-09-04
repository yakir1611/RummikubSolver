package com.example.rummikubsolver.ui;

import android.graphics.Bitmap;

import com.example.rummikubsolver.Board;
import com.example.rummikubsolver.Hand;
import com.example.rummikubsolver.RummiSet;
import com.example.rummikubsolver.Tile;
import com.example.rummikubsolver.vision.BoardAssembler;
import com.example.rummikubsolver.vision.BoundingBox;
import com.example.rummikubsolver.vision.DetectedTile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Holds everything about the turn the user is currently working on.
 *
 * Why a singleton and not Intent extras: we pass bitmaps and long lists of
 * DetectedTile between screens. Intents have a ~1MB limit and would crash
 * (TransactionTooLargeException) on a full-res photo, and DetectedTile is
 * mutable - the review screen edits the same objects the assembler will read.
 *
 * Lives only while the app is in memory. If Android kills the process
 * mid-turn the session is gone, and CaptureActivity starts fresh - which is
 * the right behaviour anyway, the photos are gone too.
 */
public final class TurnSession {

    private static TurnSession instance;

    private Bitmap boardPhoto;
    private Bitmap handPhoto;

    // the tiles as detected, edited in place by the review screen
    private final List<DetectedTile> detections = new ArrayList<>();

    // aspect (w/h) of the BOARD photo - BoardAssembler needs it to compare
    // vertical and horizontal distances fairly
    private double boardAspect = 1.0;

    private BoardAssembler.DetectionResult lastResult;

    // true once applyInitialBoardGrouping() has stamped boardSetIndex for this
    // turn - guards against a second geometric run clobbering manual edits
    private boolean initialBoardGroupingDone = false;

    private String username = "";

    // set by LoginActivity after a successful register/login call, cleared
    // on logout. Every history request needs this - it's how the server
    // knows which user is asking (see AppApiClient, requireAuth on the
    // server side). Not persisted to disk: same lifetime as username above,
    // so a killed process means logging in again, which matches how the
    // rest of the session already behaves.
    private String authToken;
    private String userId;

    // set by HistoryActivity right before opening HistoryDetailActivity - same
    // non-persisted, Intent-too-large-avoidance pattern as boardPhoto above.
    // Cleared on logout so a later, different account can't see a stale entry.
    private String historyBoardImage;
    private List<List<String>> historyBoardBefore;
    private List<String> historyHandBefore;
    private List<List<String>> historyBoardAfter;
    private List<String> historyHandRemaining;

    private TurnSession() {}

    public static synchronized TurnSession get() {
        if (instance == null) instance = new TurnSession();
        return instance;
    }

    /** Wipes the previous turn. Called when the user starts a new turn. */
    public void startNewTurn() {
        recycleIfNeeded(boardPhoto);
        recycleIfNeeded(handPhoto);
        boardPhoto = null;
        handPhoto = null;
        detections.clear();
        lastResult = null;
        boardAspect = 1.0;
        initialBoardGroupingDone = false;
    }

    private void recycleIfNeeded(Bitmap b) {
        // don't recycle - a view might still be drawing it. let GC handle it.
        // kept as a hook in case we move to explicit bitmap pooling later.
    }

    public Bitmap getBoardPhoto() { return boardPhoto; }

    public void setBoardPhoto(Bitmap bmp) {
        this.boardPhoto = bmp;
        if (bmp != null && bmp.getHeight() > 0) {
            this.boardAspect = bmp.getWidth() / (double) bmp.getHeight();
        }
    }

    public Bitmap getHandPhoto() { return handPhoto; }
    public void setHandPhoto(Bitmap bmp) { this.handPhoto = bmp; }

    public double getBoardAspect() { return boardAspect; }

    public List<DetectedTile> getDetections() { return detections; }

    public void setDetections(List<DetectedTile> tiles) {
        detections.clear();
        if (tiles != null) detections.addAll(tiles);
    }

    public void addDetections(List<DetectedTile> tiles) {
        if (tiles != null) detections.addAll(tiles);
    }

    public BoardAssembler.DetectionResult getLastResult() { return lastResult; }
    public void setLastResult(BoardAssembler.DetectionResult r) { this.lastResult = r; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAuthToken() { return authToken; }
    public String getUserId() { return userId; }

    /** Called once, right after register()/login() succeeds. */
    public void setSession(String username, String authToken, String userId) {
        this.username = username;
        this.authToken = authToken;
        this.userId = userId;
    }

    public boolean isLoggedIn() {
        return authToken != null;
    }

    public void logout() {
        username = "";
        authToken = null;
        userId = null;
        historyBoardImage = null;
        historyBoardBefore = null;
        historyHandBefore = null;
        historyBoardAfter = null;
        historyHandRemaining = null;
    }

    public String getHistoryBoardImage() { return historyBoardImage; }
    public void setHistoryBoardImage(String boardImage) { this.historyBoardImage = boardImage; }

    public List<List<String>> getHistoryBoardBefore() { return historyBoardBefore; }
    public void setHistoryBoardBefore(List<List<String>> boardBefore) { this.historyBoardBefore = boardBefore; }

    public List<String> getHistoryHandBefore() { return historyHandBefore; }
    public void setHistoryHandBefore(List<String> handBefore) { this.historyHandBefore = handBefore; }

    public List<List<String>> getHistoryBoardAfter() { return historyBoardAfter; }
    public void setHistoryBoardAfter(List<List<String>> boardAfter) { this.historyBoardAfter = boardAfter; }

    public List<String> getHistoryHandRemaining() { return historyHandRemaining; }
    public void setHistoryHandRemaining(List<String> handRemaining) { this.historyHandRemaining = handRemaining; }

    /** Re-runs the assembler over the (possibly edited) detections. */
    public BoardAssembler.DetectionResult reassemble() {
        lastResult = BoardAssembler.assemble(detections, boardAspect);
        return lastResult;
    }

    /**
     * Runs BoardAssembler exactly once per turn and bakes its grouping into
     * each BOARD DetectedTile's boardSetIndex. After this, set membership
     * lives on the tiles themselves - nobody should call BoardAssembler
     * again for this turn (buildCurrentGameState() below rebuilds from the
     * stamped indexes instead, no geometry involved).
     */
    public void applyInitialBoardGrouping() {
        if (initialBoardGroupingDone) return;
        initialBoardGroupingDone = true;

        BoardAssembler.DetectionResult result = BoardAssembler.assemble(detections, boardAspect);
        lastResult = result;

        Set<DetectedTile> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RummiSet> sets = result.board.getSets();
        for (int i = 0; i < sets.size(); i++) {
            for (Tile t : sets.get(i).getTiles()) {
                DetectedTile match = findUnconsumedMatch(t, consumed);
                if (match != null) {
                    match.setBoardSetIndex(i);
                    consumed.add(match);
                }
            }
        }
        // everything else (TOO_SMALL/INVALID_SET/unconvertible chains, never
        // matched above) keeps the boardSetIndex it started with: null
    }

    /**
     * Matches an assembler-output Tile back to the DetectedTile it came
     * from, by value+color+joker - tracks already-matched detections so
     * duplicate tiles (e.g. two blue 5s in different sets) don't all
     * collapse onto the same first match.
     */
    private DetectedTile findUnconsumedMatch(Tile t, Set<DetectedTile> consumed) {
        for (DetectedTile d : detections) {
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            if (consumed.contains(d)) continue;
            if (t.isJoker() && d.isJoker()) return d;
            if (t.isJoker() != d.isJoker()) continue;
            if (d.getNumber() != null && d.getNumber() == t.getValue() && d.getColor() == t.getColor()) {
                return d;
            }
        }
        return null;
    }

    /**
     * Distinct board-set indices currently in use, ascending. Used by the
     * tile editor's set picker - it lists sets by position in this list
     * (matching how ReviewActivity labels its blocks), not by raw index.
     */
    public List<Integer> getUsedBoardSetIndices() {
        TreeSet<Integer> indices = new TreeSet<>();
        for (DetectedTile d : detections) {
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            Integer idx = d.getBoardSetIndex();
            if (idx != null) indices.add(idx);
        }
        return new ArrayList<>(indices);
    }

    /**
     * Creates a brand-new BOARD tile with placeholder defaults (1, black) and
     * a fresh set index (max existing + 1, or 0 if there are none yet), adds
     * it to detections, and returns it so the caller can open the editor on
     * it right away. Not user-verified yet - the caller is expected to
     * immediately let the user confirm/correct it, same as any CV detection.
     */
    public DetectedTile addManualBoardTile() {
        List<Integer> used = getUsedBoardSetIndices();
        int newIndex = used.isEmpty() ? 0 : Collections.max(used) + 1;
        String id = "manual-" + detections.size() + "-" + System.nanoTime();

        DetectedTile tile = new DetectedTile(id, 1, Tile.Color.BLACK, false, 1.0f,
                new BoundingBox(0f, 0f, 0f, 0f), DetectedTile.Source.BOARD);
        tile.setBoardSetIndex(newIndex);
        detections.add(tile);
        return tile;
    }

    /** Board + Hand built from the current state - no geometry re-run. */
    public static final class GameState {
        public final Board board;
        public final Hand hand;

        GameState(Board board, Hand hand) {
            this.board = board;
            this.hand = hand;
        }
    }

    /**
     * Builds a fresh Board+Hand straight from the current boardSetIndex
     * groupings, in ascending index order, so it matches what the review
     * screen showed. No BoardAssembler involved. A group containing a tile
     * that isn't convertible (shouldn't normally happen, but toTile() would
     * throw on it) is dropped instead of crashing - the review screen's
     * Solve button should already be disabled in that case anyway.
     */
    public GameState buildCurrentGameState() {
        int[] nextId = {0};

        Hand hand = new Hand();
        for (DetectedTile d : detections) {
            if (d.getSource() != DetectedTile.Source.HAND) continue;
            if (!convertible(d)) continue;
            hand.addTile(d.toTile(nextId[0]++));
        }

        // TreeMap keeps sets in ascending boardSetIndex order - matches what
        // the review screen showed
        Map<Integer, List<DetectedTile>> groups = new TreeMap<>();
        for (DetectedTile d : detections) {
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            Integer index = d.getBoardSetIndex();
            if (index == null) continue; // unassigned tiles don't go to the solver
            groups.computeIfAbsent(index, k -> new ArrayList<>()).add(d);
        }

        Board board = new Board();
        for (List<DetectedTile> group : groups.values()) {
            if (!allConvertible(group)) continue; // guard - toTile() would throw otherwise
            List<Tile> tiles = new ArrayList<>(group.size());
            for (DetectedTile d : group) tiles.add(d.toTile(nextId[0]++));
            RummiSet set = new RummiSet(tiles);
            if (set.isValid()) board.addSet(set);
        }

        return new GameState(board, hand);
    }

    private static boolean convertible(DetectedTile d) {
        return d.isJoker() || (d.getNumber() != null && d.getColor() != null);
    }

    private static boolean allConvertible(List<DetectedTile> group) {
        for (DetectedTile d : group) {
            if (!convertible(d)) return false;
        }
        return true;
    }
}
