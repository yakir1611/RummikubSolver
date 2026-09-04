// Same approach as authController.test.js: mock the Mongoose model calls,
// run everything else for real. See that file's header comment for why
// there's no real MongoDB connection in this sandbox.
const test = require('node:test');
const assert = require('node:assert/strict');

const HistoryEntry = require('../src/models/HistoryEntry');
const { saveEntry, getHistory, renameEntry } = require('../src/controllers/historyController');

function fakeRes() {
    return {
        statusCode: 200,
        body: undefined,
        status(code) { this.statusCode = code; return this; },
        json(payload) { this.body = payload; return this; },
    };
}
// Missing tilesPlayed in the request body - rejected with 400 before touching the DB
test('saveEntry: rejects a missing tilesPlayed with 400, never touches the DB', async (t) => {
    const createMock = t.mock.method(HistoryEntry, 'create', async () => {
        throw new Error('should not be called');
    });

    const res = fakeRes();
    await saveEntry({ userId: 'u1', body: {} }, res);

    assert.equal(res.statusCode, 400);
    assert.equal(createMock.mock.callCount(), 0);
});
// Negative tilesPlayed is invalid - rejected with 400
test('saveEntry: rejects a negative tilesPlayed with 400', async () => {
    const res = fakeRes();
    await saveEntry({ userId: 'u1', body: { tilesPlayed: -3 } }, res);
    assert.equal(res.statusCode, 400);
});
// The saved entry's userId comes from req.userId (the authenticated user via JWT), never from the request body
test('saveEntry: valid entry is saved under the authenticated user, not a client-supplied one', async (t) => {
    let captured;
    t.mock.method(HistoryEntry, 'create', async (doc) => {
        captured = doc;
        return { ...doc, _id: 'entry1' };
    });

    const res = fakeRes();
    // note: userId comes from req.userId (set by requireAuth from the JWT),
    // never from req.body - a client can't save history "as" another user
    await saveEntry({ userId: 'authenticated-user', body: { tilesPlayed: 6 } }, res);

    assert.equal(res.statusCode, 201);
    assert.equal(captured.userId, 'authenticated-user');
    assert.equal(captured.tilesPlayed, 6);
});
// saveEntry/getHistory: all four Solution-screen sections (board/hand, before/after)
// round-trip through save and fetch untouched, in the same tile-code shape they were sent in
test('saveEntry: saves all four sections (board/hand before/after), and getHistory returns them unchanged', async (t) => {
    const boardBefore = [['R5', 'R6', 'R7']];
    const handBefore = ['B1', 'B2', 'JOKER'];
    const boardAfter = [['R5', 'R6', 'R7'], ['B1', 'B2', 'B3']];
    const handRemaining = ['JOKER'];

    let captured;
    t.mock.method(HistoryEntry, 'create', async (doc) => {
        captured = doc;
        return { ...doc, _id: 'entry1' };
    });

    const saveRes = fakeRes();
    await saveEntry({
        userId: 'u1',
        body: { name: '01/01/2026 10:00', tilesPlayed: 2, boardBefore, handBefore, boardAfter, handRemaining },
    }, saveRes);

    assert.equal(saveRes.statusCode, 201);
    assert.deepEqual(captured.boardBefore, boardBefore);
    assert.deepEqual(captured.handBefore, handBefore);
    assert.deepEqual(captured.boardAfter, boardAfter);
    assert.deepEqual(captured.handRemaining, handRemaining);

    // getHistory just returns whatever find() gives back - simulate it
    // returning the same document saveEntry would have created
    t.mock.method(HistoryEntry, 'find', () => ({
        sort() { return this; },
        limit() { return Promise.resolve([{ ...captured, _id: 'entry1' }]); },
    }));

    const listRes = fakeRes();
    await getHistory({ userId: 'u1' }, listRes);

    assert.equal(listRes.statusCode, 200);
    assert.deepEqual(listRes.body[0].boardBefore, boardBefore);
    assert.deepEqual(listRes.body[0].handBefore, handBefore);
    assert.deepEqual(listRes.body[0].boardAfter, boardAfter);
    assert.deepEqual(listRes.body[0].handRemaining, handRemaining);
});
// getHistory filters by the authenticated user's id, sorts newest-first, and caps results at 50
test('getHistory: queries by the authenticated user, sorted newest first, capped at 50', async (t) => {
    let capturedFilter;
    let capturedSort;
    let capturedLimit;

    // find() needs to return a chainable {sort, limit} object since the
    // controller does .find(...).sort(...).limit(...)
    t.mock.method(HistoryEntry, 'find', (filter) => {
        capturedFilter = filter;
        return {
            sort(s) { capturedSort = s; return this; },
            limit(l) { capturedLimit = l; return Promise.resolve([]); },
        };
    });

    const res = fakeRes();
    await getHistory({ userId: 'u1' }, res);

    assert.equal(res.statusCode, 200);
    assert.deepEqual(capturedFilter, { userId: 'u1' });
    assert.deepEqual(capturedSort, { timestamp: -1 });
    assert.equal(capturedLimit, 50);
});
// renameEntry: rejects an empty name with 400, never touches the DB
test('renameEntry: rejects an empty name with 400, never touches the DB', async (t) => {
    const updateMock = t.mock.method(HistoryEntry, 'findOneAndUpdate', async () => {
        throw new Error('should not be called');
    });

    const res = fakeRes();
    await renameEntry({ userId: 'u1', params: { id: 'entry1' }, body: { name: '   ' } }, res);

    assert.equal(res.statusCode, 400);
    assert.equal(updateMock.mock.callCount(), 0);
});
// renameEntry: scopes the update by both the entry id AND the authenticated user - a client
// can't rename another user's entry just by guessing/knowing its id
test('renameEntry: scopes the lookup by id and the authenticated user, not a client-supplied one', async (t) => {
    let capturedFilter;
    let capturedUpdate;
    t.mock.method(HistoryEntry, 'findOneAndUpdate', async (filter, update) => {
        capturedFilter = filter;
        capturedUpdate = update;
        return { _id: 'entry1', userId: 'authenticated-user', name: 'New name' };
    });

    const res = fakeRes();
    await renameEntry(
        { userId: 'authenticated-user', params: { id: 'entry1' }, body: { name: '  New name  ' } },
        res
    );

    assert.equal(res.statusCode, 200);
    assert.deepEqual(capturedFilter, { _id: 'entry1', userId: 'authenticated-user' });
    assert.deepEqual(capturedUpdate, { name: 'New name' }); // trimmed
    assert.equal(res.body.name, 'New name');
});
// renameEntry: an entry that doesn't exist (or belongs to someone else - same
// findOneAndUpdate filter either way) returns 404, not a 500
test('renameEntry: an entry not found for this user returns 404', async (t) => {
    t.mock.method(HistoryEntry, 'findOneAndUpdate', async () => null);

    const res = fakeRes();
    await renameEntry(
        { userId: 'u1', params: { id: 'someone-elses-entry' }, body: { name: 'New name' } },
        res
    );

    assert.equal(res.statusCode, 404);
});
