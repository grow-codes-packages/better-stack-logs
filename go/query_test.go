package betterstack

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestQueryRows(t *testing.T) {
	cases := []struct {
		fixture string
		sql     string
		check   func(t *testing.T, rows []Row)
	}{
		{"query.json_each_row", "SELECT dt, raw FROM remote(t1_grow_sdk_logs)", func(t *testing.T, rows []Row) {
			if len(rows) != 11 || rows[0]["dt"] != "2023-08-09 07:03:30.000000" {
				t.Fatalf("rows %v", rows)
			}
		}},
		{"query.json_format", "SELECT count() AS c FROM remote(t1_grow_sdk_logs) FORMAT JSON", func(t *testing.T, rows []Row) {
			if len(rows) != 1 || rows[0]["c"] != "11" {
				t.Fatalf("rows %v", rows)
			}
		}},
		{"query.array_of_rows", "SELECT 1 SETTINGS output_format_json_array_of_rows = 1 FORMAT JSONEachRow", func(t *testing.T, rows []Row) {
			if len(rows) == 0 {
				t.Fatalf("rows %v", rows)
			}
		}},
		{"query.json_extract", "SELECT JSONExtractString(raw, 'message') AS message FROM remote(t1_grow_sdk_logs);", func(t *testing.T, rows []Row) {
			if len(rows) == 0 || rows[0]["message"] == nil {
				t.Fatalf("rows %v", rows)
			}
		}},
		{"searchLogs.empty", "SELECT dt FROM remote(t1_grow_sdk_logs)", func(t *testing.T, rows []Row) {
			if rows == nil || len(rows) != 0 {
				t.Fatalf("rows %v", rows)
			}
		}},
	}
	for _, tc := range cases {
		t.Run(tc.fixture, func(t *testing.T) {
			srv := newSQLServer(t, func(w http.ResponseWriter, r *http.Request, _ string, _ int) {
				if r.Header.Get("Authorization") != basicAuth(testQueryUser, testQueryPassword) || r.Header.Get("Content-Type") != "text/plain; charset=utf-8" {
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				loadFixture(t, tc.fixture).serve(w)
			})
			c := newQueryClient(t, srv.URL, nil)
			rows, err := c.Query().Query(t.Context(), tc.sql, nil)
			if err != nil {
				t.Fatal(err)
			}
			tc.check(t, rows)
			sql := srv.sql(0)
			if strings.Count(sql, " FORMAT ") != 1 || strings.HasSuffix(sql, ";") {
				t.Fatalf("sql %q", sql)
			}
		})
	}

	t.Run("numbers and params", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			_, _ = w.Write([]byte("{\"i\":7,\"f\":1.5,\"a\":[1,{\"x\":2}]}\n\n"))
		})
		c := newQueryClient(t, srv.URL, nil)
		rows, err := c.Query().Query(t.Context(), "SELECT * FROM remote({t:Identifier}) WHERE raw = {needle:String} AND n > {n:Int64}", map[string]any{
			"t": "t1_grow_sdk_logs", "needle": "it's", "n": 3,
		})
		if err != nil {
			t.Fatal(err)
		}
		if rows[0]["i"] != int64(7) || rows[0]["f"] != 1.5 || rows[0]["a"].([]any)[1].(map[string]any)["x"] != int64(2) {
			t.Fatalf("rows %v", rows)
		}
		want := `SELECT * FROM remote(t1_grow_sdk_logs) WHERE raw = 'it\'s' AND n > 3 FORMAT JSONEachRow`
		if srv.sql(0) != want {
			t.Fatalf("sql %q", srv.sql(0))
		}
	})

	t.Run("client side validation", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) { w.WriteHeader(http.StatusOK) })
		q := newQueryClient(t, srv.URL, nil).Query()
		for _, sql := range []string{"  ; ", "SELECT 1 FORMAT CSV"} {
			if _, err := q.Query(t.Context(), sql, nil); !errors.Is(err, ErrValidation) {
				t.Errorf("%q: %v", sql, err)
			}
		}
		if _, err := q.Query(t.Context(), "SELECT {x:String}", nil); !errors.Is(err, ErrValidation) {
			t.Fatalf("missing param: %v", err)
		}
		if srv.calls.Load() != 0 {
			t.Fatal("nothing must be sent")
		}
	})

	t.Run("invalid responses", func(t *testing.T) {
		bodies := map[string]string{
			"not json lines": "hello\n",
			"broken object":  "{\"a\":\n",
			"broken array":   "[{\"a\":1}, 2]",
			"null row":       "null\n",
		}
		for name, body := range bodies {
			srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) { _, _ = w.Write([]byte(body)) })
			if _, err := newQueryClient(t, srv.URL, nil).Query().Query(t.Context(), "SELECT 1", nil); !errors.Is(err, ErrUnavailable) {
				t.Errorf("%s: %v", name, err)
			}
		}
		for name, body := range map[string]string{"json format": "nope", "json bad row": `{"meta":[],"data":[1]}`} {
			srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) { _, _ = w.Write([]byte(body)) })
			if _, err := newQueryClient(t, srv.URL, nil).Query().Query(t.Context(), "SELECT 1 FORMAT JSON", nil); !errors.Is(err, ErrUnavailable) {
				t.Errorf("%s: %v", name, err)
			}
		}
	})

	t.Run("error after 200", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			_, _ = w.Write([]byte("{\"a\":1}\nCode: 241. DB::Exception: Memory limit (total) exceeded. (MEMORY_LIMIT_EXCEEDED) (version 24.8)\n"))
		})
		_, err := newQueryClient(t, srv.URL, nil).Query().Query(t.Context(), "SELECT 1", nil)
		if e := asError(t, err); e.Code != ErrorCodeValidation || e.HTTPStatus != 200 || !strings.Contains(e.Message, "MEMORY_LIMIT_EXCEEDED") {
			t.Fatalf("err %+v", e)
		}
		srv2 := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			_, _ = w.Write([]byte("{\"meta\":[], \"data\":[\nCode: 999. DB::Exception: boom. (X)"))
		})
		_, err = newQueryClient(t, srv2.URL, nil).Query().Query(t.Context(), "SELECT 1 FORMAT JSON", nil)
		if e := asError(t, err); e.Code != ErrorCodeUnavailable || e.Retryable {
			t.Fatalf("err %+v", e)
		}
	})
}

