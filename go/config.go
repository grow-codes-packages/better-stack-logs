package betterstack

import (
	"fmt"
	"regexp"
	"strings"
)

// DefaultURL is empty on purpose: Better Stack has no global ingest host. Every
// source has its own ingesting host (for example https://s1234.eu-nbg-2.betterstackdata.com),
// so Config.URL must be set whenever the ingest API is used.
const DefaultURL = ""

// Encoding is the wire encoding of ingest requests.
type Encoding string

// Ingest encodings accepted by Better Stack's HTTP ingest API.
const (
	// EncodingJSON sends one JSON array per request (Content-Type application/json). Default.
	EncodingJSON Encoding = "json"
	// EncodingNDJSON sends one JSON object per line (Content-Type application/x-ndjson).
	EncodingNDJSON Encoding = "ndjson"
	// EncodingMsgPack sends one MessagePack array per request (Content-Type
	// application/msgpack); dt is a MessagePack timestamp extension.
	EncodingMsgPack Encoding = "msgpack"
)

// vendorEnv lists the vendor variables consulted after GROW_BETTER_STACK_LOGS_* for
// the common fields (the ingest URL).
var vendorEnv = VendorEnv{URL: []string{"BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST"}}

// Vendor variables of the Better Stack specific fields, in precedence order.
var (
	vendorSourceTokenEnv   = []string{"BETTERSTACK_SOURCE_TOKEN"}
	vendorQueryURLEnv      = []string{"BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST"}
	vendorQueryUsernameEnv = []string{"BETTERSTACK_QUERY_USERNAME"}
	vendorQueryPasswordEnv = []string{"BETTERSTACK_QUERY_PASSWORD"}
	vendorSourceTableEnv   = []string{"BETTERSTACK_SOURCE_TABLE"}
)

