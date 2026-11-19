package betterstack

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

// Client is the Better Stack Logs client. It is safe for concurrent use.
//
// It talks to two hosts with separate credentials: the source's ingesting host
// (NewLogClient, Exporter, OTLPExporter) and the SQL query host (Query). Close flushes
// every LogClient made by NewLogClient / NewLogClientWith, stops running tails and
// releases connections.
type Client struct {
	cfg    Config
	ingest *httpTransport // nil without ingest credentials
	query  *httpTransport // nil without query credentials

	exporter *IngestExporter
	otlp     *OTLPLogExporter
	sql      *Query

	mu       sync.Mutex
	closers  []func(ctx context.Context) error
	closed   bool
	closeErr error
	once     sync.Once
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	cfg.URL = NormalizeBaseURL(cfg.URL)
	cfg.QueryURL = NormalizeBaseURL(cfg.QueryURL)
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	c := &Client{cfg: cfg}
	if cfg.HasIngest() {
		t, err := newServiceTransport(cfg.common(cfg.URL), cfg.SourceToken)
		if err != nil {
			return nil, err
		}
		c.ingest = t
		c.exporter = &IngestExporter{client: c}
		c.otlp = &OTLPLogExporter{client: c}
	}
	if cfg.HasQuery() {
		t, err := newServiceTransport(cfg.common(cfg.QueryURL), cfg.QueryPassword)
		if err != nil {
			if c.ingest != nil {
				c.ingest.close()
			}
			return nil, err
		}
		c.query = t
		c.sql = &Query{client: c}
	}
	if cfg.HasIngest() {
		cfg.CommonConfig = cfg.common(cfg.URL)
	} else {
		cfg.CommonConfig = cfg.common(cfg.QueryURL)
		cfg.URL = ""
	}
	c.cfg = cfg
	for _, raw := range []string{cfg.URL, cfg.QueryURL} {
		if u, err := url.Parse(raw); err == nil && u.Scheme == "http" {
			c.anyTransport().log(slog.LevelWarn, "a Better Stack endpoint uses plain http; credentials travel unencrypted (use only for local test services)")
			break
		}
	}
	return c, nil
}

func newServiceTransport(cc CommonConfig, secret Secret) (*httpTransport, error) {
	t, err := newHTTPTransport(cc)
	if err != nil {
		return nil, err
	}
	t.client.Transport = captureTransport{next: t.transport}
	t.addSecret(secret.Reveal())
	return t, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration.
func (c *Client) Config() Config { return c.cfg }

func (c *Client) anyTransport() *httpTransport {
	if c.query != nil {
		return c.query
	}
	return c.ingest
}

// ingestHeader is the source-token header (sent to the ingesting host only).
func (c *Client) ingestHeader() http.Header {
	return http.Header{"Authorization": {"Bearer " + c.cfg.SourceToken.Reveal()}}
}

// queryHeader is the HTTP Basic header (sent to the query host only).
func (c *Client) queryHeader() http.Header {
	req := http.Request{Header: http.Header{}}
	req.SetBasicAuth(c.cfg.QueryUsername, c.cfg.QueryPassword.Reveal())
	return req.Header
}

func (c *Client) needIngest(op string) error {
	if c.ingest == nil {
		return ConfigError(op + " needs the ingest credentials (url and sourceToken)")
	}
	return nil
}

func (c *Client) needQuery(op string) error {
	if c.query == nil {
		return ConfigError(op + " needs the query credentials (queryUrl, queryUsername and queryPassword)")
	}
	return nil
}

// Health checks liveness without credentials and without sending data
// (details["endpoint"] is "query" or "ingest"). With query
// credentials it calls GET {queryUrl}/ping (ClickHouse, "Ok."); otherwise it makes a
// GET request to the ingesting host, where any HTTP answer below 500 means the host
// is reachable (status "ok"). An unreachable or unhealthy service is reported in the
// result; it fails only for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	if c.query != nil {
		hs, err := c.query.checkHealth(ctx, "health", &request{Method: http.MethodGet, Path: "/ping"})
		if err == nil {
			if hs.Details == nil {
				hs.Details = map[string]string{}
			}
			hs.Details["endpoint"] = "query"
		}
		return hs, err
	}
	res, err := c.ingest.do(ctx, &request{Method: http.MethodGet, Path: "/", Operation: "health", NoRetry: true, RawStatus: true})
	if err != nil {
		switch CodeOf(err) {
		case ErrorCodeCancelled, ErrorCodeConfig:
			return HealthStatus{}, err
		}
		return HealthStatus{Status: "unreachable", Details: map[string]string{"error": err.Error(), "endpoint": "ingest"}}, nil
	}
	hs := HealthStatus{Status: "ok", Healthy: true, Details: map[string]string{"httpStatus": strconv.Itoa(res.Status), "endpoint": "ingest"}}
	if res.Status >= 500 {
		hs.Status, hs.Healthy = "unhealthy", false
	}
	return hs, nil
}

