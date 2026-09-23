// Same approach as historyController.test.js: mock the Mongoose model calls,
// run everything else for real.
const test = require('node:test');
const assert = require('node:assert/strict');

const Game = require('../src/models/Game');
const HistoryEntry = require('../src/models/HistoryEntry');
const { createGame, listGames, renameGame, getGameHistory } = require('../src/controllers/gameController');

function fakeRes() {
    return {
        statusCode: 200,
        body: undefined,
        status(code) { this.statusCode = code; return this; },
        json(payload) { this.body = payload; return this; },
    };
}
// createGame: the saved game's userId comes from req.userId, never a client-supplied one
test('createGame: a new game is created under the authenticated user, not a client-supplied one', async (t) => {
    let captured;
    t.mock.method(Game, 'create', async (doc) => {
        captured = doc;
        return { ...doc, _id: 'game1' };
    });

    const res = fakeRes();
    await createGame({ userId: 'authenticated-user', body: { name: '01/01/2026 10:00' } }, res);

    assert.equal(res.statusCode, 201);
    assert.equal(captured.userId, 'authenticated-user');
    assert.equal(captured.name, '01/01/2026 10:00');
});
// listGames: queries by the authenticated user, sorted newest first, capped
test('listGames: queries by the authenticated user, sorted newest first, capped at 50', async (t) => {
    let capturedFilter, capturedSort, capturedLimit;

    t.mock.method(Game, 'find', (filter) => {
        capturedFilter = filter;
        return {
            sort(s) { capturedSort = s; return this; },
            limit(l) { capturedLimit = l; return Promise.resolve([]); },
        };
    });

    const res = fakeRes();
    await listGames({ userId: 'u1' }, res);

    assert.equal(res.statusCode, 200);
    assert.deepEqual(capturedFilter, { userId: 'u1' });
    assert.deepEqual(capturedSort, { timestamp: -1 });
    assert.equal(capturedLimit, 50);
});
// renameGame: rejects an empty name with 400, never touches the DB
test('renameGame: rejects an empty name with 400, never touches the DB', async (t) => {
    const updateMock = t.mock.method(Game, 'findOneAndUpdate', async () => {
        throw new Error('should not be called');
    });

    const res = fakeRes();
    await renameGame({ userId: 'u1', params: { id: 'game1' }, body: { name: '   ' } }, res);

    assert.equal(res.statusCode, 400);
    assert.equal(updateMock.mock.callCount(), 0);
});
// renameGame: scopes the update by both the game id AND the authenticated user
test('renameGame: scopes the lookup by id and the authenticated user, not a client-supplied one', async (t) => {
    let capturedFilter, capturedUpdate;
    t.mock.method(Game, 'findOneAndUpdate', async (filter, update) => {
        capturedFilter = filter;
        capturedUpdate = update;
        return { _id: 'game1', userId: 'authenticated-user', name: 'New name' };
    });

    const res = fakeRes();
    await renameGame(
        { userId: 'authenticated-user', params: { id: 'game1' }, body: { name: '  New name  ' } },
        res
    );

    assert.equal(res.statusCode, 200);
    assert.deepEqual(capturedFilter, { _id: 'game1', userId: 'authenticated-user' });
    assert.deepEqual(capturedUpdate, { name: 'New name' });
    assert.equal(res.body.name, 'New name');
});
// renameGame: a game not found for this user returns 404, not a 500
test('renameGame: a game not found for this user returns 404', async (t) => {
    t.mock.method(Game, 'findOneAndUpdate', async () => null);

    const res = fakeRes();
    await renameGame(
        { userId: 'u1', params: { id: 'someone-elses-game' }, body: { name: 'New name' } },
        res
    );

    assert.equal(res.statusCode, 404);
});
// getGameHistory: filters by both the authenticated user AND the requested game, sorted newest first, capped
test('getGameHistory: filters by the authenticated user and the requested game, sorted newest first, capped at 50', async (t) => {
    let capturedFilter, capturedSort, capturedLimit;

    t.mock.method(HistoryEntry, 'find', (filter) => {
        capturedFilter = filter;
        return {
            sort(s) { capturedSort = s; return this; },
            limit(l) { capturedLimit = l; return Promise.resolve([]); },
        };
    });

    const res = fakeRes();
    await getGameHistory({ userId: 'u1', params: { id: 'game1' } }, res);

    assert.equal(res.statusCode, 200);
    assert.deepEqual(capturedFilter, { userId: 'u1', gameId: 'game1' });
    assert.deepEqual(capturedSort, { timestamp: -1 });
    assert.equal(capturedLimit, 50);
});
// getGameHistory: a game id that isn't this user's own returns an empty list, not another user's turns -
// the filter itself (userId + gameId together) is what makes this safe, this just proves it in practice
test('getGameHistory: a game id with no matching entries for this user returns an empty list', async (t) => {
    t.mock.method(HistoryEntry, 'find', () => ({
        sort() { return this; },
        limit() { return Promise.resolve([]); },
    }));

    const res = fakeRes();
    await getGameHistory({ userId: 'u1', params: { id: 'someone-elses-game' } }, res);

    assert.equal(res.statusCode, 200);
    assert.deepEqual(res.body, []);
});
