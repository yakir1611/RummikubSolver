// The Express app itself, separate from server.js which actually starts it
// listening. Splitting these two lets tests build the app and hit its
// routes directly (via a library like supertest) without opening a real
// network port or needing dotenv/.env to be configured.
const express = require('express');
const cors = require('cors');

const authRoutes = require('./routes/authRoutes');
const historyRoutes = require('./routes/historyRoutes');

function createApp() {
    const app = express();

    app.use(cors()); // the Android client isn't a browser so CORS doesn't
                      // block it either way, but this keeps the API testable
                      // from a browser/Postman during development too
    app.use(express.json()); // parses JSON request bodies into req.body

    // simple liveness check - useful to confirm the server + DB connection
    // are both up before pointing the Android app at it
    app.get('/api/health', (req, res) => res.json({ status: 'ok' }));

    app.use('/api/auth', authRoutes);
    app.use('/api/history', historyRoutes);

    // catch-all for unknown routes
    app.use((req, res) => {
        res.status(404).json({ error: 'לא נמצא' });
    });

    // express recognizes an error handler specifically by its 4 arguments
    // (req/res/next aren't enough) - this catches anything a route handler
    // throws or calls next(err) with, so one bug doesn't crash the process
    app.use((err, req, res, next) => {
        console.error('[app] unhandled error:', err);
        res.status(500).json({ error: 'שגיאת שרת' });
    });

    return app;
}

module.exports = { createApp };