// Ping makes an authenticated round trip and returns its latency. With query
// credentials it runs "SELECT count() FROM remote(<sourceTable>_logs) WHERE 1 = 0"
// (or "SELECT 1" without a source table); otherwise it posts an empty batch ("[]",
// no event is stored) to the ingesting host. Bad credentials are an AUTHENTICATION
// error.
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	if c.sql != nil {
		sql := "SELECT 1 AS ok"
		if c.cfg.SourceTable != "" {
			sql = "SELECT count() AS c FROM remote(" + c.cfg.SourceTable + "_logs) WHERE 1 = 0"
		}
		if _, err := c.sql.rows(ctx, "ping", sql, nil); err != nil {
			return 0, err
		}
		return time.Since(start), nil
	}
	if err := c.exporter.post(ctx, "ping", []byte("[]"), "application/json", false); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion always returns an UNSUPPORTED error: Better Stack is a SaaS and
// exposes no service version (the query host's ClickHouse version is not one).
func (c *Client) ServerVersion(context.Context) (string, error) {
	return "", UnsupportedError("serverVersion", "Better Stack exposes no server version")
}

// Exporter returns the LogExporter that posts batches to the ingesting host with the
// configured Encoding. It is nil without ingest credentials. Most users call
// NewLogClient instead.
func (c *Client) Exporter() *IngestExporter { return c.exporter }

// OTLPExporter returns the LogExporter that sends OTLP/HTTP protobuf to
// {url}/v1/logs on the ingesting host with the source token. It is nil without
// ingest credentials. Use it with NewLogClientWith.
func (c *Client) OTLPExporter() *OTLPLogExporter { return c.otlp }

// Query returns the SQL query client (nil without query credentials).
func (c *Client) Query() *Query { return c.sql }

// NewLogClient starts a batching LogClient that sends to the ingesting host with the
// configured Encoding. Batch limits are capped at the ingest limits (MaxRequestBytes
// per request). It is a CONFIG error without ingest credentials. Client.Close
// flushes and closes it.
func (c *Client) NewLogClient(opts LogClientOptions) (*BatchingLogClient, error) {
	if err := c.needIngest("newLogClient"); err != nil {
		return nil, err
	}
	return c.NewLogClientWith(c.exporter, opts)
}

// NewLogClientWith starts a batching LogClient over exporter (for example
// OTLPExporter()). Client.Close flushes and closes it.
func (c *Client) NewLogClientWith(exporter LogExporter, opts LogClientOptions) (*BatchingLogClient, error) {
	if exporter == nil {
		return nil, ConfigError("newLogClient needs an exporter (ingest credentials missing)")
	}
	if opts.Batch.MaxBatchBytes > MaxRequestBytes {
		opts.Batch.MaxBatchBytes = MaxRequestBytes
	}
	lc, err := NewBatchingLogClient(exporter, opts)
	if err != nil {
		return nil, err
	}
	if err := c.register(lc.Close); err != nil {
		_ = lc.Close(context.Background())
		return nil, err
	}
	return lc, nil
}

// register adds a closer run by Close; it fails with CANCELLED after Close.
func (c *Client) register(fn func(ctx context.Context) error) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return &Error{Code: ErrorCodeCancelled, Message: "client is closed"}
	}
	c.closers = append(c.closers, fn)
	return nil
}

// Close flushes and closes every LogClient made by NewLogClient / NewLogClientWith,
// stops running tails and releases connections. It is idempotent and returns the
// first flush error.
func (c *Client) Close() error {
	c.once.Do(func() {
		c.mu.Lock()
		c.closed = true
		closers := c.closers
		c.closers = nil
		c.mu.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), c.cfg.TotalTimeout)
		defer cancel()
		for _, fn := range closers {
			if err := fn(ctx); err != nil && c.closeErr == nil {
				c.closeErr = err
			}
		}
		if c.ingest != nil {
			c.ingest.close()
		}
		if c.query != nil {
			c.query.close()
		}
	})
	return c.closeErr
}
