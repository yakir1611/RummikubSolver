const express = require('express');
const { register, login } = require('../controllers/authController');

const router = express.Router();

// POST /api/auth/register  { username, password } -> { token, userId, username }
router.post('/register', register);

// POST /api/auth/login     { username, password } -> { token, userId, username }
router.post('/login', login);

module.exports = router;
