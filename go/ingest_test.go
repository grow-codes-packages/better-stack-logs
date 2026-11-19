package betterstack

import (
	"errors"
	"math"
	"net/http"
	"strings"
	"testing"
	"time"
)

func sampleRecord(msg string) LogRecord {
	flags := uint8(1)
	return LogRecord{
		Timestamp:         time.Date(2026, 11, 18, 21, 0, 0, 123456789, time.UTC),
		ObservedTimestamp: time.Date(2026, 11, 18, 21, 0, 1, 0, time.UTC),
		Severity:          SeverityError,
		SeverityText:      "ERROR",
		Body:              msg,
		Attributes:        map[string]AttrValue{"n": int64(2), "nested": map[string]AttrValue{"k": "Grüße ✓"}, "list": []AttrValue{"a", true, 1.5}},
		Resource:          map[string]AttrValue{"service.name": "svc"},
		TraceID:           "4bf92f3577b34da6a3ce929d0e0e4736",
		SpanID:            "00f067aa0ba902b7",
		TraceFlags:        &flags,
	}
}

func TestEvent(t *testing.T) {
	ev := Event(sampleRecord("hello"))
	if ev["dt"] != "2026-11-18T21:00:00.123456789Z" || ev["message"] != "hello" || ev["level"] != "error" ||
		ev["severity_number"] != int64(17) || ev["trace_flags"] != int64(1) || ev["observed_dt"] != "2026-11-18T21:00:01Z" {
		t.Fatalf("event %v", ev)
	}
	span := ev["span"].(map[string]any)
	if span["trace_id"] != "4bf92f3577b34da6a3ce929d0e0e4736" || span["span_id"] != "00f067aa0ba902b7" {
		t.Fatalf("span %v", span)
	}
	if ev["resource"].(map[string]any)["service.name"] != "svc" || ev["attributes"].(map[string]any)["n"] != int64(2) {
		t.Fatalf("maps %v", ev)
	}
	min := Event(LogRecord{Severity: SeverityWarn, SpanID: "00f067aa0ba902b7"})
	if min["level"] != "warn" || min["dt"] == "" {
		t.Fatalf("minimal %v", min)
	}
	if _, ok := min["attributes"]; ok {
		t.Fatal("empty attributes must be left out")
	}
	if _, ok := Event(LogRecord{Body: "x"})["level"]; ok {
		t.Fatal("no level without severity")
	}
}

