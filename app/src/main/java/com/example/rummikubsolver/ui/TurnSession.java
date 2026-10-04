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
 * App-wide singleton holding all mutable state for the turn currently in progress
 * (photos, raw tile detections, game/user identity, history-view snapshot).
 * It exists so screens can share this data without passing it through Intents,
 * which have a strict size limit that a full-res photo or a long tile list would blow past.
 *
 * Its two main jobs: (1) run the one-time geometric grouping pass (applyInitialBoardGrouping)
 * that stamps each board DetectedTile with the set index it belongs to,
 * matching BoardAssembler's freshly-built Tile objects back to the original detections via findUnconsumedMatch;
 * and (2) rebuild a solver-ready Board+Hand (buildCurrentGameState) directly from those stamped indices,
 * with no further geometry involved — so later screens always reflect whatever the user has since edited.
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
    // Flag starts false - no grouping pass has run yet for this turn.
    private boolean initialBoardGroupingDone = false;

    // the game the current turn belongs to. Games are created lazily: HomeActivity's
    // "new game" only remembers pendingGameName here; the game itself is only
    // actually created server-side (and currentGameId set - see setCurrentGameId())
    // by SolutionActivity when the FIRST turn is actually saved. That way a user who
    // backs out before finishing a turn never leaves an empty game behind.

    // Name chosen for a not-yet-created game, pending its first saved turn.
    private String pendingGameName;
    // Server-assigned id of the current game, once it actually exists.
    private String currentGameId;

    // how many turns have been successfully saved in the current game so far.
    private int turnCount = 0;
    // Display name of the currently logged-in user, empty when logged out.
    private String username = "";

    // set after a successful register/login call, cleared
    // on logout. Every history request needs this - it's how the server
    // knows which user is asking (see AppApiClient, requireAuth on the
    // server side).
    // JWT/session token proving who the current user is.
    private String authToken;
    private String userId;
    // Base64 (or similar) encoded board photo for the history entry being viewed.
    private String historyBoardImage;
    // Board sets ("before" state) of the history turn being viewed, as tile-code rows.
    private List<List<String>> historyBoardBefore;
    // Hand tiles ("before" state) of the history turn being viewed, as tile codes.
    private List<String> historyHandBefore;
    // Board sets ("after" state) of the history turn being viewed, as tile-code rows.
    private List<List<String>> historyBoardAfter;
    // Hand tiles remaining ("after" state) of the history turn being viewed.
    private List<String> historyHandRemaining;

    private TurnSession() {}

    public static synchronized TurnSession get() {
        if (instance == null) instance = new TurnSession();
        return instance;
    }

    /**
     * Starts a brand-new game: remembers the default name to create it with
     * once there's actually something to save (see setCurrentGameId()), resets
     * the turn counter, and wipes any previous turn state. Every startNewTurn()
     * after this, until the next startNewGame(), keeps accumulating under the
     * same game.
     */
    public void startNewGame(String defaultName) {
        this.pendingGameName = defaultName;
        this.currentGameId = null;
        this.turnCount = 0;
        startNewTurn();
    }

    public String getPendingGameName() { return pendingGameName; }

    public String getCurrentGameId() { return currentGameId; }

    // Called once, right after the first turn's save actually creates the game server-side.
    public void setCurrentGameId(String gameId) { this.currentGameId = gameId; }

    // The number to use for the NEXT turn's default name - see solution_turn_default_name.
    public int getNextTurnNumber() { return turnCount + 1; }

    // Called once a turn is actually saved successfully, so the next one gets the next number.
    public void incrementTurnCount() { turnCount++; }

    /**
     * Resumes an already-existing game (HomeActivity's "המשך משחק אחרון"):
     * sets currentGameId directly and seeds the
     * turn counter with how many turns it already has, so the next one saved
     * continues the numbering ("תור מספר N+1") instead of restarting at 1.
     */
    public void resumeGame(String gameId, int existingTurnCount) {
        this.pendingGameName = null;
        this.currentGameId = gameId;
        this.turnCount = existingTurnCount;
        startNewTurn();
    }

    /** Wipes the previous turn. Called when the user starts a new turn.
     *  Does NOT touch currentGameId - a new turn within the same game keeps it. */
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

    // Returns the live, mutable list of all detections for this turn.
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
    // Clears all per-user and per-game state on logout.
    public void logout() {
        username = "";
        authToken = null;
        userId = null;
        pendingGameName = null;
        currentGameId = null;
        turnCount = 0;
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

    /**
     * Creates a brand-new HAND tile with placeholder defaults (1, black), adds it
     * to detections, and returns it so the caller can open the editor on it right
     * away. Hand tiles have no board-set concept, so no boardSetIndex is stamped.
     * Not user-verified yet - same contract as addManualBoardTile().
     */
    public DetectedTile addManualHandTile() {
        String id = "manual-hand-" + detections.size() + "-" + System.nanoTime();
        DetectedTile tile = new DetectedTile(id, 1, Tile.Color.BLACK, false, 1.0f,
                new BoundingBox(0f, 0f, 0f, 0f), DetectedTile.Source.HAND);
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
        // Groups board detections by their assigned set index, sorted ascending.
        Map<Integer, List<DetectedTile>> groups = new TreeMap<>();
        for (DetectedTile d : detections) {
            if (d.getSource() != DetectedTile.Source.BOARD) continue;
            Integer index = d.getBoardSetIndex();
            if (index == null) continue; // unassigned tiles don't go to the solver
            groups.computeIfAbsent(index, k -> new ArrayList<>()).add(d);
        }
        // Fresh, empty board to populate from the grouped board detections.
        Board board = new Board();
        for (List<DetectedTile> group : groups.values()) {
            if (!allConvertible(group)) continue; // guard - toTile() would throw otherwise
            List<Tile> tiles = new ArrayList<>(group.size());
            for (DetectedTile d : group) tiles.add(d.toTile(nextId[0]++));
            RummiSet set = new RummiSet(tiles);
            if (set.isValid()) board.addSet(set);
        }
        // Return the fully reconstructed board+hand pair.
        return new GameState(board, hand);
    }
    // Returns true if a detection has enough info to become a real domain Tile.
    private static boolean convertible(DetectedTile d) {
        return d.isJoker() || (d.getNumber() != null && d.getColor() != null);
    }
    // Returns true only if every detection in the group is individually convertible.
    private static boolean allConvertible(List<DetectedTile> group) {
        for (DetectedTile d : group) {
            if (!convertible(d)) return false;
        }
        return true;
    }
}