func TestQueryErrors(t *testing.T) {
	cases := []struct {
		fixture string
		code    ErrorCode
	}{
		{"query.401_bad_password", ErrorCodeAuthentication},
		{"query.401_no_auth", ErrorCodeAuthentication},
		{"query.404_unknown_table", ErrorCodeNotFound},
		{"query.404_unknown_column", ErrorCodeValidation},
		{"query.400_syntax", ErrorCodeValidation},
		{"query.params_binding", ErrorCodeValidation},
		{"query.503_fault", ErrorCodeUnavailable},
	}
	for _, tc := range cases {
		t.Run(tc.fixture, func(t *testing.T) {
			srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) { loadFixture(t, tc.fixture).serve(w) })
			_, err := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(1) }).Query().Query(t.Context(), "SELECT 1", nil)
			e := asError(t, err)
			if e.Code != tc.code || strings.Contains(e.Error(), testQueryPassword) {
				t.Fatalf("err %+v", e)
			}
		})
	}

	t.Run("clickhouse code mapping", func(t *testing.T) {
		codes := map[int]ErrorCode{
			516: ErrorCodeAuthentication, 497: ErrorCodePermissionDenied, 81: ErrorCodeNotFound,
			48: ErrorCodeUnsupported, 159: ErrorCodeTimeout, 456: ErrorCodeValidation, 457: ErrorCodeValidation, 202: ErrorCodeRateLimited, 241: ErrorCodeValidation, 1: "",
		}
		for n, want := range codes {
			if got := CodeForClickHouse(n); got != want {
				t.Errorf("%d: %s", n, got)
			}
		}
		if ClickHouseErrorCode(http.Header{"X-Clickhouse-Exception-Code": {"60"}}, nil) != 60 ||
			ClickHouseErrorCode(nil, []byte("x\nCode: 47. DB")) != 47 || ClickHouseErrorCode(nil, []byte("none")) != 0 {
			t.Fatal("code parsing")
		}
	})

	t.Run("status and code remapping", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			w.Header().Set(HeaderClickHouseExceptionCode, "202")
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte("Code: 202. DB::Exception: Too many simultaneous queries. (TOO_MANY_SIMULTANEOUS_QUERIES)"))
		})
		_, err := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) }).Query().Query(t.Context(), "SELECT 1", nil)
		if e := asError(t, err); e.Code != ErrorCodeRateLimited || !e.Retryable {
			t.Fatalf("err %+v", e)
		}
		srv2 := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte("Code: 241. DB::Exception: Memory limit exceeded"))
		})
		_, err = newQueryClient(t, srv2.URL, nil).Query().Query(t.Context(), "SELECT 1", nil)
		if e := asError(t, err); e.Code != ErrorCodeValidation || !strings.Contains(e.Message, "narrow the time range") {
			t.Fatalf("err %+v", e)
		}
		plain := errors.New("plain")
		if remapQueryError(plain, &capture{}) != plain {
			t.Fatal("plain")
		}
		e := &Error{Code: ErrorCodeValidation, HTTPStatus: 400}
		if remapQueryError(e, &capture{status: 500}) != e {
			t.Fatal("status mismatch must keep the error")
		}
	})
}