func TestIngestExporter(t *testing.T) {
	for _, enc := range []Encoding{EncodingJSON, EncodingNDJSON, EncodingMsgPack} {
		for _, gz := range []bool{false, true} {
			t.Run(string(enc)+map[bool]string{true: "+gzip", false: ""}[gz], func(t *testing.T) {
				fi := &fakeIngest{}
				ts := newTestServer(t, fi.handler)
				c := newTestClient(t, ts.URL, func(cfg *Config) { cfg.Encoding = enc })
				recs := []LogRecord{sampleRecord("one"), sampleRecord("two"), {Body: "three"}}
				res, err := c.Exporter().Export(t.Context(), recs, ExportOptions{Gzip: gz})
				if err != nil || res.Accepted != 3 || res.Rejected != 0 {
					t.Fatalf("export %+v %v", res, err)
				}
				evs, types, encs := fi.snapshot()
				if len(evs) != 3 || evs[0]["message"] != "one" || evs[2]["message"] != "three" {
					t.Fatalf("events %v", evs)
				}
				want := map[Encoding]string{EncodingJSON: contentTypeJSON, EncodingNDJSON: contentTypeNDJSON, EncodingMsgPack: contentTypeMsgPack}[enc]
				if types[0] != want || (encs[0] == "gzip") != gz {
					t.Fatalf("headers %v %v", types, encs)
				}
				attrs := evs[0]["attributes"].(map[string]any)
				if attrs["nested"].(map[string]any)["k"] != "Grüße ✓" {
					t.Fatalf("attrs %v", attrs)
				}
				if enc == EncodingMsgPack {
					if dt, ok := evs[0]["dt"].(time.Time); !ok || !dt.Equal(recs[0].Timestamp) {
						t.Fatalf("msgpack dt %v", evs[0]["dt"])
					}
				} else if evs[0]["dt"] != "2026-11-18T21:00:00.123456789Z" {
					t.Fatalf("dt %v", evs[0]["dt"])
				}
			})
		}
	}

	t.Run("split by request size", func(t *testing.T) {
		fi := &fakeIngest{}
		ts := newTestServer(t, fi.handler)
		c := newTestClient(t, ts.URL, nil)
		e := &IngestExporter{client: c, maxRequest: 400}
		recs := make([]LogRecord, 10)
		for i := range recs {
			recs[i] = LogRecord{Body: strings.Repeat("x", 50), Timestamp: time.Unix(0, 0)}
		}
		res, err := e.Export(t.Context(), recs, ExportOptions{})
		if err != nil || res.Accepted != 10 || fi.bodies < 3 {
			t.Fatalf("res %+v err %v bodies %d", res, err, fi.bodies)
		}
		// One oversized record is rejected, the rest sent.
		big := append([]LogRecord{{Body: strings.Repeat("y", 1000)}}, recs[:2]...)
		res, err = e.Export(t.Context(), big, ExportOptions{})
		if err != nil || res.Accepted != 2 || res.Rejected != 1 || !errors.Is(res.Error, ErrValidation) {
			t.Fatalf("oversized %+v %v", res, err)
		}
		res, err = e.Export(t.Context(), big[:1], ExportOptions{})
		if !errors.Is(err, ErrValidation) || res.Accepted != 0 {
			t.Fatalf("only oversized %+v %v", res, err)
		}
	})

	t.Run("partial failure", func(t *testing.T) {
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, n int) {
			if n == 1 {
				w.WriteHeader(http.StatusAccepted)
				return
			}
			loadFixture(t, "send.406_bad_json").serve(w)
		})
		c := newTestClient(t, ts.URL, nil)
		e := &IngestExporter{client: c, maxRequest: 100}
		recs := []LogRecord{{Body: strings.Repeat("a", 40)}, {Body: strings.Repeat("b", 40)}, {Body: strings.Repeat("c", 40)}}
		res, err := e.Export(t.Context(), recs, ExportOptions{})
		if err != nil || res.Accepted != 1 || res.Rejected != 2 || !errors.Is(res.Error, ErrValidation) {
			t.Fatalf("partial %+v %v", res, err)
		}
	})

	t.Run("unencodable record", func(t *testing.T) {
		fi := &fakeIngest{}
		ts := newTestServer(t, fi.handler)
		c := newTestClient(t, ts.URL, nil)
		res, err := c.Exporter().Export(t.Context(), []LogRecord{{Body: "x", Attributes: map[string]AttrValue{"f": math.Inf(1)}}}, ExportOptions{})
		if err != nil || res.Accepted != 1 {
			t.Fatalf("inf normalized: %+v %v", res, err)
		}
		if c.Exporter().Shutdown(t.Context()) != nil || c.OTLPExporter().Shutdown(t.Context()) != nil {
			t.Fatal("shutdown")
		}
	})
}

func TestIngestErrors(t *testing.T) {
	cases := []struct {
		fixture   string
		code      ErrorCode
		retryable bool
	}{
		{"send.403_bad_token", ErrorCodeAuthentication, false},
		{"send.403_missing_token", ErrorCodeAuthentication, false},
		{"send.402_fault", ErrorCodeRateLimited, false},
		{"send.406_bad_json", ErrorCodeValidation, false},
		{"send.406_bad_msgpack", ErrorCodeValidation, false},
		{"send.406_scalar", ErrorCodeValidation, false},
		{"send.413_too_large", ErrorCodeValidation, false},
		{"send.429_fault", ErrorCodeRateLimited, true},
		{"send.503_fault", ErrorCodeUnavailable, true},
	}
	for _, tc := range cases {
		t.Run(tc.fixture, func(t *testing.T) {
			ts := newTestServer(t, fixtureHandler(t, tc.fixture))
			c := newTestClient(t, ts.URL, func(cfg *Config) { cfg.Retry = fastRetry(1) })
			_, err := c.Exporter().Export(t.Context(), []LogRecord{{Body: "x"}}, ExportOptions{})
			e := asError(t, err)
			if e.Code != tc.code || e.Retryable != tc.retryable || strings.Contains(e.Error(), testToken) {
				t.Fatalf("err %+v", e)
			}
			wantCalls := int32(1)
			if tc.retryable {
				wantCalls = 2
			}
			if ts.calls.Load() != wantCalls {
				t.Fatalf("calls %d", ts.calls.Load())
			}
		})
	}
	t.Run("remap passes other errors", func(t *testing.T) {
		plain := errors.New("plain")
		if remapIngestError(plain) != plain {
			t.Fatal("plain")
		}
	})
}

