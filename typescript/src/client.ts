// Service-owned: the Better Stack Logs client (API.md §6; DECISIONS 4 and 5).

import type { Dispatcher } from "undici";
import {
  ConfigError,
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnsupportedError,
  createDispatcher,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { BetterStackConfig, type BetterStackConfigOptions } from "./config.js";
import { BetterStackLogExporter, type BetterStackLogExporterOptions } from "./exporter.js";
import { joinUrl, mapIngestError, mappedRequest } from "./http.js";
import {
  BatchingLogClient,
  type BatchingLogClientOptions,
  type LogExporter,
} from "./logging/index.js";
import { BetterStackOtlpLogExporter, type BetterStackOtlpLogExporterOptions } from "./otlp.js";
import { BetterStackQuery } from "./query.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** Options of {@link BetterStackClient.logClient}. */
export interface BetterStackLogClientOptions extends BatchingLogClientOptions {
  /** `"http"` (Better Stack ingest API, default) or `"otlp"` (OTLP/HTTP `/v1/logs`). */
  protocol?: "http" | "otlp";
  /** Encoding and limits of the HTTP exporter (protocol `"http"`). */
  exporter?: BetterStackLogExporterOptions;
  /** Options of the OTLP exporter (protocol `"otlp"`). */
  otlp?: BetterStackOtlpLogExporterOptions;
}

/**
 * {@link LogClient} that ships records to the source's ingesting host through a background
 * `BatchProcessor`. Create it with {@link BetterStackClient.logClient}.
 */
export class BetterStackLogClient extends BatchingLogClient {
  /** The exporter used by this client. */
  readonly exporter: LogExporter;

  /** @param exporter the ingest exporter. @param options batching, resource, redaction. */
  constructor(exporter: LogExporter, options: BatchingLogClientOptions = {}) {
    super(exporter, options);
    this.exporter = exporter;
  }
}

const ALL_ERROR_STATUSES: readonly number[] = Array.from({ length: 200 }, (_, i) => 400 + i);

/**
 * Client for Better Stack Logs: ingestion through the source's ingesting host (`logClient`,
 * `exporter`, `otlpExporter`) and the SQL query API (`query()`), each with its own credentials and
 * transport. A source token is never sent to the query host and SQL credentials never to the
 * ingest host.
 *
 * @example
 * const client = BetterStackClient.fromEnv();
 * const logs = client.logClient({ resource: { "service.name": "checkout" } });
 * logs.logger().info("order placed", { orderId: 42 });
 * await client.close();
 */
export class BetterStackClient {
  /** The immutable configuration. */
  readonly config: BetterStackConfig;
  readonly #ingest: HttpTransport | undefined;
  readonly #queryTransport: HttpTransport | undefined;
  readonly #ownedDispatcher: Dispatcher | undefined;
  readonly #logClients = new Set<BetterStackLogClient>();
  #exporter: BetterStackLogExporter | undefined;
  #otlp: BetterStackOtlpLogExporter | undefined;
  #query: BetterStackQuery | undefined;
  #closing: Promise<void> | undefined;

  /** Accepts a {@link BetterStackConfig} or plain options. Throws `ConfigError` when invalid. */
  constructor(config: BetterStackConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = BetterStackConfig.from(config);
    this.config = cfg;
    let dispatcher = internals.dispatcher;
    if (dispatcher === undefined) {
      try {
        dispatcher = createDispatcher(cfg.transportConfig(cfg.hasIngest ? "ingest" : "query"));
      } catch (err) {
        throw new ConfigError("invalid configuration: cannot read a TLS file", { cause: err });
      }
      this.#ownedDispatcher = dispatcher;
    }
    const make = (side: "ingest" | "query"): HttpTransport =>
      new HttpTransport({
        ...internals,
        dispatcher: dispatcher,
        config: cfg.transportConfig(side),
        knownSecrets: () => cfg.knownSecrets(),
      });
    if (cfg.hasIngest) this.#ingest = make("ingest");
    if (cfg.hasQuery) this.#queryTransport = make("query");
  }

  /**
   * Builds a client from `GROW_BETTER_STACK_LOGS_*`, `BETTERSTACK_SOURCE_TOKEN`,
   * `BETTERSTACK_INGESTING_HOST`, `BETTERSTACK_QUERY_*` … environment variables.
   */
  static fromEnv(overrides: BetterStackConfigOptions = {}): BetterStackClient {
    return new BetterStackClient(BetterStackConfig.fromEnv(overrides));
  }

  #ingestTransport(operation: string): HttpTransport {
    if (this.#ingest === undefined) {
      throw new ConfigError(
        `${operation}: url (the source's ingesting host) and sourceToken are required for ingestion`,
      );
    }
    return this.#ingest;
  }

  #sqlTransport(operation: string): HttpTransport {
    if (this.#queryTransport === undefined) {
      throw new ConfigError(
        `${operation}: queryUrl, queryUsername and queryPassword are required for SQL queries`,
      );
    }
    return this.#queryTransport;
  }

  /**
   * Unauthenticated liveness. With a query host: `GET {queryUrl}/ping` (`Ok.`). Ingest-only:
   * `GET {url}/` without credentials (any answer below 500 proves the host is reachable; no data
   * is sent). Never throws except `CANCELLED`/`CONFIG`.
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const signal = options.signal === undefined ? {} : { signal: options.signal };
      if (this.#queryTransport !== undefined) {
        const res = await this.#queryTransport.get<string>(
          joinUrl(this.config.queryUrl ?? "", "/ping"),
          { operation: "health", responseType: "text", ...signal },
        );
        const word = res.data.trim().replace(/\.$/, "");
        return healthStatus(word === "" ? "ok" : word.toLowerCase(), { endpoint: "query" });
      }
      const ingest = this.#ingestTransport("health");
      const res = await ingest.get(joinUrl(this.config.url ?? "", "/"), {
        operation: "health",
        responseType: "none",
        allowStatus: ALL_ERROR_STATUSES,
        ...signal,
      });
      if (res.status >= 500) {
        return healthStatus("unhealthy", { endpoint: "ingest", httpStatus: res.status }, false);
      }
      return healthStatus("ok", { endpoint: "ingest", httpStatus: res.status }, true);
    });
  }

  /**
   * Authenticated round trip; returns the latency in ms. With query credentials:
   * `SELECT count() AS c FROM remote(<sourceTable>_logs) WHERE 1 = 0` (or `SELECT 1` without a
   * `sourceTable`). Ingest-only: posts an empty batch `[]` (0 events). Bad credentials →
   * `AuthenticationError`.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    if (this.#queryTransport !== undefined) {
      const table = this.config.sourceTable;
      const sql =
        table === undefined
          ? "SELECT 1 AS ok FORMAT JSONEachRow"
          : `SELECT count() AS c FROM remote(${table}_logs) WHERE 1 = 0 FORMAT JSONEachRow`;
      await this.query().queryRaw(sql, {}, options.signal === undefined ? {} : options);
      return performance.now() - start;
    }
    await mappedRequest(
      this.#ingestTransport("ping"),
      {
        method: "POST",
        path: joinUrl(this.config.url ?? "", "/"),
        headers: {
          authorization: `Bearer ${this.config.sourceToken?.reveal() ?? ""}`,
          "content-type": "application/json",
        },
        body: "[]",
        idempotent: true,
        operation: "ping",
        responseType: "none",
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
      mapIngestError,
    );
    return performance.now() - start;
  }

  /** Better Stack is a SaaS without a version endpoint: always throws `UnsupportedError`. */
  serverVersion(_options: CallOptions = {}): Promise<string> {
    return Promise.reject(
      new UnsupportedError("serverVersion: Better Stack does not report a server version"),
    );
  }

  /** The HTTP ingest exporter (JSON encoding, default limits). Requires the ingest side. */
  get exporter(): BetterStackLogExporter {
    this.#exporter ??= new BetterStackLogExporter(this.#ingestTransport("exporter"), this.config);
    return this.#exporter;
  }

  /** The OTLP/HTTP logs exporter (protobuf). Requires the ingest side. */
  get otlpExporter(): BetterStackOtlpLogExporter {
    this.#otlp ??= new BetterStackOtlpLogExporter(
      this.#ingestTransport("otlpExporter"),
      this.config,
    );
    return this.#otlp;
  }

  /**
   * A batching {@link BetterStackLogClient} over the HTTP ingest API (default) or OTLP.
   * {@link close} flushes and closes every log client it created. Requires the ingest side.
   */
  logClient(options: BetterStackLogClientOptions = {}): BetterStackLogClient {
    if (this.#closing !== undefined) throw new ConfigError("logClient: client is closed");
    const { protocol, exporter: exporterOptions, otlp, ...batching } = options;
    const transport = this.#ingestTransport("logClient");
    let exporter: LogExporter;
    const wire: string = protocol ?? "http";
    if (wire === "otlp") {
      exporter =
        otlp === undefined
          ? this.otlpExporter
          : new BetterStackOtlpLogExporter(transport, this.config, otlp);
    } else if (wire === "http") {
      exporter =
        exporterOptions === undefined
          ? this.exporter
          : new BetterStackLogExporter(transport, this.config, exporterOptions);
    } else {
      throw new ConfigError('logClient: protocol must be "http" or "otlp"');
    }
    const client = new BetterStackLogClient(exporter, batching);
    this.#logClients.add(client);
    return client;
  }

  /** The SQL query client. Throws `ConfigError` without query credentials. */
  query(): BetterStackQuery {
    this.#query ??= new BetterStackQuery(this.#sqlTransport("query"), this.config);
    return this.#query;
  }

  /** Flushes and closes every log client, then releases connections. Idempotent. */
  close(): Promise<void> {
    this.#closing ??= (async () => {
      await Promise.allSettled([...this.#logClients].map((c) => c.close()));
      this.#logClients.clear();
      await this.#ingest?.close();
      await this.#queryTransport?.close();
      await this.#ownedDispatcher?.close().catch(() => undefined);
    })();
    return this.#closing;
  }
}
