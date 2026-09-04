// Unit tests for authController, run WITHOUT a real MongoDB connection because the test were written before connecting to MongoDB.
// What we do here instead: mock the two calls authController makes into
// Mongoose (User.create / User.findOne) with node:test's built-in t.mock,
// and run everything else for real - real bcrypt hashing, real JWT
// signing/verification, real Express req/res objects via a tiny fake. This
// still catches the actual bugs that matter here: wrong status codes, the
// password never leaking into a response, duplicate-username handling,
// wrong-password handling.
const test = require('node:test');
const assert = require('node:assert/strict');
const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');

const User = require('../src/models/User');
const { register, login } = require('../src/controllers/authController');

process.env.JWT_SECRET = 'test-secret-not-for-production';

// Minimal stand-in for Express's res object: just enough for
// res.status(x).json(y) to work and to let us inspect what was sent.
function fakeRes() {
    return {
        statusCode: 200,
        body: undefined,
        status(code) {
            this.statusCode = code;
            return this;
        },
        json(payload) {
            this.body = payload;
            return this;
        },
    };
}
// Password that doesn't match the required format is rejected before the DB is ever touched (validation runs first)
test('register: rejects a password shorter than 5 chars before touching the DB', async (t) => {
    const createMock = t.mock.method(User, 'create', async () => {
        throw new Error('should not be called');
    });

    const res = fakeRes();
    await register({ body: { username: 'ok', password: 'ab' } }, res);

    assert.equal(res.statusCode, 400);
    assert.equal(createMock.mock.callCount(), 0);
});
// Successful register: password is hashed (never stored/returned raw), and the returned token decodes to the right user id
test('register: hashes the password (never stores or returns it raw) and returns a valid token', async (t) => {
    let capturedInsert;
    t.mock.method(User, 'create', async (doc) => {
        capturedInsert = doc;
        return { _id: 'abc123', username: doc.username };
    });

    const res = fakeRes();
    await register({ body: { username: 'yakir', password: 'plaintext1pw' } }, res);

    assert.equal(res.statusCode, 201);
    assert.equal(res.body.username, 'yakir');
    assert.ok(res.body.token, 'expected a token in the response');
    assert.ok(!('password' in res.body), 'raw password must never be echoed back');

    // the thing that got sent to Mongo should be a bcrypt hash, not the raw password
    assert.notEqual(capturedInsert.passwordHash, 'plaintext1pw');
    const matches = await bcrypt.compare('plaintext1pw', capturedInsert.passwordHash);
    assert.ok(matches, 'stored hash should verify against the original password');

    // and the token should be a real, verifiable JWT carrying that user's id
    const decoded = jwt.verify(res.body.token, process.env.JWT_SECRET);
    assert.equal(decoded.sub, 'abc123');
});
// Mongo's duplicate-username error (E11000) is translated to 409, not a generic 500
test('register: a duplicate username (Mongo E11000) becomes a 409, not a 500', async (t) => {
    t.mock.method(User, 'create', async () => {
        const err = new Error('duplicate key');
        err.code = 11000;
        throw err;
    });

    const res = fakeRes();
    await register({ body: { username: 'taken', password: 'whatever1' } }, res);

    assert.equal(res.statusCode, 409);
});
// Login with a username that was never registered - 401
test('login: unknown username returns 401 without leaking whether the DB was even reachable', async (t) => {
    t.mock.method(User, 'findOne', async () => null);

    const res = fakeRes();
    await login({ body: { username: 'ghost', password: 'whatever' } }, res);

    assert.equal(res.statusCode, 401);
});
// Login with the right username but wrong password - 401
test('login: wrong password returns 401', async (t) => {
    const realHash = await bcrypt.hash('correct-pw', 10);
    t.mock.method(User, 'findOne', async () => ({
        _id: 'u1',
        username: 'matan',
        passwordHash: realHash,
    }));

    const res = fakeRes();
    await login({ body: { username: 'matan', password: 'wrong-pw' } }, res);

    assert.equal(res.statusCode, 401);
});
// Login with correct credentials - 200, and the token decodes to the right user id
test('login: correct credentials return 200 and a token that verifies', async (t) => {
    const realHash = await bcrypt.hash('correct-pw', 10);
    t.mock.method(User, 'findOne', async () => ({
        _id: 'u1',
        username: 'matan',
        passwordHash: realHash,
    }));

    const res = fakeRes();
    await login({ body: { username: 'matan', password: 'correct-pw' } }, res);

    assert.equal(res.statusCode, 200);
    const decoded = jwt.verify(res.body.token, process.env.JWT_SECRET);
    assert.equal(decoded.sub, 'u1');
});
