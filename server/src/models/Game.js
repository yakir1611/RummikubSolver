// Defines one "game": a container that groups turns (HistoryEntry) together.
// A game is opened once (HomeActivity's "New game" button) and every turn
// played afterward, until the user goes back home, belongs to it - see
// HistoryEntry.gameId.
const mongoose = require('mongoose');

const gameSchema = new mongoose.Schema({
    userId: {
        type: mongoose.Schema.Types.ObjectId,
        ref: 'User',
        required: true,
        index: true, // every read is "give me this user's games" - same
                     // reasoning as HistoryEntry.userId
    },
    name: {
        type: String, // defaults to a date+time string at creation time (see
                       // HomeActivity); renameable later via PATCH /api/games/:id
        required: false,
    },
    timestamp: {
        type: Date,
        default: Date.now,
    },
});

module.exports = mongoose.model('Game', gameSchema);
