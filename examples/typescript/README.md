# TypeScript example

Sends two log lines through the source's ingesting host and reads them back with the SQL query API
(`waitForLog`, `searchLogs`, `query` with a bound parameter).

```sh
(cd ../../typescript && npm install && npm run build)
npm install
BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com BETTERSTACK_SOURCE_TOKEN=... \
BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com BETTERSTACK_QUERY_USERNAME=... \
BETTERSTACK_QUERY_PASSWORD=... BETTERSTACK_SOURCE_TABLE=t1234_myapp npm start
```

Without the query variables the example only sends. Replace the `file:` dependency in
`package.json` with a version (`npm install @grow-codes/better-stack-logs`) to run it against the
published package.
