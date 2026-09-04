// Actual entry point: node src/server.js. Loads the .env file, connects to
// MongoDB, then starts listening. Kept separate from app.js so tests can
// import createApp() without triggering any of this.
require('dotenv').config();

const { createApp } = require('./app');
const { connectDB } = require('./config/db');

const PORT = process.env.PORT || 3000;

async function main() {
    await connectDB(process.env.MONGO_URI);

    const app = createApp();
    app.listen(PORT, () => {
        console.log(`[server] listening on port ${PORT}`);
    });
}

main().catch((err) => {
    // if Mongo isn't reachable or JWT_SECRET is missing, fail loudly at
    // startup instead of limping along and throwing on the first request
    console.error('[server] failed to start:', err);
    process.exit(1);
});