func searchRowsHandler(t *testing.T) func(w http.ResponseWriter, r *http.Request, sql string, n int) {
	return func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
		loadFixture(t, "searchLogs.contains").serve(w)
	}
}

func TestSearchLogs(t *testing.T) {
	now := time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)

	t.Run("sql rendering", func(t *testing.T) {
		sql, err := renderSearchSQL(SearchLogsOptions{Source: "t1_x", Contains: `it's "q" \ here`, Limit: 5, Order: OrderAsc, Cursor: encodeCursor(10)}, "", now)
		if err != nil {
			t.Fatal(err)
		}
		want := `SELECT dt, raw FROM (SELECT dt, raw FROM remote(t1_x_logs) UNION ALL SELECT dt, raw FROM s3Cluster(primary, t1_x_s3) WHERE _row_type = 1) ` +
			`WHERE dt >= toDateTime64('2026-11-17 12:00:00.000000', 6, 'UTC') AND position(raw, 'it\'s \\"q\\" \\\\ here') > 0 ORDER BY dt ASC LIMIT 6 OFFSET 10 FORMAT JSONEachRow`
		if sql != want {
			t.Fatalf("sql\n%s\n%s", sql, want)
		}
		end := now.Add(-time.Hour)
		sql, _ = renderSearchSQL(SearchLogsOptions{End: end}, "t1_default", now)
		if !strings.Contains(sql, "dt BETWEEN toDateTime64('2026-11-17 11:00:00.000000', 6, 'UTC') AND toDateTime64('2026-11-18 11:00:00.000000', 6, 'UTC')") ||
			!strings.Contains(sql, "remote(t1_default_logs)") || !strings.Contains(sql, "ORDER BY dt DESC LIMIT 101 FORMAT") || strings.Contains(sql, "position") {
			t.Fatalf("sql %s", sql)
		}
		bad := []SearchLogsOptions{
			{Source: "t1; DROP TABLE x"},
			{Source: "t1", Limit: -1},
			{Source: "t1", Limit: MaxSearchLimit + 1},
			{Source: "t1", Start: now, End: now.Add(-time.Second)},
			{Source: "t1", Order: "sideways"},
			{Source: "t1", Cursor: "!!"},
		}
		for i, o := range bad {
			if _, err := renderSearchSQL(o, "", now); !errors.Is(err, ErrValidation) {
				t.Errorf("%d: %v", i, err)
			}
		}
		if _, err := renderSearchSQL(SearchLogsOptions{}, "", now); !errors.Is(err, ErrConfig) {
			t.Fatalf("no source: %v", err)
		}
		if JSONEscape("<a & \"b\">\n") != `<a & \"b\">\n` {
			t.Fatal(JSONEscape("<a & \"b\">\n"))
		}
	})

	t.Run("records", func(t *testing.T) {
		srv := newSQLServer(t, searchRowsHandler(t))
		q := newQueryClient(t, srv.URL, nil).Query()
		res, err := q.SearchLogs(t.Context(), SearchLogsOptions{Contains: "needle"})
		if err != nil {
			t.Fatal(err)
		}
		if len(res.Records) == 0 || res.Records[0].Timestamp.IsZero() || res.Records[0].Body == "" || res.NextCursor != "" {
			t.Fatalf("res %+v", res)
		}
		if !strings.Contains(srv.sql(0), "remote(t1_grow_sdk_logs)") {
			t.Fatalf("default source: %s", srv.sql(0))
		}
		if sql, err := q.RenderSearchSQL(SearchLogsOptions{}); err != nil || !strings.Contains(sql, "t1_grow_sdk_s3") {
			t.Fatalf("render %s %v", sql, err)
		}
	})

	t.Run("dedup hot and cold", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			line := `{"dt":"2026-11-18 12:00:00.000001","raw":"{\"message\":\"m\"}"}` + "\n"
			_, _ = w.Write([]byte(line + line))
		})
		res, err := newQueryClient(t, srv.URL, nil).Query().SearchLogs(t.Context(), SearchLogsOptions{})
		if err != nil || len(res.Records) != 1 || res.Records[0].Timestamp.Nanosecond() != 1000 {
			t.Fatalf("res %+v %v", res, err)
		}
	})

	t.Run("search error", func(t *testing.T) {
		srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, _ int) {
			loadFixture(t, "query.404_unknown_table").serve(w)
		})
		q := newQueryClient(t, srv.URL, nil).Query()
		if _, err := q.SearchLogs(t.Context(), SearchLogsOptions{}); !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		if _, err := q.Search(t.Context(), "x", SearchOptions{}); !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		if _, err := q.SearchLogs(t.Context(), SearchLogsOptions{Source: "bad source"}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
	})
}

