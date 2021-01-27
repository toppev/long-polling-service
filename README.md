# long-polling-service

A simple service for HTTP long polling messages.

The server is written in Ktor/Kotlin.

## Enpoints
There are only 3 endpoints:

**Subscribe:** POST `/<id>`, the server will check if anyone is attempting to send to that endpoint, otherwise the server will halt until a message is received or the request times out.

**Send to subscriber:** POST `/<id>/send` the request body is forwarded to the subscriber. This may halt for a while no one has subscribed for the id.

**Delete subscription:** DELETE `/<id>`. This will remove the connection (if any) waiting in the given endpoint and consequently, they will not receive the message.

## Building
Build with `./gradlew build` and see `./build/libs`.

Or with docker:
1. `docker build . -t long-polling`
2.  `docker run long-polling`

