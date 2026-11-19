package betterstack

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"strings"
	"time"
)

// Ingest limits of Better Stack's HTTP API.
const (
	// MaxRequestBytes is the largest request body (10 MiB, measured compressed). The
	// exporter never builds a larger uncompressed body, so the limit always holds.
	MaxRequestBytes = 10 << 20
	// MaxRecordBytes is the largest single encoded record (10 MiB uncompressed).
	MaxRecordBytes = 10 << 20
)

// Content types of the ingest encodings.
const (
	contentTypeJSON    = "application/json"
	contentTypeNDJSON  = "application/x-ndjson"
	contentTypeMsgPack = "application/msgpack"
)

// IngestExporter is the LogExporter that posts batches to the source's ingesting host
// (POST {url}/, Authorization: Bearer <source token>) as JSON, NDJSON or MessagePack,
// gzip-compressed when ExportOptions.Gzip is set. Batches are split so that no request
// exceeds MaxRequestBytes; a record larger than MaxRecordBytes is rejected with a
// VALIDATION error before anything is sent. Export requests are retried on 429, 5xx
// and network errors; Better Stack has no deduplication, so a retry after a lost answer
// can store a record twice.
type IngestExporter struct {
	client     *Client
	encoding   Encoding
	maxRequest int
	maxRecord  int
}

// IngestExporterOptions configure NewExporter. Zero values mean the defaults.
type IngestExporterOptions struct {
	// Encoding is the body encoding (default Config.Encoding, else EncodingJSON).
	Encoding Encoding
	// MaxRequestBytes is the uncompressed size limit of one request, at most
	// MaxRequestBytes (default MaxRequestBytes).
	MaxRequestBytes int
	// MaxRecordBytes is the encoded size limit of one record, at most MaxRecordBytes
	// (default MaxRecordBytes); larger records are rejected.
	MaxRecordBytes int
}

// NewExporter returns an IngestExporter with its own encoding and limits (use it
// with NewLogClientWith). It is a CONFIG error without ingest credentials and a
// VALIDATION error for an unknown encoding or limits outside 1..10 MiB.
func (c *Client) NewExporter(opts IngestExporterOptions) (*IngestExporter, error) {
	const op = "logs.exporter"
	if err := c.needIngest(op); err != nil {
		return nil, err
	}
	e := &IngestExporter{client: c, encoding: opts.Encoding, maxRequest: opts.MaxRequestBytes, maxRecord: opts.MaxRecordBytes}
	if e.encoding == "" {
		e.encoding = c.cfg.encoding()
	}
	switch e.encoding {
	case EncodingJSON, EncodingNDJSON, EncodingMsgPack:
	default:
		return nil, ValidationError(op, "encoding must be json, ndjson or msgpack")
	}
	for _, v := range []*int{&e.maxRequest, &e.maxRecord} {
		if *v == 0 {
			*v = MaxRequestBytes
		}
		if *v < 1 || *v > MaxRequestBytes {
			return nil, ValidationError(op, "maxRequestBytes and maxRecordBytes must be between 1 and 10 MiB")
		}
	}
	return e, nil
}

// Encoding returns the exporter's body encoding.
func (e *IngestExporter) Encoding() Encoding {
	if e.encoding == "" {
		return e.client.cfg.encoding()
	}
	return e.encoding
}

func (e *IngestExporter) limits() (request, record int) {
	request, record = MaxRequestBytes, MaxRecordBytes
	if e.maxRequest > 0 {
		request = e.maxRequest
	}
	if e.maxRecord > 0 {
		record = e.maxRecord
	}
	return request, record
}

// SendEventsOptions configure IngestExporter.SendEvents.
type SendEventsOptions struct {
	// Encoding is the body encoding (default: the exporter's encoding).
	Encoding Encoding
	// DisableGzip sends the body uncompressed (gzip is on by default).
	DisableGzip bool
}

