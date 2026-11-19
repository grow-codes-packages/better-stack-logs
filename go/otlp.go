package betterstack

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strings"

	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

// LogScopeName is the instrumentation scope name of records exported over OTLP.
const LogScopeName = "grow-codes-better-stack-logs"

// OTLPLogsPath is the OTLP/HTTP logs path on the ingesting host.
const OTLPLogsPath = "/v1/logs"

// OTLPLogExporter is a LogExporter that sends records as OTLP/HTTP protobuf
// (ExportLogsServiceRequest, Content-Type application/x-protobuf) to {url}/v1/logs on
// the source's ingesting host with "Authorization: Bearer <source token>", gzip-
// compressed when ExportOptions.Gzip is set. A partial_success answer marks the
// rejected records as failed. Get it from Client.OTLPExporter.
type OTLPLogExporter struct {
	client *Client
}

var _ LogExporter = (*OTLPLogExporter)(nil)

// Export sends records in one OTLP request.
func (e *OTLPLogExporter) Export(ctx context.Context, records []LogRecord, opts ExportOptions) (ExportResult, error) {
	const op = "logs.otlpExport"
	if err := e.client.needIngest(op); err != nil {
		return ExportResult{}, err
	}
	if len(records) == 0 {
		return ExportResult{}, nil
	}
	body, err := MarshalOTLPLogs(records)
	if err != nil {
		return ExportResult{}, ValidationError(op, "records cannot be encoded")
	}
	h := e.client.ingestHeader()
	if opts.Gzip {
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		_, _ = zw.Write(body)
		_ = zw.Close()
		body = buf.Bytes()
		h.Set("Content-Encoding", "gzip")
	}
	h.Set("Accept", "application/x-protobuf")
	res, err := e.client.ingest.do(ctx, &request{
		Method: http.MethodPost, Path: OTLPLogsPath, Body: body, ContentType: "application/x-protobuf",
		Header: h, Operation: op, Idempotent: true,
	})
	if err != nil {
		return ExportResult{}, remapIngestError(err)
	}
	n := len(records)
	rej := int(min(max(rejectedFromOTLP(res), 0), int64(n)))
	out := ExportResult{Accepted: n - rej, Rejected: rej}
	if rej > 0 {
		out.Error = ValidationError(op, fmt.Sprintf("the ingesting host rejected %d log records", rej))
	}
	return out, nil
}

// Shutdown is a no-op (the Client owns the connections).
func (e *OTLPLogExporter) Shutdown(context.Context) error { return nil }

// MarshalOTLPLogs encodes records as an OTLP ExportLogsServiceRequest (protobuf),
// grouped by resource, with scope LogScopeName.
func MarshalOTLPLogs(records []LogRecord) ([]byte, error) {
	// ExportLogsServiceRequest and LogsData share the wire format (field 1, repeated
	// ResourceLogs), so the collector package (and its gRPC dependencies) is not needed.
	data := &logspb.LogsData{}
	byResource := map[string]*logspb.ScopeLogs{}
	for _, r := range records {
		key, _ := json.Marshal(r.Resource) // maps marshal with sorted keys
		sl, ok := byResource[string(key)]
		if !ok {
			sl = &logspb.ScopeLogs{Scope: &commonpb.InstrumentationScope{Name: LogScopeName, Version: Version}}
			byResource[string(key)] = sl
			data.ResourceLogs = append(data.ResourceLogs, &logspb.ResourceLogs{
				Resource:  &resourcepb.Resource{Attributes: otlpKeyValues(r.Resource)},
				ScopeLogs: []*logspb.ScopeLogs{sl},
			})
		}
		sl.LogRecords = append(sl.LogRecords, otlpLogRecord(r))
	}
	return proto.Marshal(data)
}