func TestParseDateTime(t *testing.T) {
	for _, s := range []string{"2026-11-18 12:00:00", "2026-11-18 12:00:00.123456789", "2026-11-18T12:00:00+01:00"} {
		if _, err := ParseDateTime(s); err != nil {
			t.Errorf("%s: %v", s, err)
		}
	}
	if _, err := ParseDateTime("nope"); err == nil {
		t.Fatal("nope")
	}
}

func TestRecordFromRow(t *testing.T) {
	raw := `{"dt":"2026-11-18T21:00:00.123456789Z","message":"hello","level":"error","severity_number":17,` +
		`"span":{"trace_id":"4BF92F3577B34DA6A3CE929D0E0E4736","span_id":"00f067aa0ba902b7"},"trace_flags":1,` +
		`"resource":{"service.name":"svc"},"attributes":{"n":2,"run":"r1"},"observed_dt":"2026-11-18T21:00:01Z","extra":true}`
	rec := RecordFromRow(Row{"dt": "2026-11-18 21:00:00.123456", "raw": raw})
	if rec.Body != "hello" || rec.SeverityText != "ERROR" || rec.Severity != SeverityError || rec.Timestamp.Nanosecond() != 123456000 ||
		rec.TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" || rec.SpanID != "00f067aa0ba902b7" || rec.TraceFlags == nil || *rec.TraceFlags != 1 ||
		rec.Resource["service.name"] != "svc" || rec.Attributes["n"] != int64(2) || rec.Attributes["extra"] != true ||
		rec.ObservedTimestamp.IsZero() {
		t.Fatalf("record %+v", rec)
	}
	// Round trip with Event.
	in := sampleRecord("round trip")
	b, _ := marshalJSONForTest(Event(in))
	out := RecordFromRow(Row{"dt": FormatSQLTime(in.Timestamp), "raw": b})
	if out.Body != in.Body || out.Severity != in.Severity || out.TraceID != in.TraceID || out.Attributes["nested"].(map[string]any)["k"] != "Grüße ✓" {
		t.Fatalf("round trip %+v", out)
	}
	otlp := RecordFromRow(Row{"raw": `{"dt":"2026-11-18T21:00:00Z","msg":{"k":1},"severity":"warning","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"zz","severity_number":"13"}`})
	if otlp.Body != `{"k":1}` || otlp.Severity != SeverityWarn || otlp.SeverityText != "WARN" || otlp.TraceID == "" || otlp.SpanID != "" || otlp.Timestamp.IsZero() {
		t.Fatalf("otlp-shaped %+v", otlp)
	}
	if r := RecordFromRow(Row{"raw": "plain text"}); r.Body != "plain text" || r.Severity != SeverityInfo || r.SeverityText != "INFO" {
		t.Fatalf("plain %+v", r)
	}
	if r := RecordFromRow(Row{"raw": `{"other":1,"level":"custom","trace_id":"000000000000000000000000000000000"}`}); r.Body != `{"other":1,"level":"custom","trace_id":"000000000000000000000000000000000"}` ||
		r.Attributes["other"] != int64(1) || r.SeverityText != "custom" || r.TraceID != "" {
		t.Fatalf("no message %+v", r)
	}
	if r := RecordFromRow(Row{"raw": map[string]any{"message": "obj"}}); r.Body != "obj" {
		t.Fatalf("raw object %+v", r)
	}
}

