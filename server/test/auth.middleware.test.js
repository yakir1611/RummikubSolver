// requireAuth is pure JWT logic with no DB involved, so this one runs
// entirely for real - no mocking needed at all.
const test = require('node:test');
const assert = require('node:assert/strict');
const jwt = require('jsonwebtoken');

const { requireAuth } = require('../src/middleware/auth');

process.env.JWT_SECRET = 'test-secret-not-for-production';

function fakeRes() {
    return {
        statusCode: 200,
        body: undefined,
        status(code) { this.statusCode = code; return this; },
        json(payload) { this.body = payload; return this; },
    };
}
// No Authorization header at all - requireAuth must reject with 401 and never call next().
test('requireAuth: missing Authorization header -> 401, next() not called', () => {
    let nextCalled = false;
    const res = fakeRes();

    requireAuth({ headers: {} }, res, () => { nextCalled = true; });

    assert.equal(res.statusCode, 401);
    assert.equal(nextCalled, false);
});
// Header present but not in the "Bearer <token>" format - still rejected.
test('requireAuth: header without "Bearer " prefix -> 401', () => {
    const res = fakeRes();
    let nextCalled = false;

    requireAuth({ headers: { authorization: 'sometoken' } }, res, () => { nextCalled = true; });

    assert.equal(res.statusCode, 401);
    assert.equal(nextCalled, false);
});
// Valid, correctly signed token - request proceeds and req.userId is set from it.
test('requireAuth: valid token -> next() runs and req.userId is set', () => {
    const token = jwt.sign({ sub: 'user-42' }, process.env.JWT_SECRET, { expiresIn: '1h' });
    const req = { headers: { authorization: `Bearer ${token}` } };
    const res = fakeRes();
    let nextCalled = false;

    requireAuth(req, res, () => { nextCalled = true; });

    assert.equal(nextCalled, true);
    assert.equal(req.userId, 'user-42');
});
// Token that was already expired the moment it was issued - rejected with 401
test('requireAuth: expired token -> 401', () => {
    // expiresIn: -1 means "already expired the moment it was issued"
    const token = jwt.sign({ sub: 'user-42' }, process.env.JWT_SECRET, { expiresIn: -1 });
    const res = fakeRes();
    let nextCalled = false;

    requireAuth({ headers: { authorization: `Bearer ${token}` } }, res, () => { nextCalled = true; });

    assert.equal(res.statusCode, 401);
    assert.equal(nextCalled, false);
});
// Token signed with a different secret than the server's JWT_SECRET - can't be forged, rejected with 401
test('requireAuth: token signed with a different secret -> 401', () => {
    const token = jwt.sign({ sub: 'user-42' }, 'wrong-secret', { expiresIn: '1h' });
    const res = fakeRes();
    let nextCalled = false;

    requireAuth({ headers: { authorization: `Bearer ${token}` } }, res, () => { nextCalled = true; });

    assert.equal(res.statusCode, 401);
    assert.equal(nextCalled, false);
});
