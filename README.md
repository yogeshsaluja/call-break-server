# PlayCrew game servers

This repository deploys independent Ktor services for the PlayCrew games.

| Service | Distribution task | Loopback port | Public WebSocket |
|---|---|---|---|
| Call Break | `:server:installDist` | 8081 | `/ws` |
| Ludo | `:ludo-server:installDist` | 8083 | `/games/ludo/ws` |

Build and test locally:

```bash
./gradlew :server:installDist :ludo-server:installDist :ludo-server:test
```

The deployment workflow installs immutable distributions under `/opt/callbreak-server/releases`, points `current` and `ludo-current` at the active release, and restarts `callbreak.service` and `ludo.service`. Both applications listen only on `127.0.0.1`; Nginx terminates TLS and proxies the public routes.