func TestSearchCursor(t *testing.T) {
	srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, sql string, _ int) {
		if !strings.Contains(sql, "LIMIT 3") {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if strings.Contains(sql, "OFFSET 2") {
			_, _ = w.Write([]byte(`{"dt":"2026-11-18 12:00:00.000003","raw":"{\"message\":\"c\"}"}` + "\n"))
			return
		}
		_, _ = w.Write([]byte(`{"dt":"2026-11-18 12:00:00.000001","raw":"{\"message\":\"a\"}"}` + "\n" +
			`{"dt":"2026-11-18 12:00:00.000002","raw":"{\"message\":\"b\"}"}` + "\n" +
			`{"dt":"2026-11-18 12:00:00.000003","raw":"{\"message\":\"c\"}"}` + "\n"))
	})
	q := newQueryClient(t, srv.URL, nil).Query()
	page, err := q.Search(t.Context(), "x", SearchOptions{Limit: 2})
	if err != nil || len(page.Records) != 2 || page.NextCursor == "" {
		t.Fatalf("page %+v %v", page, err)
	}
	all, err := SearchAll(t.Context(), q, "x", SearchOptions{Limit: 2}, 0)
	if err != nil || len(all) != 3 || all[2].Body != "c" {
		t.Fatalf("all %+v %v", all, err)
	}
	for _, c := range []string{"!!", encodeCursor(-1), "bzpu"} {
		if _, err := q.Search(t.Context(), "x", SearchOptions{Cursor: c}); !errors.Is(err, ErrValidation) {
			t.Errorf("cursor %q: %v", c, err)
		}
	}
}

