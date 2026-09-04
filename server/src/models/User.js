// A registered player.
// We never store the raw password - only its bcrypt
// hash (see authController.register). "username" is the login handle.
const mongoose = require('mongoose');

const userSchema = new mongoose.Schema({
    username: {
        type: String,
        required: true,
        unique: true, // Mongo builds a unique index on this - a duplicate
                       // insert throws E11000, which authController catches
        trim: true,
        match: [/^[A-Za-z0-9]+$/, 'Username must contain only English letters and digits'],
    },
    passwordHash: {
        type: String,
        required: true,
    },
    createdAt: {
        type: Date,
        default: Date.now,
    },
});

module.exports = mongoose.model('User', userSchema);