func otlpLogRecord(r LogRecord) *logspb.LogRecord {
	lr := &logspb.LogRecord{
		SeverityNumber: logspb.SeverityNumber(r.Severity), // #nosec G115 -- severities are 0..24
		SeverityText:   r.SeverityText,
		Body:           &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: r.Body}},
		Attributes:     otlpKeyValues(r.Attributes),
	}
	if !r.Timestamp.IsZero() {
		lr.TimeUnixNano = uint64(r.Timestamp.UnixNano()) // #nosec G115 -- post-1970 timestamps
	}
	if !r.ObservedTimestamp.IsZero() {
		lr.ObservedTimeUnixNano = uint64(r.ObservedTimestamp.UnixNano()) // #nosec G115 -- post-1970 timestamps
	}
	if b, err := hex.DecodeString(r.TraceID); err == nil && len(b) == 16 {
		lr.TraceId = b
	}
	if b, err := hex.DecodeString(r.SpanID); err == nil && len(b) == 8 {
		lr.SpanId = b
	}
	if r.TraceFlags != nil {
		lr.Flags = uint32(*r.TraceFlags)
	}
	return lr
}

func otlpKeyValues(m map[string]AttrValue) []*commonpb.KeyValue {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]*commonpb.KeyValue, 0, len(keys))
	for _, k := range keys {
		if v := otlpAnyValue(m[k]); v != nil {
			out = append(out, &commonpb.KeyValue{Key: k, Value: v})
		}
	}
	return out
}

func otlpAnyValue(v AttrValue) *commonpb.AnyValue {
	switch x := NormalizeAttrValue(v).(type) {
	case nil:
		return nil
	case string:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: x}}
	case bool:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_BoolValue{BoolValue: x}}
	case int64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: x}}
	case float64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_DoubleValue{DoubleValue: x}}
	case []AttrValue:
		arr := &commonpb.ArrayValue{}
		for _, e := range x {
			if av := otlpAnyValue(e); av != nil {
				arr.Values = append(arr.Values, av)
			}
		}
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_ArrayValue{ArrayValue: arr}}
	case map[string]AttrValue:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_KvlistValue{KvlistValue: &commonpb.KeyValueList{Values: otlpKeyValues(x)}}}
	default:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: fmt.Sprint(x)}}
	}
}

// rejectedFromOTLP reads partial_success.rejected_log_records from a protobuf or JSON
// ExportLogsServiceResponse (0 when absent or unreadable).
func rejectedFromOTLP(res *response) int64 {
	if len(res.Body) == 0 {
		return 0
	}
	if strings.Contains(res.Header.Get("Content-Type"), "json") {
		var j struct {
			PartialSuccess struct {
				RejectedLogRecords json.Number `json:"rejectedLogRecords"`
			} `json:"partialSuccess"`
		}
		if json.Unmarshal(res.Body, &j) == nil {
			n, _ := j.PartialSuccess.RejectedLogRecords.Int64()
			return n
		}
		return 0
	}
	partial := protoField(res.Body, 1)
	if partial == nil {
		return 0
	}
	return protoVarint(partial, 1)
}

// protoField returns the bytes of the first length-delimited field num in b.
func protoField(b []byte, num protowire.Number) []byte {
	for len(b) > 0 {
		n, typ, l := protowire.ConsumeTag(b)
		if l < 0 {
			return nil
		}
		b = b[l:]
		if n == num && typ == protowire.BytesType {
			v, m := protowire.ConsumeBytes(b)
			if m < 0 {
				return nil
			}
			return v
		}
		m := protowire.ConsumeFieldValue(n, typ, b)
		if m < 0 {
			return nil
		}
		b = b[m:]
	}
	return nil
}

// protoVarint returns the first varint field num in b (0 when absent).
func protoVarint(b []byte, num protowire.Number) int64 {
	for len(b) > 0 {
		n, typ, l := protowire.ConsumeTag(b)
		if l < 0 {
			return 0
		}
		b = b[l:]
		if n == num && typ == protowire.VarintType {
			v, m := protowire.ConsumeVarint(b)
			if m < 0 {
				return 0
			}
			return int64(v) // #nosec G115 -- counts fit int64
		}
		m := protowire.ConsumeFieldValue(n, typ, b)
		if m < 0 {
			return 0
		}
		b = b[m:]
	}
	return 0
}
