const express = require('express');
const { saveEntry, getHistory, renameEntry } = require('../controllers/historyController');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

// All three routes need a valid token - requireAuth runs first and sets
// req.userId, or 401 before the controller ever runs.

// POST /api/history  { name, tilesPlayed, boardBefore, handBefore, boardAfter, handRemaining } -> the saved entry
router.post('/', requireAuth, saveEntry);

// GET /api/history -> this user's entries, newest first
router.get('/', requireAuth, getHistory);

// PATCH /api/history/:id  { name } -> the renamed entry
router.patch('/:id', requireAuth, renameEntry);

module.exports = router;
