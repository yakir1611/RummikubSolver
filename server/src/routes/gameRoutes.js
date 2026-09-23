const express = require('express');
const { createGame, listGames, renameGame, getGameHistory } = require('../controllers/gameController');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

// All routes need a valid token - requireAuth runs first and sets
// req.userId, or 401 before the controller ever runs.

// POST /api/games  { name } -> the created game
router.post('/', requireAuth, createGame);

// GET /api/games -> this user's games, newest first
router.get('/', requireAuth, listGames);

// PATCH /api/games/:id  { name } -> the renamed game
router.patch('/:id', requireAuth, renameGame);

// GET /api/games/:id/history -> the turns saved inside this game, newest first
router.get('/:id/history', requireAuth, getGameHistory);

module.exports = router;
