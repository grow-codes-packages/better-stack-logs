package betterstack

import (
	"errors"
	"net/http"
	"testing"
	"time"
)

func TestQueryRawAndSettings(t *testing.T) {
	srv := newSQLServer(t, func(w http.ResponseWriter, r *http.Request, _ string, _ int) {
		if r.URL.Query().Get("max_execution_time") != "30" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = w.Write([]byte("c\n5\n"))
	})
	q := newQueryClient(t, srv.URL, nil).Query()
	text, err := q.QueryRaw(t.Context(), "SELECT count() AS c FROM remote({t:Identifier}) FORMAT CSVWithNames", map[string]any{"t": "t1_x_logs"},
		QueryOptions{Settings: map[string]any{"max_execution_time": 30}})
	if err != nil || text != "c\n5\n" || srv.sql(0) != "SELECT count() AS c FROM remote(t1_x_logs) FORMAT CSVWithNames" {
		t.Fatalf("raw %q %v %q", text, err, srv.sql(0))
	}
	if _, err := q.QueryRaw(t.Context(), "SELECT 1", nil, QueryOptions{Settings: map[string]any{"bad name": 1}}); !errors.Is(err, ErrValidation) {
		t.Fatalf("setting name: %v", err)
	}
	if _, err := q.QueryRaw(t.Context(), "SELECT 1", nil); !errors.Is(err, ErrValidation) {
		t.Fatalf("400 without setting: %v", err)
	}
	if _, err := q.QueryRaw(t.Context(), " ", nil); !errors.Is(err, ErrValidation) {
		t.Fatalf("empty: %v", err)
	}
	if _, err := q.QueryRaw(t.Context(), "SELECT {x:String}", nil); !errors.Is(err, ErrValidation) {
		t.Fatalf("missing param: %v", err)
	}
}

func TestNewIngestExporterAndSendEvents(t *testing.T) {
	fi := &fakeIngest{}
	ts := newTestServer(t, fi.handler)
	c := newTestClient(t, ts.URL, nil)
	if c.Exporter().Encoding() != EncodingJSON {
		t.Fatal("default encoding")
	}
	e, err := c.NewIngestExporter(IngestExporterOptions{Encoding: EncodingNDJSON, MaxRequestBytes: 1 << 20, MaxRecordBytes: 300})
	if err != nil || e.Encoding() != EncodingNDJSON {
		t.Fatalf("exporter %v", err)
	}
	res, err := e.Export(t.Context(), []LogRecord{{Body: "small"}, {Body: string(make([]byte, 400))}}, ExportOptions{})
	if err != nil || res.Accepted != 1 || res.Rejected != 1 {
		t.Fatalf("record limit %+v %v", res, err)
	}
	for _, bad := range []IngestExporterOptions{{Encoding: "xml"}, {MaxRequestBytes: MaxRequestBytes + 1}, {MaxRecordBytes: -1}} {
		if _, err := c.NewIngestExporter(bad); !errors.Is(err, ErrValidation) {
			t.Errorf("%+v: %v", bad, err)
		}
	}
	lc, err := c.NewLogClientWith(e, LogClientOptions{})
	if err != nil {
		t.Fatal(err)
	}
	lc.Send(t.Context(), LogInput{Message: "via custom exporter"})
	if err := lc.Flush(t.Context()); err != nil {
		t.Fatal(err)
	}

	ts0 := time.Date(2026, 1, 2, 3, 4, 5, 6000, time.UTC)
	events := []map[string]any{{"message": "raw one", "dt": ts0, "nested": map[string]any{"a": 1}}, {"message": "raw two", "dt": 1700000000}}
	for _, enc := range []Encoding{"", EncodingNDJSON, EncodingMsgPack} {
		if err := c.Exporter().SendEvents(t.Context(), events, SendEventsOptions{Encoding: enc, DisableGzip: enc == EncodingNDJSON}); err != nil {
			t.Fatalf("%s: %v", enc, err)
		}
	}
	evs, types, encs := fi.snapshot()
	n := len(evs)
	if n != 8 || evs[2]["message"] != "raw one" || evs[2]["dt"] != "2026-01-02T03:04:05.000006Z" || types[len(types)-1] != contentTypeMsgPack ||
		encs[len(encs)-2] != "" || encs[len(encs)-1] != "gzip" {
		t.Fatalf("events %d %v %v %v", n, evs, types, encs)
	}
	if dt, ok := evs[6]["dt"].(time.Time); !ok || !dt.Equal(ts0) {
		t.Fatalf("msgpack dt %v", evs[6]["dt"])
	}
	if err := c.Exporter().SendEvents(t.Context(), events, SendEventsOptions{Encoding: "xml"}); !errors.Is(err, ErrValidation) {
		t.Fatalf("bad encoding: %v", err)
	}
	if err := c.Exporter().SendEvents(t.Context(), []map[string]any{{"big": string(make([]byte, MaxRequestBytes))}}, SendEventsOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("too large: %v", err)
	}
	if err := c.Exporter().SendEvents(t.Context(), []map[string]any{{"c": make(chan int)}}, SendEventsOptions{}); err != nil {
		t.Fatalf("chan normalized: %v", err)
	}

	q := newQueryClient(t, ts.URL, nil)
	if _, err := q.NewIngestExporter(IngestExporterOptions{}); !errors.Is(err, ErrConfig) {
		t.Fatalf("no ingest: %v", err)
	}
	if err := (&IngestExporter{client: q}).SendEvents(t.Context(), events, SendEventsOptions{}); !errors.Is(err, ErrConfig) {
		t.Fatalf("no ingest send: %v", err)
	}
}
