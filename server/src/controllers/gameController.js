// Create/list/rename games, and list the turns saved inside one game. Same
// requireAuth pattern as historyController - req.userId is always set here.
const Game = require('../models/Game');
const HistoryEntry = require('../models/HistoryEntry');

const MAX_GAMES = 50;
const MAX_ENTRIES = 50; // mirrors historyController's cap, per game

// POST /games  { name } -> creates a new game for the logged-in user.
async function createGame(req, res) {
    const { name } = req.body;

    try {
        const game = await Game.create({
            userId: req.userId,
            name, // undefined is fine - the schema field is optional
        });
        return res.status(201).json(game);
    } catch (err) {
        console.error('[game] create failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת ביצירת משחק' });
    }
}
// GET /games -> this user's games, newest first.
async function listGames(req, res) {
    try {
        const games = await Game.find({ userId: req.userId })
            .sort({ timestamp: -1 })
            .limit(MAX_GAMES);
        return res.json(games);
    } catch (err) {
        console.error('[game] list failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בטעינת משחקים' });
    }
}
// PATCH /games/:id  { name } -> renames a game. Scoped to the logged-in user
// the same way historyController.renameEntry is.
async function renameGame(req, res) {
    const { name } = req.body;

    if (typeof name !== 'string' || !name.trim()) {
        return res.status(400).json({ error: 'שם לא יכול להיות ריק' });
    }

    try {
        const game = await Game.findOneAndUpdate(
            { _id: req.params.id, userId: req.userId },
            { name: name.trim() },
            { new: true }
        );
        if (!game) {
            return res.status(404).json({ error: 'המשחק לא נמצא' });
        }
        return res.json(game);
    } catch (err) {
        console.error('[game] rename failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בשינוי השם' });
    }
}
// GET /games/:id/history -> the turns saved inside this game, newest first.
// Filtering by both userId and gameId (not "does this game belong to me,
// then fetch") keeps this scoped the exact same way getHistory/renameEntry
// already are - a client can't read another user's turns just by guessing a
// gameId, since no entry of theirs will ever match that filter.
async function getGameHistory(req, res) {
    try {
        const entries = await HistoryEntry.find({ userId: req.userId, gameId: req.params.id })
            .sort({ timestamp: -1 })
            .limit(MAX_ENTRIES);
        return res.json(entries);
    } catch (err) {
        console.error('[game] fetch history failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בטעינת תורים' });
    }
}

module.exports = { createGame, listGames, renameGame, getGameHistory };