func TestNewLogClient(t *testing.T) {
	fi := &fakeIngest{}
	ts := newTestServer(t, fi.handler)
	c := newTestClient(t, ts.URL, nil)
	lc, err := c.NewLogClient(LogClientOptions{Batch: BatchOptions{MaxBatchBytes: MaxRequestBytes * 2}})
	if err != nil {
		t.Fatal(err)
	}
	lc.Logger("checkout", map[string]AttrValue{"region": "eu"}).Info(t.Context(), "order placed", "order_id", 42, "password", "hunter2")
	lc.Send(t.Context(), LogInput{Message: "second", Severity: SeverityWarn})
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}
	evs, _, _ := fi.snapshot()
	if len(evs) != 2 || evs[0]["message"] != "order placed" || evs[0]["level"] != "info" {
		t.Fatalf("events %v", evs)
	}
	attrs := evs[0]["attributes"].(map[string]any)
	if attrs["order_id"] != float64(42) || attrs["password"] == "hunter2" || attrs["region"] != "eu" {
		t.Fatalf("attrs %v", attrs)
	}
	if st := lc.Stats(); st.Sent != 2 || st.Dropped != 0 {
		t.Fatalf("stats %+v", st)
	}
	// Client.Close flushes registered log clients.
	lc.Send(t.Context(), LogInput{Message: "on close"})
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if evs, _, _ := fi.snapshot(); len(evs) != 3 {
		t.Fatalf("close did not flush: %d", len(evs))
	}
	if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: ts.URL}, SourceToken: NewSecret(testToken)}); err != nil {
		t.Fatal(err)
	}
}

func TestNewLogClientWithInvalidBatch(t *testing.T) {
	ts := newTestServer(t, statusHandler(http.StatusAccepted))
	c := newTestClient(t, ts.URL, nil)
	if _, err := c.NewLogClient(LogClientOptions{Batch: BatchOptions{MaxQueueSize: -1}}); err == nil {
		t.Fatal("invalid batch options must fail")
	}
}

func TestMsgPack(t *testing.T) {
	values := []any{
		nil, true, false, int64(0), int64(-5), int64(-100), int64(70000), int64(1 << 40), 7, 1.25,
		strings.Repeat("s", 40), strings.Repeat("m", 300), strings.Repeat("l", 70000),
		make([]any, 20), map[string]any{"a": int64(1)}, int32(9), []string{"x"},
		time.Unix(5, 0), time.Unix(5, 7), time.Unix(1<<35, 3), time.Unix(-5, 0),
	}
	big := map[string]any{}
	for i := 0; i < 20; i++ {
		big[strings.Repeat("k", i+1)] = int64(i)
	}
	values = append(values, big, make([]any, 70000))
	for i, v := range values {
		b, err := marshalMsgPack(v)
		if err != nil {
			t.Fatalf("%d: %v", i, err)
		}
		got, rest, err := decodeMsgPack(b)
		if err != nil || len(rest) != 0 {
			t.Fatalf("%d: decode %v rest %d", i, err, len(rest))
		}
		if tm, ok := v.(time.Time); ok && !got.(time.Time).Equal(tm) {
			t.Fatalf("%d: time %v != %v", i, got, tm)
		}
	}
	if _, err := marshalMsgPack(map[string]any{"c": make(chan int)}); err != nil {
		t.Fatalf("chan is normalised to text: %v", err)
	}
	deep := any("x")
	for i := 0; i < 70; i++ {
		deep = []any{deep}
	}
	if _, err := marshalMsgPack(deep); err == nil {
		t.Fatal("deep must fail")
	}
}
