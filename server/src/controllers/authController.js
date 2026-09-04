// Register + login. Two separate endpoints (not "get or create") because the
// Android LoginActivity already has separate Register/Login buttons -
// we're matching that UI, not inventing a new flow.
const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const User = require('../models/User');

const SALT_ROUNDS = 10; // bcrypt's own cost factor - higher = slower to hash
                         // (good against brute force) but also slower for us.

// Mirrors User schema's username "match" validator - checked here too so
// registration fails fast with a clear message before ever touching Mongo.
const USERNAME_PATTERN = /^[A-Za-z0-9]+$/;
// At least 5 chars, only English letters/digits, at least one letter AND one digit.
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9]{5,}$/;
// Builds a signed JWT for this user, containing their Mongo _id under the
// standard "sub" claim, so requireAuth can identify the user on later requests.
function generateToken(user) {
    return jwt.sign({ sub: user._id.toString() }, process.env.JWT_SECRET, {
        expiresIn: process.env.JWT_EXPIRES_IN || '7d',
    });
}
// POST /register - creates a new user account with a hashed password and
// immediately logs them in (returns a token), so the client doesn't need a
// separate /login call right after registering.
async function register(req, res) {
    const { username, password } = req.body;

    if (!username || !password) {
        return res.status(400).json({
            error: 'שם משתמש וסיסמה נדרשים',
        });
    }
    if (!USERNAME_PATTERN.test(username)) {
        return res.status(400).json({
            error: 'שם המשתמש יכול להכיל רק אותיות באנגלית ומספרים',
        });
    }
    if (!PASSWORD_PATTERN.test(password)) {
        return res.status(400).json({
            error: 'הסיסמה חייבת להכיל לפחות 5 תווים, עם אותיות באנגלית ולפחות מספר אחד',
        });
    }

    try {
        // hash() salts and hashes in one call - the salt itself ends up
        // embedded in the output string, so we don't need to store it separately
        const passwordHash = await bcrypt.hash(password, SALT_ROUNDS);
        const user = await User.create({ username, passwordHash });

        ////////////////////////////////////////////////
        console.log(`[auth] new user registered: ${user.username} (id: ${user._id})`);
        //////////////////////////////////////////////////

        
        // logging the user in immediately after registering saves the app an
        // extra round trip - it doesn't have to call /login right after /register
        const token = generateToken(user);
        return res.status(201).json({
            token,
            userId: user._id,
            username: user.username,
        });
    } catch (err) {
        if (err.code === 11000) {
            // Mongo's duplicate-key error code - the unique index on
            // username caught a name that's already taken
            return res.status(409).json({ error: 'שם המשתמש כבר תפוס' });
        }
        console.error('[auth] register failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בהרשמה' });
    }
}
// POST /login - verifies username + password against the stored hash and
// returns a fresh JWT if they match.
async function login(req, res) {
    const { username, password } = req.body;

    if (!username || !password) {
        return res.status(400).json({ error: 'שם משתמש וסיסמה נדרשים' });
    }

    try {
        const user = await User.findOne({ username });
        if (!user) {
            return res.status(401).json({ error: 'משתמש לא קיים' });
        }

        const passwordMatches = await bcrypt.compare(password, user.passwordHash);
        if (!passwordMatches) {
            return res.status(401).json({ error: 'סיסמה שגויה' });
        }

        const token = generateToken(user);
        return res.json({
            token,
            userId: user._id,
            username: user.username,
        });
    } catch (err) {
        console.error('[auth] login failed:', err);
        return res.status(500).json({ error: 'שגיאת שרת בהתחברות' });
    }
}

module.exports = { register, login };