// SendEvents posts raw Better Stack events (JSON objects; "dt" may be an RFC 3339
// string, UNIX seconds/ms/ns or a time.Time) in one request, without splitting. Values
// are normalised like attributes; time.Time becomes a MessagePack timestamp with
// EncodingMsgPack and RFC 3339 otherwise. Errors are mapped like Export's.
func (e *IngestExporter) SendEvents(ctx context.Context, events []map[string]any, opts SendEventsOptions) error {
	const op = "logs.sendEvents"
	if err := e.client.needIngest(op); err != nil {
		return err
	}
	enc := opts.Encoding
	if enc == "" {
		enc = e.Encoding()
	}
	encoded := make([]encodedRecord, 0, len(events))
	for _, ev := range events {
		var b []byte
		var err error
		switch enc {
		case EncodingMsgPack:
			b, err = marshalMsgPack(ev)
		case EncodingJSON, EncodingNDJSON:
			b, err = json.Marshal(NormalizeAttrValue(ev))
		default:
			return ValidationError(op, "encoding must be json, ndjson or msgpack")
		}
		if err != nil {
			return ValidationError(op, "an event cannot be encoded")
		}
		encoded = append(encoded, b)
	}
	body, ct := frame(enc, encoded)
	if len(body) > MaxRequestBytes {
		return ValidationError(op, "the request exceeds the 10 MiB limit")
	}
	return e.post(ctx, op, body, ct, !opts.DisableGzip)
}

var _ LogExporter = (*IngestExporter)(nil)

// Event converts a record to the Better Stack event object (the JSON that is stored
// in the "raw" column): dt (RFC 3339 UTC, nanoseconds), message, level (lower-case
// severity text), severity_number, span.trace_id / span.span_id (Better Stack's log ↔
// trace correlation fields), trace_flags, resource, attributes and observed_dt. Empty
// fields are left out.
func Event(rec LogRecord) map[string]any {
	ev := map[string]any{"dt": formatEventTime(rec.Timestamp), "message": rec.Body}
	if rec.SeverityText != "" {
		ev["level"] = strings.ToLower(rec.SeverityText)
	} else if rec.Severity != SeverityUnspecified {
		ev["level"] = strings.ToLower(rec.Severity.String())
	}
	if rec.Severity != SeverityUnspecified {
		ev["severity_number"] = int64(rec.Severity)
	}
	if rec.TraceID != "" || rec.SpanID != "" {
		span := map[string]any{}
		if rec.TraceID != "" {
			span["trace_id"] = rec.TraceID
		}
		if rec.SpanID != "" {
			span["span_id"] = rec.SpanID
		}
		ev["span"] = span
	}
	if rec.TraceFlags != nil {
		ev["trace_flags"] = int64(*rec.TraceFlags)
	}
	if len(rec.Resource) > 0 {
		ev["resource"] = NormalizeAttrValue(rec.Resource)
	}
	if len(rec.Attributes) > 0 {
		ev["attributes"] = NormalizeAttrValue(rec.Attributes)
	}
	if !rec.ObservedTimestamp.IsZero() {
		ev["observed_dt"] = formatEventTime(rec.ObservedTimestamp)
	}
	return ev
}

func formatEventTime(t time.Time) string {
	if t.IsZero() {
		t = time.Now()
	}
	return t.UTC().Format(time.RFC3339Nano)
}

// encodedRecord is one record encoded for the configured encoding.
type encodedRecord []byte

// encode encodes one event for enc. For JSON and NDJSON it is the JSON object; for
// MessagePack the map (dt as a timestamp extension).
func encodeEvent(enc Encoding, rec LogRecord) (encodedRecord, error) {
	ev := Event(rec)
	if enc == EncodingMsgPack {
		ts := rec.Timestamp
		if ts.IsZero() {
			ts = time.Now()
		}
		ev["dt"] = ts
		if !rec.ObservedTimestamp.IsZero() {
			ev["observed_dt"] = rec.ObservedTimestamp
		}
		return marshalMsgPack(ev)
	}
	return json.Marshal(ev)
}

