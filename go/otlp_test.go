package betterstack

import (
	"compress/gzip"
	"errors"
	"io"
	"net/http"
	"testing"

	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

func partialSuccess(rejected int64) []byte {
	var inner []byte
	inner = protowire.AppendTag(inner, 2, protowire.BytesType)
	inner = protowire.AppendString(inner, "bad")
	inner = protowire.AppendTag(inner, 1, protowire.VarintType)
	inner = protowire.AppendVarint(inner, uint64(rejected))
	var out []byte
	out = protowire.AppendTag(out, 3, protowire.VarintType)
	out = protowire.AppendVarint(out, 1)
	out = protowire.AppendTag(out, 1, protowire.BytesType)
	return protowire.AppendBytes(out, inner)
}

func TestOTLPLogExporter(t *testing.T) {
	var got logspb.LogsData
	var encoding string
	ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		if r.URL.Path != OTLPLogsPath || r.Header.Get("Authorization") != "Bearer "+testToken || r.Header.Get("Content-Type") != "application/x-protobuf" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		var body io.Reader = r.Body
		encoding = r.Header.Get("Content-Encoding")
		if encoding == "gzip" {
			body, _ = gzip.NewReader(r.Body)
		}
		data, _ := io.ReadAll(body)
		if err := proto.Unmarshal(data, &got); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		if n == 2 {
			_, _ = w.Write(partialSuccess(1))
		}
	})
	c := newTestClient(t, ts.URL, nil)
	e := c.OTLPExporter()
	recs := []LogRecord{sampleRecord("one"), {Body: "two", Resource: map[string]AttrValue{"service.name": "other"}}}
	res, err := e.Export(t.Context(), recs, ExportOptions{})
	if err != nil || res.Accepted != 2 {
		t.Fatalf("export %+v %v", res, err)
	}
	if len(got.ResourceLogs) != 2 || got.ResourceLogs[0].ScopeLogs[0].Scope.Name != LogScopeName {
		t.Fatalf("data %v", &got)
	}
	lr := got.ResourceLogs[0].ScopeLogs[0].LogRecords[0]
	if lr.Body.GetStringValue() != "one" || len(lr.TraceId) != 16 || len(lr.SpanId) != 8 || lr.Flags != 1 || lr.SeverityNumber != 17 || lr.ObservedTimeUnixNano == 0 {
		t.Fatalf("record %v", lr)
	}
	res, err = e.Export(t.Context(), recs, ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 1 || res.Rejected != 1 || !errors.Is(res.Error, ErrValidation) || encoding != "gzip" {
		t.Fatalf("partial %+v %v %s", res, err, encoding)
	}
	if res, err := e.Export(t.Context(), nil, ExportOptions{}); err != nil || res.Accepted != 0 {
		t.Fatal("empty")
	}
}

func TestOTLPErrorsAndResponses(t *testing.T) {
	ts := newTestServer(t, fixtureHandler(t, "send.403_bad_token"))
	c := newTestClient(t, ts.URL, nil)
	if _, err := c.OTLPExporter().Export(t.Context(), []LogRecord{{Body: "x"}}, ExportOptions{}); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("403: %v", err)
	}
	ts404 := newTestServer(t, fixtureHandler(t, "otlpExport.v1_logs"))
	if _, err := newTestClient(t, ts404.URL, nil).OTLPExporter().Export(t.Context(), []LogRecord{{Body: "x"}}, ExportOptions{}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("404: %v", err)
	}

	jsonRes := &response{Header: http.Header{"Content-Type": {"application/json"}}, Body: []byte(`{"partialSuccess":{"rejectedLogRecords":"3"}}`)}
	if rejectedFromOTLP(jsonRes) != 3 {
		t.Fatal("json partial")
	}
	jsonRes.Body = []byte("nope")
	if rejectedFromOTLP(jsonRes) != 0 {
		t.Fatal("json broken")
	}
	for _, b := range [][]byte{nil, {0xff}, {0x0a, 0x05}, {0x0a, 0x01, 0x08}, {0x0a, 0x02, 0x10, 0x01}, {0x08, 0xff}, {0x0a, 0x01, 0xff}} {
		if rejectedFromOTLP(&response{Header: http.Header{}, Body: b}) != 0 {
			t.Errorf("garbage %x", b)
		}
	}
	if rejectedFromOTLP(&response{Header: http.Header{}, Body: partialSuccess(4)}) != 4 {
		t.Fatal("proto partial")
	}
	attrs := map[string]AttrValue{"s": "x", "b": true, "i": int64(1), "f": 1.5, "l": []AttrValue{"a", nil}, "m": map[string]AttrValue{"k": "v"}, "nil": nil}
	if kv := otlpKeyValues(attrs); len(kv) != 6 {
		t.Fatalf("kv %v", kv)
	}
}
