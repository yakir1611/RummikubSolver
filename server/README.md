# RummikubSolver server

Node.js + Express + MongoDB backend for the app. Scope on purpose: **only
auth and turn history**. Tile detection (Roboflow) and the actual solving
(`OptimalSolver`) both stay on the Android client - see the root
`DATA_CONTRACT.md` / project notes for why.

## What's here

```
server/
  src/
    app.js                 Express app + routes (no DB connection, no listen - testable on its own)
    server.js               real entry point: loads .env, connects Mongo, then app.listen()
    config/db.js             mongoose connection wrapper
    models/User.js           username + bcrypt password hash
    models/HistoryEntry.js   one saved turn (userId, timestamp, tilesPlayed)
    middleware/auth.js       requireAuth - verifies the JWT, sets req.userId
    controllers/             the actual register/login/save/list logic
    routes/                  wires controllers to Express routes
  test/                     unit tests (see "Testing" below)
```

## Endpoints

| Method | Path                 | Auth? | Body                              | Returns                          |
|--------|----------------------|-------|------------------------------------|-----------------------------------|
| GET    | `/api/health`         | no    | -                                   | `{ status: "ok" }`                |
| POST   | `/api/auth/register`  | no    | `{ username, password }`           | `{ token, userId, username }`     |
| POST   | `/api/auth/login`     | no    | `{ username, password }`           | `{ token, userId, username }`     |
| POST   | `/api/history`        | yes   | `{ tilesPlayed, boardSnapshot? }`  | the saved entry                   |
| GET    | `/api/history`        | yes   | -                                   | array of entries, newest first    |

"Auth: yes" means `Authorization: Bearer <token>` is required - the token
you got back from register/login. Error responses are always
`{ "error": "<Hebrew message>" }` with an appropriate status code (400/401/
409/500) - the Android client (`AppApiClient`) shows that message directly.

## Running it locally

1. `npm install`
2. Get MongoDB running - either:
   - Docker: `docker run -d -p 27017:27017 --name rummikub-mongo mongo:7`
   - or a local `mongod` install pointed at the default port
3. `cp .env.example .env` and fill in `JWT_SECRET` (the file explains how to
   generate one). Leave `MONGO_URI` as-is if you used the Docker command above.
4. `npm start` (or `npm run dev` for auto-restart on file changes)
5. Check it's alive: `curl http://localhost:3000/api/health` should return
   `{"status":"ok"}`

The Android app's default `APP_SERVER_URL` (`http://10.0.2.2:3000/`) points
at exactly this - the emulator's alias for "the host machine's localhost" -
so as long as the server is running on the same machine as Android Studio,
no `local.properties` change is needed for emulator testing. A physical
device needs a real reachable address there instead (and `network_security_config.xml`
only allows plain HTTP to 10.0.2.2/localhost/127.0.0.1 - a real deployment
needs HTTPS, or that config needs extending).

## Testing

`npm test` runs everything under `test/` with Node's built-in test runner -
no Jest/Mocha dependency needed.

**Why these tests mock MongoDB instead of using a real one:** the natural
choice (`mongodb-memory-server`, spins up a real disposable `mongod`) needs
to download a `mongod` binary the first time it runs. That download is
blocked in the sandbox this was built in (network allowlist), so the tests
here mock the two or three Mongoose calls each controller makes
(`User.create`, `User.findOne`, `HistoryEntry.create`, `HistoryEntry.find`)
and run everything else for real: actual bcrypt hashing, actual JWT
signing/verification, actual Express-shaped req/res objects. That's real
coverage of the logic that matters (status codes, password never leaking
into a response, wrong-password vs. unknown-user, one user never seeing
another's history) - it just doesn't touch a real database.

If you want a true end-to-end run against a real (in-memory) Mongo - worth
doing at least once before the submission, since it catches schema-level
bugs the mocks can't (typos in field names, a bad index, etc.) - that'll
work fine on a normal machine with open internet:

```bash
npm install --save-dev mongodb-memory-server supertest
```

then write a test that calls `MongoMemoryServer.create()`, `mongoose.connect()`
to its URI, `createApp()` from `src/app.js`, and drives it with `supertest`
the same way the mocked tests drive the controllers directly. Ask if you'd
like that test file written out - it's a quick add, this README just
doesn't assume the dependency is installed since it can't be verified here.

## Security notes (read before submitting)

- Passwords are hashed with bcrypt (cost factor 10) - never stored or logged
  in plaintext.
- `.env` (real secrets) is gitignored - only `.env.example` (placeholders) is
  committed. **Generate a real `JWT_SECRET` before running this for real**,
  don't leave the placeholder in.
- Login intentionally returns different errors for "user doesn't exist" vs.
  "wrong password" - easier to debug for a student project, but note in
  `authController.js` explains the trade-off (a hardened production API
  would return one generic message for both, to avoid confirming which
  usernames are registered).