// frame joins encoded records into one request body.
func frame(enc Encoding, recs []encodedRecord) ([]byte, string) {
	var b bytes.Buffer
	switch enc {
	case EncodingNDJSON:
		for _, r := range recs {
			b.Write(r)
			b.WriteByte('\n')
		}
		return b.Bytes(), contentTypeNDJSON
	case EncodingMsgPack:
		writeMsgPackArrayHeader(&b, len(recs))
		for _, r := range recs {
			b.Write(r)
		}
		return b.Bytes(), contentTypeMsgPack
	}
	b.WriteByte('[')
	for i, r := range recs {
		if i > 0 {
			b.WriteByte(',')
		}
		b.Write(r)
	}
	b.WriteByte(']')
	return b.Bytes(), contentTypeJSON
}

// frameOverhead is an upper bound of the framing bytes per record and per request.
const frameOverhead = 8

// split groups records so that each framed request stays within limit bytes.
func split(recs []encodedRecord, limit int) [][]encodedRecord {
	var out [][]encodedRecord
	var cur []encodedRecord
	size := frameOverhead
	for _, r := range recs {
		n := len(r) + 1
		if len(cur) > 0 && size+n > limit {
			out = append(out, cur)
			cur, size = nil, frameOverhead
		}
		cur = append(cur, r)
		size += n
	}
	if len(cur) > 0 {
		out = append(out, cur)
	}
	return out
}

// Export encodes and posts records (see IngestExporter). It returns the number of
// accepted records; records over MaxRecordBytes are rejected with a VALIDATION error,
// and a failed request fails its records and the rest of the batch.
func (e *IngestExporter) Export(ctx context.Context, records []LogRecord, opts ExportOptions) (ExportResult, error) {
	const op = "logs.export"
	if err := e.client.needIngest(op); err != nil {
		return ExportResult{}, err
	}
	enc := e.Encoding()
	limit, recordLimit := e.limits()
	var res ExportResult
	encoded := make([]encodedRecord, 0, len(records))
	for _, rec := range records {
		b, err := encodeEvent(enc, rec)
		if err != nil || len(b) > recordLimit || len(b)+frameOverhead > limit {
			res.Rejected++
			res.Error = ValidationError(op, "a record exceeds the 10 MiB record limit or cannot be encoded and was dropped")
			continue
		}
		encoded = append(encoded, b)
	}
	for _, chunk := range split(encoded, limit) {
		body, ct := frame(enc, chunk)
		if err := e.post(ctx, op, body, ct, opts.Gzip); err != nil {
			res.Rejected += len(records) - res.Accepted - res.Rejected
			if res.Accepted == 0 {
				return res, err
			}
			res.Error = asSDKError(err)
			return res, nil
		}
		res.Accepted += len(chunk)
	}
	if res.Accepted == 0 && res.Error != nil {
		return res, res.Error
	}
	return res, nil
}

// asSDKError converts err to *Error.
func asSDKError(err error) *Error {
	return contextErr("logs.export", err)
}

// post sends one ingest request body.
func (e *IngestExporter) post(ctx context.Context, op string, body []byte, contentType string, gz bool) error {
	h := e.client.ingestHeader()
	if gz {
		var b bytes.Buffer
		w := gzip.NewWriter(&b)
		_, _ = w.Write(body)
		_ = w.Close()
		body = b.Bytes()
		h.Set("Content-Encoding", "gzip")
	}
	_, err := e.client.ingest.do(ctx, &request{
		Method: "POST", Path: "/", Header: h, Body: body, ContentType: contentType,
		Idempotent: true, Operation: op,
	})
	if err != nil {
		return remapIngestError(err)
	}
	return nil
}

// Shutdown is a no-op (the Client owns the connections).
func (e *IngestExporter) Shutdown(context.Context) error { return nil }