var identifierRe = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// IsValidIdentifier reports whether s is a safe ClickHouse identifier
// (^[A-Za-z_][A-Za-z0-9_]*$), the rule for source tables (collections).
func IsValidIdentifier(s string) bool { return identifierRe.MatchString(s) }

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// Better Stack has two hosts with separate credentials: the source's ingesting host
// (CommonConfig.URL + SourceToken, sent as "Authorization: Bearer") and the SQL query
// host (QueryURL + QueryUsername + QueryPassword, sent as HTTP Basic). Each set is
// all-or-nothing; a client needs at least one of them. Credentials of one host are
// never sent to the other.
//
// URLs may be given as full base URLs (http://better-stack-logs:8080) or as a bare
// host[:port] (s1234.eu-nbg-2.betterstackdata.com), which becomes https://host[:port].
type Config struct {
	CommonConfig

	// SourceToken is the source token of the ingesting host (env
	// GROW_BETTER_STACK_LOGS_SOURCE_TOKEN, then BETTERSTACK_SOURCE_TOKEN).
	// CommonConfig.URL is the ingesting host (env GROW_BETTER_STACK_LOGS_URL, then
	// BETTERSTACK_INGESTING_URL, BETTERSTACK_INGESTING_HOST).
	SourceToken Secret
	// QueryURL is the SQL query host, for example https://eu-nbg-2-connect.betterstackdata.com
	// (env GROW_BETTER_STACK_LOGS_QUERY_URL, then BETTERSTACK_QUERY_URL, BETTERSTACK_QUERY_HOST).
	QueryURL string
	// QueryUsername is the username of the query connection (env
	// GROW_BETTER_STACK_LOGS_QUERY_USERNAME, then BETTERSTACK_QUERY_USERNAME).
	QueryUsername string
	// QueryPassword is the password of the query connection (env
	// GROW_BETTER_STACK_LOGS_QUERY_PASSWORD, then BETTERSTACK_QUERY_PASSWORD).
	QueryPassword Secret
	// SourceTable is the default collection (<team>_<table>, for example t1234_myapp) used
	// by SearchLogs, Search, Tail and Ping (env GROW_BETTER_STACK_LOGS_SOURCE_TABLE, then
	// BETTERSTACK_SOURCE_TABLE).
	SourceTable string
	// Encoding is the ingest wire encoding (default EncodingJSON; env
	// GROW_BETTER_STACK_LOGS_ENCODING).
	Encoding Encoding
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_BETTER_STACK_LOGS_* > vendor
// variables (BETTERSTACK_INGESTING_URL, BETTERSTACK_INGESTING_HOST,
// BETTERSTACK_SOURCE_TOKEN, BETTERSTACK_QUERY_URL, BETTERSTACK_QUERY_HOST,
// BETTERSTACK_QUERY_USERNAME, BETTERSTACK_QUERY_PASSWORD, BETTERSTACK_SOURCE_TABLE) >
// defaults. env replaces the process environment when non-nil. The result is validated.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	setSecret := func(dst *Secret, name string, vendor []string) {
		if dst.IsZero() {
			if v, ok := r.String(name, vendor...); ok {
				*dst = NewSecret(v)
			}
		}
	}
	setString := func(dst *string, name string, vendor []string) {
		if *dst == "" {
			if v, ok := r.String(name, vendor...); ok {
				*dst = strings.TrimSpace(v)
			}
		}
	}
	setSecret(&c.SourceToken, "SOURCE_TOKEN", vendorSourceTokenEnv)
	setString(&c.QueryURL, "QUERY_URL", vendorQueryURLEnv)
	setString(&c.QueryUsername, "QUERY_USERNAME", vendorQueryUsernameEnv)
	setSecret(&c.QueryPassword, "QUERY_PASSWORD", vendorQueryPasswordEnv)
	setString(&c.SourceTable, "SOURCE_TABLE", vendorSourceTableEnv)
	if c.Encoding == "" {
		if v, ok := r.String("ENCODING"); ok {
			c.Encoding = Encoding(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	c.URL = NormalizeBaseURL(c.URL)
	c.QueryURL = NormalizeBaseURL(c.QueryURL)
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// NormalizeBaseURL turns a bare host[:port] into https://host[:port] and strips
// trailing slashes. A value that contains "://" keeps its scheme. Empty stays empty.
func NormalizeBaseURL(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	return strings.TrimRight(s, "/")
}

// HasIngest reports whether the ingest credentials (URL and SourceToken) are set.
func (c Config) HasIngest() bool { return c.URL != "" && !c.SourceToken.IsZero() }

// HasQuery reports whether the query credentials (QueryURL, QueryUsername and
// QueryPassword) are set.
func (c Config) HasQuery() bool {
	return c.QueryURL != "" && c.QueryUsername != "" && !c.QueryPassword.IsZero()
}

// encoding returns Encoding or EncodingJSON.
func (c Config) encoding() Encoding {
	if c.Encoding == "" {
		return EncodingJSON
	}
	return c.Encoding
}

// common returns the defaulted common config for one base URL.
func (c Config) common(baseURL string) CommonConfig {
	cc := c.CommonConfig
	cc.URL = NormalizeBaseURL(baseURL)
	return cc.withDefaults(cc.URL)
}

// Validate checks the configuration and returns a CONFIG error: the ingest set (URL +
// SourceToken) and the query set (QueryURL + QueryUsername + QueryPassword) are each
// all-or-nothing and at least one is complete; URLs are absolute http(s) URLs;
// SourceTable is an identifier; Encoding is known; the common rules hold.
func (c Config) Validate() error {
	ingestAny := c.URL != "" || !c.SourceToken.IsZero()
	queryAny := c.QueryURL != "" || c.QueryUsername != "" || !c.QueryPassword.IsZero()
	switch {
	case ingestAny && !c.HasIngest():
		return ConfigError("url and sourceToken must be given together (GROW_BETTER_STACK_LOGS_URL, GROW_BETTER_STACK_LOGS_SOURCE_TOKEN)")
	case queryAny && !c.HasQuery():
		return ConfigError("queryUrl, queryUsername and queryPassword must be given together (GROW_BETTER_STACK_LOGS_QUERY_URL, _QUERY_USERNAME, _QUERY_PASSWORD)")
	case !c.HasIngest() && !c.HasQuery():
		return ConfigError("url and sourceToken (ingest) or queryUrl, queryUsername and queryPassword (query) are required")
	}
	if c.HasIngest() {
		if err := c.common(c.URL).validate(); err != nil {
			return err
		}
	}
	if c.HasQuery() {
		if err := c.common(c.QueryURL).validate(); err != nil {
			return ConfigError("query " + strings.TrimPrefix(err.Error(), "config: "))
		}
	}
	if c.SourceTable != "" && !IsValidIdentifier(c.SourceTable) {
		return ConfigError("sourceTable must match ^[A-Za-z_][A-Za-z0-9_]*$")
	}
	switch c.encoding() {
	case EncodingJSON, EncodingNDJSON, EncodingMsgPack:
	default:
		return ConfigError("encoding must be json, ndjson or msgpack")
	}
	return nil
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	var b strings.Builder
	fmt.Fprintf(&b, "Config{%s", c.describe())
	if c.QueryURL != "" {
		fmt.Fprintf(&b, " QueryURL:%s", RedactURL(c.QueryURL))
	}
	if c.QueryUsername != "" {
		fmt.Fprintf(&b, " QueryUsername:%s", c.QueryUsername)
	}
	if c.SourceTable != "" {
		fmt.Fprintf(&b, " SourceTable:%s", c.SourceTable)
	}
	if c.Encoding != "" {
		fmt.Fprintf(&b, " Encoding:%s", c.Encoding)
	}
	fmt.Fprintf(&b, " SourceToken:%s QueryPassword:%s}", c.SourceToken, c.QueryPassword)
	return b.String()
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }
