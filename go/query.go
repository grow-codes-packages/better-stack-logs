package betterstack

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Query defaults.
const (
	// DefaultSearchWindow is the time window of SearchLogs / Search when no start is given.
	DefaultSearchWindow = 24 * time.Hour
	// MaxSearchLimit caps SearchLogs / Search page sizes.
	MaxSearchLimit = 10000
	// DefaultTailInterval is the poll interval of Tail (Better Stack suggests 1–2 s
	// between queries).
	DefaultTailInterval = time.Second
	// tailPageLimit is the page size of one Tail poll.
	tailPageLimit = 1000
)

// Query is the Better Stack SQL query client (the ClickHouse HTTP interface of the
// query host, HTTP Basic credentials of a connection). It implements LogQueryClient
// over the configured SourceTable. Get it from Client.Query; it is safe for
// concurrent use. Better Stack allows 4 concurrent log queries per account.
type Query struct {
	client *Client
}

var _ LogQueryClient = (*Query)(nil)

// Row is one result row of Query, keyed by column name. JSON numbers become int64
// (when integral) or float64; ClickHouse renders 64-bit integers as strings.
type Row = map[string]any

// QueryOptions are optional settings of Query and QueryRaw.
type QueryOptions struct {
	// Settings are ClickHouse settings sent as URL parameters (for example
	// {"max_execution_time": 30}).
	Settings map[string]any
}

var formatClauseRe = regexp.MustCompile(`(?is)\bFORMAT\s+([A-Za-z][A-Za-z0-9_]*)\s*$`)

// jsonFormats are the FORMAT names Query parses into rows.
var jsonFormats = map[string]bool{"jsoneachrow": true, "json": true, "jsonstringseachrow": true, "ndjson": true, "jsonlines": true}

// Query runs sql with params bound client side (see BindParams) and returns the rows.
// Without a FORMAT clause "FORMAT JSONEachRow" is appended; FORMAT JSONEachRow / JSON
// (also with output_format_json_array_of_rows) are parsed, other formats are a
// VALIDATION error (use QueryRaw). ClickHouse errors are mapped by their exception code
// (516 → AUTHENTICATION, 497 → PERMISSION_DENIED, 60/81 → NOT_FOUND, 62/47/… →
// VALIDATION, 202 → RATE_LIMITED, 159/160 → TIMEOUT, 48 → UNSUPPORTED), also when the
// error follows a 200 answer.
func (q *Query) Query(ctx context.Context, sql string, params map[string]any, opts ...QueryOptions) ([]Row, error) {
	return q.rows(ctx, "query", sql, params, opts...)
}

func (q *Query) rows(ctx context.Context, op, sql string, params map[string]any, opts ...QueryOptions) ([]Row, error) {
	trimmed := strings.TrimSpace(strings.TrimRight(strings.TrimSpace(sql), ";"))
	if m := formatClauseRe.FindStringSubmatch(trimmed); m != nil {
		if !jsonFormats[strings.ToLower(m[1])] {
			return nil, ValidationError(op, "FORMAT "+m[1]+" cannot be parsed into rows; use QueryRaw or FORMAT JSONEachRow")
		}
	} else if trimmed != "" {
		trimmed += " FORMAT JSONEachRow"
	}
	res, err := q.raw(ctx, op, trimmed, params, opts...)
	if err != nil {
		return nil, err
	}
	return parseRows(op, res)
}

// QueryRaw runs sql with params bound client side and returns the response body
// unchanged (any FORMAT). Errors are mapped like Query's.
func (q *Query) QueryRaw(ctx context.Context, sql string, params map[string]any, opts ...QueryOptions) (string, error) {
	res, err := q.raw(ctx, "query", sql, params, opts...)
	if err != nil {
		return "", err
	}
	return string(res.Body), nil
}

func (q *Query) raw(ctx context.Context, op, sql string, params map[string]any, opts ...QueryOptions) (*response, error) {
	if err := q.client.needQuery(op); err != nil {
		return nil, err
	}
	if strings.TrimSpace(sql) == "" {
		return nil, ValidationError(op, "sql must not be empty")
	}
	bound, err := BindParams(sql, params)
	if err != nil {
		return nil, err
	}
	var query url.Values
	for _, o := range opts {
		for k, v := range o.Settings {
			if !IsValidIdentifier(k) {
				return nil, ValidationError(op, "invalid setting name "+strconv.Quote(k))
			}
			if query == nil {
				query = url.Values{}
			}
			query.Set(k, fmt.Sprint(v))
		}
	}
	cctx, capt := withCapture(ctx)
	res, err := q.client.query.do(cctx, &request{
		Method: http.MethodPost, Path: "/", Query: query, Header: q.client.queryHeader(),
		Body: []byte(bound), ContentType: "text/plain; charset=utf-8", Idempotent: true, Operation: op,
	})
	if err != nil {
		return nil, remapQueryError(err, capt)
	}
	return res, nil
}

// streamError builds the error for "Code: N. DB::Exception: …" in a 200 body. Unknown
// codes are UNAVAILABLE and not retryable.
func streamError(op string, res *response, line string) *Error {
	code := CodeForClickHouse(ClickHouseErrorCode(nil, []byte(line)))
	retryable := code.Retryable()
	if code == "" {
		code, retryable = ErrorCodeUnavailable, false
	}
	text := truncate(strings.TrimSpace(line), maxVendorText)
	return &Error{
		Code: code, Message: fmt.Sprintf("%s: %s (HTTP %d)", op, text, res.Status),
		HTTPStatus: res.Status, RequestID: res.RequestID, Retryable: retryable,
	}
}

// parseRows parses a JSONEachRow, JSON-array or FORMAT JSON body into rows.
func parseRows(op string, res *response) ([]Row, error) {
	body := bytes.TrimSpace(res.Body)
	rows := []Row{}
	if len(body) == 0 {
		return rows, nil
	}
	if m := clickHouseCodeRe.FindIndex(body); m != nil {
		line, _, _ := bytes.Cut(body[m[0]:], []byte("\n"))
		return nil, streamError(op, res, string(line))
	}
	switch body[0] {
	case '[': // SETTINGS output_format_json_array_of_rows = 1
		var arr []json.RawMessage
		if err := json.Unmarshal(body, &arr); err != nil {
			return nil, invalidResponse(op, res)
		}
		return appendRows(op, res, rows, arr)
	case '{':
		var doc struct {
			Meta json.RawMessage   `json:"meta"`
			Data []json.RawMessage `json:"data"`
		}
		if !bytes.Contains(body, []byte("\n{")) && json.Unmarshal(body, &doc) == nil && doc.Meta != nil {
			return appendRows(op, res, rows, doc.Data) // FORMAT JSON
		}
	}
	var lines []json.RawMessage
	for _, line := range bytes.Split(body, []byte("\n")) {
		if line = bytes.TrimSpace(line); len(line) > 0 {
			lines = append(lines, line)
		}
	}
	return appendRows(op, res, rows, lines)
}

func appendRows(op string, res *response, rows []Row, items []json.RawMessage) ([]Row, error) {
	for _, raw := range items {
		r, err := decodeRow(raw)
		if err != nil {
			return nil, invalidResponse(op, res)
		}
		rows = append(rows, r)
	}
	return rows, nil
}

func invalidResponse(op string, res *response) *Error {
	return &Error{Code: ErrorCodeUnavailable, Message: op + ": invalid response", HTTPStatus: res.Status, RequestID: res.RequestID}
}

// decodeRow decodes one JSON object with numbers converted to int64 / float64.
func decodeRow(raw []byte) (Row, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	if m == nil {
		return nil, errors.New("not an object")
	}
	return convertNumbers(m).(map[string]any), nil
}

func convertNumbers(v any) any {
	switch x := v.(type) {
	case json.Number:
		if i, err := x.Int64(); err == nil {
			return i
		}
		f, _ := x.Float64()
		return f
	case map[string]any:
		for k, e := range x {
			x[k] = convertNumbers(e)
		}
		return x
	case []any:
		for i, e := range x {
			x[i] = convertNumbers(e)
		}
		return x
	}
	return v
}

// SearchLogsOptions configure SearchLogs.
type SearchLogsOptions struct {
	// Source is the collection (<team>_<table>, for example t1234_myapp); default
	// Config.SourceTable. It must match ^[A-Za-z_][A-Za-z0-9_]*$.
	Source string
	// Contains is case-sensitive literal text searched in the raw event JSON. It is
	// JSON-escaped first (quote, backslash, control characters), so it matches text
	// inside string values. Empty matches everything.
	Contains string
	// Start is the inclusive window start (default: DefaultSearchWindow before End, or
	// before now).
	Start time.Time
	// End is the inclusive window end (default: no upper bound, robust to clock skew
	// and ingest delay).
	End time.Time
	// Limit is the page size, 1..MaxSearchLimit (0 = DefaultSearchLimit).
	Limit int
	// Order is the dt order (default OrderDesc, newest first).
	Order SortOrder
	// Cursor is the opaque NextCursor of a previous page.
	Cursor string
}

// JSONEscape escapes text like a JSON string value without the surrounding quotes
// (quote, backslash and control characters; HTML characters are kept).
func JSONEscape(text string) string {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(text)
	out := bytes.TrimSpace(b.Bytes())
	return string(out[1 : len(out)-1])
}

// RenderSearchSQL renders the SQL of SearchLogs: hot remote(<source>_logs) UNION ALL
// cold s3Cluster(primary, <source>_s3) WHERE _row_type = 1, the inclusive dt window,
// position(raw, <JSON-escaped contains>) > 0, ORDER BY dt, LIMIT <limit+1> (to detect a
// next page) and OFFSET from the cursor, FORMAT JSONEachRow. Values are escaped literals.
func (q *Query) RenderSearchSQL(opts SearchLogsOptions) (string, error) {
	return renderSearchSQL(opts, q.client.cfg.SourceTable, time.Now())
}

func renderSearchSQL(opts SearchLogsOptions, defaultSource string, now time.Time) (string, error) {
	const op = "logs.search"
	source := opts.Source
	if source == "" {
		source = defaultSource
	}
	if source == "" {
		return "", ConfigError("searchLogs needs a source or a configured sourceTable (GROW_BETTER_STACK_LOGS_SOURCE_TABLE)")
	}
	if !IsValidIdentifier(source) {
		return "", ValidationError(op, "source must match ^[A-Za-z_][A-Za-z0-9_]*$")
	}
	limit := opts.Limit
	if limit == 0 {
		limit = DefaultSearchLimit
	}
	if limit < 1 || limit > MaxSearchLimit {
		return "", ValidationError(op, fmt.Sprintf("limit must be an integer between 1 and %d", MaxSearchLimit))
	}
	order := "DESC"
	switch opts.Order {
	case "", OrderDesc:
	case OrderAsc:
		order = "ASC"
	default:
		return "", ValidationError(op, `order must be "asc" or "desc"`)
	}
	start := opts.Start
	if start.IsZero() {
		base := now
		if !opts.End.IsZero() {
			base = opts.End
		}
		start = base.Add(-DefaultSearchWindow)
	}
	if !opts.End.IsZero() && start.After(opts.End) {
		return "", ValidationError(op, "start must not be after end")
	}
	offset, err := decodeCursor(opts.Cursor)
	if err != nil {
		return "", err
	}
	ts := func(t time.Time) string { return "toDateTime64(" + QuoteSQLString(FormatSQLTime(t)) + ", 6, 'UTC')" }
	var b strings.Builder
	fmt.Fprintf(&b, "SELECT dt, raw FROM (SELECT dt, raw FROM remote(%s_logs) UNION ALL SELECT dt, raw FROM s3Cluster(primary, %s_s3) WHERE _row_type = 1) WHERE ", source, source)
	if opts.End.IsZero() {
		fmt.Fprintf(&b, "dt >= %s", ts(start))
	} else {
		fmt.Fprintf(&b, "dt BETWEEN %s AND %s", ts(start), ts(opts.End))
	}
	if opts.Contains != "" {
		fmt.Fprintf(&b, " AND position(raw, %s) > 0", QuoteSQLString(JSONEscape(opts.Contains)))
	}
	fmt.Fprintf(&b, " ORDER BY dt %s LIMIT %d", order, limit+1)
	if offset > 0 {
		fmt.Fprintf(&b, " OFFSET %d", offset)
	}
	b.WriteString(" FORMAT JSONEachRow")
	return b.String(), nil
}

// searchRows runs the search SQL and returns the de-duplicated rows of one page and
// whether more rows exist.
func (q *Query) searchRows(ctx context.Context, opts SearchLogsOptions) ([]Row, bool, error) {
	const op = "logs.search"
	if err := q.client.needQuery(op); err != nil {
		return nil, false, err
	}
	sql, err := q.RenderSearchSQL(opts)
	if err != nil {
		return nil, false, err
	}
	rows, err := q.rows(ctx, op, sql, nil)
	if err != nil {
		return nil, false, err
	}
	limit := opts.Limit
	if limit == 0 {
		limit = DefaultSearchLimit
	}
	more := len(rows) > limit
	if more {
		rows = rows[:limit]
	}
	out := make([]Row, 0, len(rows))
	seen := make(map[string]bool, len(rows))
	for _, r := range rows {
		key := fmt.Sprint(r["dt"]) + "\x00" + fmt.Sprint(r["raw"])
		if !seen[key] {
			seen[key] = true
			out = append(out, r)
		}
	}
	return out, more, nil
}

// SearchLogs searches the hot and cold logs of a collection (see RenderSearchSQL),
// with an inclusive time window and a literal substring match, newest first by default.
// Rows are normalised (RecordFromRow) and de-duplicated on (dt, raw) where hot and cold
// overlap; NextCursor is set when more rows exist.
func (q *Query) SearchLogs(ctx context.Context, opts SearchLogsOptions) (LogSearchResult, error) {
	rows, more, err := q.searchRows(ctx, opts)
	if err != nil {
		return LogSearchResult{}, err
	}
	res := LogSearchResult{Records: make([]LogRecord, 0, len(rows))}
	for _, r := range rows {
		res.Records = append(res.Records, RecordFromRow(r))
	}
	if more {
		offset, _ := decodeCursor(opts.Cursor)
		limit := opts.Limit
		if limit == 0 {
			limit = DefaultSearchLimit
		}
		res.NextCursor = encodeCursor(offset + limit)
	}
	return res, nil
}

// ParseDateTime parses a dt value: "YYYY-MM-DD hh:mm:ss[.f…]" (0–9 fraction digits,
// UTC) or RFC 3339.
func ParseDateTime(s string) (time.Time, error) {
	if t, err := time.ParseInLocation("2006-01-02 15:04:05.999999999", s, time.UTC); err == nil {
		return t, nil
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, err
	}
	return t.UTC(), nil
}

// topLevelKeys are the event keys mapped to LogRecord fields by RecordFromRow.
var topLevelKeys = map[string]bool{
	"dt": true, "message": true, "msg": true, "level": true, "severity": true, "severity_number": true,
	"span": true, "trace_id": true, "span_id": true, "trace_flags": true, "resource": true,
	"attributes": true, "observed_dt": true,
}

// hexID returns v lower-cased when it is a non-zero hex id of n digits, else "".
func hexID(v any, n int) string {
	s, ok := v.(string)
	if !ok {
		return ""
	}
	s = strings.ToLower(strings.TrimSpace(s))
	if len(s) != n || strings.Trim(s, "0") == "" || strings.Trim(s, "0123456789abcdef") != "" {
		return ""
	}
	return s
}

// RecordFromRow normalises a {dt, raw} row to a LogRecord: Body = message (else msg,
// else the raw text); Severity from severity_number, else level / severity text (default
// INFO); SeverityText is the canonical name when the text is a known severity;
// Timestamp = dt (else the event's dt); TraceID / SpanID from span.trace_id /
// span.span_id (else trace_id / span_id, hex only); Resource = resource; Attributes =
// every other key plus the attributes object.
func RecordFromRow(row Row) LogRecord {
	rawText, ok := row["raw"].(string)
	if !ok {
		b, _ := json.Marshal(row["raw"])
		rawText = string(b)
	}
	ev, err := decodeRow([]byte(rawText))
	if err != nil {
		ev = map[string]any{}
	}
	rec := LogRecord{Attributes: map[string]AttrValue{}, Resource: map[string]AttrValue{}, Body: rawText}
	for _, v := range []any{row["dt"], ev["dt"]} {
		if s, ok := v.(string); ok {
			if t, err := ParseDateTime(s); err == nil {
				rec.Timestamp = t
				break
			}
		}
	}
	if v, ok := ev["message"]; ok && v != nil {
		rec.Body = textOf(v)
	} else if v, ok := ev["msg"]; ok && v != nil {
		rec.Body = textOf(v)
	}
	levelText := ""
	for _, k := range []string{"level", "severity"} {
		if s, ok := ev[k].(string); ok && s != "" {
			levelText = s
			break
		}
	}
	fromText, textOK := ParseSeverity(levelText)
	switch n := ev["severity_number"].(type) {
	case int64:
		if n >= 1 && n <= 24 {
			rec.Severity = Severity(n)
		}
	case string:
		if s, ok := ParseSeverity(n); ok {
			rec.Severity = s
		}
	}
	if rec.Severity == SeverityUnspecified {
		rec.Severity = SeverityInfo
		if textOK {
			rec.Severity = fromText
		}
	}
	switch {
	case textOK:
		rec.SeverityText = fromText.String()
	case levelText != "":
		rec.SeverityText = levelText
	default:
		rec.SeverityText = rec.Severity.String()
	}
	if s, ok := ev["observed_dt"].(string); ok {
		if t, err := ParseDateTime(s); err == nil {
			rec.ObservedTimestamp = t
		}
	}
	span, _ := ev["span"].(map[string]any)
	first := func(a, b any) any {
		if a != nil {
			return a
		}
		return b
	}
	if rec.TraceID = hexID(first(span["trace_id"], ev["trace_id"]), 32); rec.TraceID != "" {
		rec.SpanID = hexID(first(span["span_id"], ev["span_id"]), 16)
		if f, ok := ev["trace_flags"].(int64); ok && f >= 0 && f <= 255 {
			u := uint8(f)
			rec.TraceFlags = &u
		}
	}
	if res, ok := ev["resource"].(map[string]any); ok {
		rec.Resource = NormalizeAttributes(res)
	}
	for k, v := range ev {
		if !topLevelKeys[k] {
			if n := NormalizeAttrValue(v); n != nil {
				rec.Attributes[k] = n
			}
		}
	}
	if attrs, ok := ev["attributes"].(map[string]any); ok {
		for k, v := range NormalizeAttributes(attrs) {
			rec.Attributes[k] = v
		}
	}
	return rec
}

func textOf(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	b, _ := json.Marshal(v)
	return string(b)
}

// Search implements LogQueryClient.Search over Config.SourceTable: query is the
// SearchLogs Contains text.
func (q *Query) Search(ctx context.Context, query string, opts SearchOptions) (LogSearchResult, error) {
	return q.SearchLogs(ctx, SearchLogsOptions{
		Contains: query, Start: opts.Start, End: opts.End, Limit: opts.Limit, Order: opts.Order, Cursor: opts.Cursor,
	})
}

type cursor struct {
	O int `json:"o"`
}

func encodeCursor(offset int) string {
	b, _ := json.Marshal(cursor{O: offset})
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(c string) (int, error) {
	if c == "" {
		return 0, nil
	}
	if b, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(c, "=")); err == nil {
		var v struct {
			O *int `json:"o"`
		}
		if json.Unmarshal(b, &v) == nil && v.O != nil && *v.O >= 0 {
			return *v.O, nil
		}
	}
	return 0, ValidationError("logs.search", "invalid cursor")
}

// Tail polls SearchLogs every opts.Interval (default DefaultTailInterval) for new rows
// of Config.SourceTable that contain query, starting at opts.Start (default now), and
// calls onRecord for each new record in dt order. Rows are de-duplicated at the
// polling boundary. Polling errors are skipped (the next poll retries). The tail ends
// when the handle is stopped, ctx is done or the Client is closed.
func (q *Query) Tail(ctx context.Context, query string, onRecord func(LogRecord), opts TailOptions) (TailHandle, error) {
	const op = "logs.tail"
	if err := q.client.needQuery(op); err != nil {
		return nil, err
	}
	if q.client.cfg.SourceTable == "" {
		return nil, ConfigError("tail needs Config.SourceTable")
	}
	if onRecord == nil {
		return nil, ValidationError(op, "onRecord is required")
	}
	interval := opts.Interval
	if interval <= 0 {
		interval = DefaultTailInterval
	}
	start := opts.Start
	if start.IsZero() {
		start = time.Now()
	}
	tctx, cancel := context.WithCancel(ctx)
	h := &pollTail{cancel: cancel, done: make(chan struct{})}
	if err := q.client.register(func(context.Context) error { h.Stop(); return nil }); err != nil {
		cancel()
		return nil, err
	}
	go h.run(tctx, q, query, start, interval, onRecord)
	return h, nil
}

type pollTail struct {
	cancel context.CancelFunc
	done   chan struct{}
	once   sync.Once
}

// Stop ends the tail and waits for the poller to finish. It is idempotent.
func (h *pollTail) Stop() {
	h.once.Do(h.cancel)
	<-h.done
}

func (h *pollTail) run(ctx context.Context, q *Query, query string, cursor time.Time, interval time.Duration, onRecord func(LogRecord)) {
	defer close(h.done)
	seen := map[string]bool{}
	for {
		rows, _, err := q.searchRows(ctx, SearchLogsOptions{Contains: query, Start: cursor, Order: OrderAsc, Limit: tailPageLimit})
		if err == nil {
			for _, r := range rows {
				dtText, _ := r["dt"].(string)
				raw := fmt.Sprint(r["raw"])
				dt, perr := ParseDateTime(dtText)
				if perr != nil || dt.Before(cursor) {
					continue
				}
				if dt.After(cursor) {
					cursor = dt
					seen = map[string]bool{}
				}
				if seen[raw] {
					continue
				}
				seen[raw] = true
				func() {
					defer func() { _ = recover() }()
					onRecord(RecordFromRow(r))
				}()
			}
		}
		if sleepContext(ctx, interval) != nil {
			return
		}
	}
}

// ListStreams returns the configured collections: Config.SourceTable when set
// (Better Stack's data plane has no listing endpoint; collections are listed by the
// Telemetry management API, which needs a team token).
func (q *Query) ListStreams(context.Context) ([]string, error) {
	if q.client.cfg.SourceTable == "" {
		return []string{}, nil
	}
	return []string{q.client.cfg.SourceTable}, nil
}

// ListLabels returns an UNSUPPORTED error: Better Stack events are schemaless JSON
// without labels.
func (q *Query) ListLabels(context.Context) ([]string, error) {
	return nil, UnsupportedError("logs.listLabels", "Better Stack events have no labels")
}

// WaitForLog polls Search until a record contains query (and matches opts.Predicate);
// TIMEOUT when nothing matches within opts.Timeout (default 30 s, interval 1 s).
func (q *Query) WaitForLog(ctx context.Context, query string, opts WaitForLogOptions) (LogRecord, error) {
	return WaitForLog(ctx, q, query, opts)
}
