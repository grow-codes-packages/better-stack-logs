# TypeScript integration tests

A consumer project: it installs the packed `@grow-codes/better-stack-logs` tarball (never the source tree)
and runs the integration and conformance tests with `node --test` against a real Better Stack Logs.

```sh
cd typescript && make pack            # writes dist/grow-codes-better-stack-logs-<version>.tgz
cd integration && npm run install-package
GROW_BETTER_STACK_LOGS_URL=http://127.0.0.1:<port> GROW_BETTER_STACK_LOGS_TOKEN=... npm test
```

In CI and locally with Docker this runs inside `docker/Dockerfile.typescript`
(`make test-docker LANGS=typescript` from the repository root), which reads `/bootstrap/env`.
