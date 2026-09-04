// Save/read a user's turn history. Both routes run after requireAuth, so
// req.userId is always set by the time we get here - no need to re-check it.
const HistoryEntry = require('../models/HistoryEntry');

const MAX_ENTRIES = 50; 
// POST /history - saves one turn (how many tiles were played, a display
// name, and all four sections the Solution screen showed: board/hand,
// before/after) for the currently logged-in user.
async function saveEntry(req, res) {
    const { name, tilesPlayed, boardImage, boardBefore, handBefore, boardAfter, handRemaining } = req.body;

    if (typeof tilesPlayed !== 'number' || tilesPlayed < 0) {
        return res.status(400).json({ error: 'tilesPlayed חייב להיות מספר לא-שלילי' });
    }

    try {
        const entry = await HistoryEntry.create({
            userId: req.userId,
            name,
            tilesPlayed,
            boardImage, // undefined is fine - the schema fields are optional
            boardBefore,
            handBefore,
            boardAfter,
            handRemaining,
        });
        return res.status(201).json(entry);
    } catch (err) {
        console.error('[history] save failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בשמירת היסטוריה' });
    }
}
// PATCH /history/:id  { name } - renames an existing entry. Scoped to the
// logged-in user the same way GET/POST are (filtering by req.userId), so one
// user can never rename (or even discover the existence of) another's entry.
async function renameEntry(req, res) {
    const { name } = req.body;

    if (typeof name !== 'string' || !name.trim()) {
        return res.status(400).json({ error: 'שם לא יכול להיות ריק' });
    }

    try {
        const entry = await HistoryEntry.findOneAndUpdate(
            { _id: req.params.id, userId: req.userId },
            { name: name.trim() },
            { new: true } // return the updated document, not the pre-update one
        );
        if (!entry) {
            return res.status(404).json({ error: 'הרשומה לא נמצאה' });
        }
        return res.json(entry);
    } catch (err) {
        console.error('[history] rename failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בשינוי השם' });
    }
}
// GET /history - returns this user's last 50 saved turns, newest first.
async function getHistory(req, res) {
    try {
        const entries = await HistoryEntry.find({ userId: req.userId })
            .sort({ timestamp: -1 }) // newest first, matches HistoryStore.load()
            .limit(MAX_ENTRIES);
        return res.json(entries);
    } catch (err) {
        console.error('[history] fetch failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בטעינת היסטוריה' });
    }
}

module.exports = { saveEntry, getHistory, renameEntry };
