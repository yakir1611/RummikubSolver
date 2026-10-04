// Wraps the MongoDB connection in one place so server.js and the tests
// don't each need to know mongoose's connect() options.
const mongoose = require('mongoose');

/**
 * Connects to MongoDB and returns the connection promise.
 *
 * @param {string} uri - Mongo connection string. Passed in explicitly
 *   (instead of reading process.env directly in here) so tests can point
 *   this at an in-memory Mongo instance without touching real env vars.
 */
async function connectDB(uri) {
    if (!uri) {
        throw new Error('MONGO_URI is not set - copy .env.example to .env and fill it in');
    }

    await mongoose.connect(uri);
    console.log(`[db] connected to MongoDB at ${maskCredentials(uri)}`);
    return mongoose.connection;
}

async function disconnectDB() {
    await mongoose.disconnect();
}

function maskCredentials(uri) {
    return uri.replace(/\/\/([^:]+):([^@]+)@/, '//$1:***@');
}

module.exports = { connectDB, disconnectDB };