func TestWaitForLogAndStreams(t *testing.T) {
	srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, _ string, n int) {
		if n < 2 {
			return
		}
		_, _ = w.Write([]byte(`{"dt":"2026-11-18 12:00:00.000001","raw":"{\"message\":\"found it\"}"}` + "\n"))
	})
	q := newQueryClient(t, srv.URL, nil).Query()
	rec, err := q.WaitForLog(t.Context(), "found", WaitForLogOptions{Interval: 10 * time.Millisecond, Timeout: 5 * time.Second})
	if err != nil || rec.Body != "found it" {
		t.Fatalf("wait %+v %v", rec, err)
	}
	empty := newSQLServer(t, func(http.ResponseWriter, *http.Request, string, int) {})
	_, err = newQueryClient(t, empty.URL, nil).Query().WaitForLog(t.Context(), "never", WaitForLogOptions{Interval: 10 * time.Millisecond, Timeout: 50 * time.Millisecond})
	if !errors.Is(err, ErrTimeout) {
		t.Fatalf("timeout: %v", err)
	}
	if s, err := q.ListStreams(t.Context()); err != nil || len(s) != 1 || s[0] != testTable {
		t.Fatalf("streams %v %v", s, err)
	}
	noTable := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.SourceTable = "" }).Query()
	if s, err := noTable.ListStreams(t.Context()); err != nil || len(s) != 0 {
		t.Fatalf("streams %v %v", s, err)
	}
	if _, err := q.ListLabels(t.Context()); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("labels %v", err)
	}
}

func TestTail(t *testing.T) {
	var mu sync.Mutex
	polls := 0
	srv := newSQLServer(t, func(w http.ResponseWriter, _ *http.Request, sql string, _ int) {
		mu.Lock()
		polls++
		n := polls
		mu.Unlock()
		if !strings.Contains(sql, "ORDER BY dt ASC") {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		switch n {
		case 1:
			_, _ = w.Write([]byte(`{"dt":"2030-01-01 00:00:00.000001","raw":"{\"message\":\"a\"}"}` + "\n"))
		case 2:
			w.WriteHeader(http.StatusServiceUnavailable)
		default:
			_, _ = w.Write([]byte(`{"dt":"2020-01-01 00:00:00.000001","raw":"{\"message\":\"old\"}"}` + "\n" +
				`{"dt":"2030-01-01 00:00:00.000001","raw":"{\"message\":\"a\"}"}` + "\n" +
				`{"dt":"2030-01-01 00:00:00.000001","raw":"{\"message\":\"b\"}"}` + "\n" +
				`{"dt":"2030-01-01 00:00:00.000002","raw":"{\"message\":\"c\"}"}` + "\n"))
		}
	})
	c := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) })
	got := make(chan string, 10)
	h, err := c.Query().Tail(t.Context(), "x", func(r LogRecord) {
		got <- r.Body
		if r.Body == "b" {
			panic("handler panics are contained")
		}
	}, TailOptions{Interval: 5 * time.Millisecond, Start: time.Date(2029, 1, 1, 0, 0, 0, 0, time.UTC)})
	if err != nil {
		t.Fatal(err)
	}
	var seen []string
	timeout := time.After(5 * time.Second)
	for len(seen) < 3 {
		select {
		case b := <-got:
			seen = append(seen, b)
		case <-timeout:
			t.Fatalf("tail saw %v", seen)
		}
	}
	h.Stop()
	h.Stop()
	if strings.Join(seen, ",") != "a,b,c" {
		t.Fatalf("seen %v", seen)
	}

	if _, err := c.Query().Tail(t.Context(), "x", nil, TailOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("nil handler: %v", err)
	}
	noTable := newQueryClient(t, srv.URL, func(cfg *Config) { cfg.SourceTable = "" })
	if _, err := noTable.Query().Tail(t.Context(), "x", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrConfig) {
		t.Fatalf("no table: %v", err)
	}
	// Client.Close stops running tails; a closed client refuses new ones.
	h2, err := c.Query().Tail(context.Background(), "x", func(LogRecord) {}, TailOptions{})
	if err != nil {
		t.Fatal(err)
	}
	_ = c.Close()
	h2.Stop()
	if _, err := c.Query().Tail(t.Context(), "x", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("after close: %v", err)
	}
}
