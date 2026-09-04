// Defines the shape of one saved history entry in MongoDB: which user it
// belongs to, when it was saved, how many tiles were played, a display name
// (defaults to a date+time string, renameable), and the full suggested
// solution for that turn.
const mongoose = require('mongoose');

// Tile code format (must match Android's TileCodeFormat exactly):
// a numbered tile is <ColorLetter><Value>, where ColorLetter is one of
// R=Red, B=Blue, K=Black, Y=Yellow (K for Black, since B is already taken by
// Blue), and Value is 1-13, e.g. "R5", "K12". A joker is exactly "JOKER".
const historyEntrySchema = new mongoose.Schema({
    userId: {
        type: mongoose.Schema.Types.ObjectId,
        ref: 'User',
        required: true,
        index: true, // every read is "give me this user's history", so this
                     // is the field we filter on - index it
    },
    timestamp: {
        type: Date,
        default: Date.now,
    },
    tilesPlayed: {
        type: Number,
        required: true,
        min: 0,
    },
    name: {
        type: String, // defaults to a date+time string at save time (see
                       // SolutionActivity); renameable later via PATCH /api/history/:id
        required: false,
    },
    boardImage: {
        // base64-encoded, compressed JPEG of the photographed board - no
        // longer written by the app (SolutionActivity dropped the photo in
        // favor of the tile-rendered sections), kept only so entries saved
        // before that change still display theirs
        type: String,
        required: false,
    },
    // The four sections the Solution screen shows (see SolutionActivity /
    // BoardRenderer) - saved verbatim in the same tile-code format so
    // HistoryDetailActivity can reconstruct and render exactly what was
    // shown at save time, no re-solving involved.
    boardBefore: {
        type: [[String]], // the board going into this solve: an array of
                           // sets, each set an array of tile codes, e.g.
                           // [["R5","R6","R7"], ["B1","B2","JOKER"]]
        required: false,
    },
    handBefore: {
        type: [String], // the full hand going into this solve, flat array of tile codes
        required: false,
    },
    boardAfter: {
        type: [[String]], // the solver's resulting board, same shape as boardBefore
        required: false,
    },
    handRemaining: {
        type: [String], // hand tiles left after the move, flat array of tile codes
        required: false,
    },
});

module.exports = mongoose.model('HistoryEntry', historyEntrySchema);
