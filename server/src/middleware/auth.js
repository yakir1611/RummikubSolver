// Protects routes that need a logged-in user (history save/read).
// Expects "Authorization: Bearer <token>" - the token is whatever
// authController.login() handed back after a successful login.
const jwt = require('jsonwebtoken');
// Express middleware that runs before protected routes - checks for a
// valid "Bearer <token>" in the Authorization header and blocks the
// request with 401 if it's missing, malformed, or expired.
function requireAuth(req, res, next) {
    const header = req.headers.authorization || '';
    const [scheme, token] = header.split(' ');

    if (scheme !== 'Bearer' || !token) {
        return res.status(401).json({ error: 'לא נשלח טוקן התחברות' });
    }

    try {
        const payload = jwt.verify(token, process.env.JWT_SECRET);
        req.userId = payload.sub;
        next();
    } catch (err) {
        return res.status(401).json({ error: 'טוקן לא תקין או פג תוקף' });
    }
}

module.exports = { requireAuth };
