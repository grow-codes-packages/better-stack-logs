package betterstack

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// HeaderClickHouseExceptionCode is the response header that carries the ClickHouse
// exception code of a failed query.
const HeaderClickHouseExceptionCode = "X-ClickHouse-Exception-Code"

// maxCapturedBody bounds the error body kept for mapping.
const maxCapturedBody = 64 << 10

var clickHouseCodeRe = regexp.MustCompile(`(?m)^\s*Code:\s*(\d+)\.`)

// capture records the last error response of one call (the transport keeps only the
// status in its error). It is attached to the call's context.
type capture struct {
	mu     sync.Mutex
	status int
	header http.Header
	body   []byte
}

type captureKey struct{}

func withCapture(ctx context.Context) (context.Context, *capture) {
	c := &capture{}
	return context.WithValue(ctx, captureKey{}, c), c
}

func (c *capture) get() (int, http.Header, []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.status, c.header, c.body
}

// captureTransport is an http.RoundTripper that copies error responses (status >= 400)
// into the capture of the request context, leaving the body readable for the caller.
type captureTransport struct {
	next http.RoundTripper
}

func (t captureTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := t.next.RoundTrip(req)
	if err != nil || resp.StatusCode < 400 {
		return resp, err
	}
	c, ok := req.Context().Value(captureKey{}).(*capture)
	if !ok {
		return resp, nil
	}
	data, rerr := io.ReadAll(io.LimitReader(resp.Body, maxCapturedBody))
	_ = resp.Body.Close()
	resp.Body = io.NopCloser(bytes.NewReader(data))
	if rerr != nil {
		return resp, nil //nolint:nilerr // a short read only loses the mapping detail; the caller reads the status
	}
	c.mu.Lock()
	c.status, c.header, c.body = resp.StatusCode, resp.Header.Clone(), data
	c.mu.Unlock()
	return resp, nil
}

// ClickHouseErrorCode returns the ClickHouse exception code of an error response: the
// X-ClickHouse-Exception-Code header, else "Code: N." at the start of a body line.
// It returns 0 when there is none.
func ClickHouseErrorCode(header http.Header, body []byte) int {
	if header != nil {
		if n, err := strconv.Atoi(strings.TrimSpace(header.Get(HeaderClickHouseExceptionCode))); err == nil && n > 0 {
			return n
		}
	}
	if m := clickHouseCodeRe.FindSubmatch(body); m != nil {
		n, _ := strconv.Atoi(string(m[1]))
		return n
	}
	return 0
}

// CodeForClickHouse maps a ClickHouse exception code to a canonical error code, or ""
// when the code has no specific mapping (the HTTP status decides then).
func CodeForClickHouse(code int) ErrorCode {
	switch code {
	case 516, 192, 193, 194: // AUTHENTICATION_FAILED, UNKNOWN_USER, WRONG_PASSWORD, REQUIRED_PASSWORD
		return ErrorCodeAuthentication
	case 497: // ACCESS_DENIED
		return ErrorCodePermissionDenied
	case 60, 81: // UNKNOWN_TABLE, UNKNOWN_DATABASE
		return ErrorCodeNotFound
	case 62, 47, 46, 36, 53, 42, 73, 241: // syntax, identifiers, arguments, types, format, memory limit
		return ErrorCodeValidation
	case 48: // NOT_IMPLEMENTED
		return ErrorCodeUnsupported
	case 159, 160: // TIMEOUT_EXCEEDED, TOO_SLOW
		return ErrorCodeTimeout
	case 202: // TOO_MANY_SIMULTANEOUS_QUERIES
		return ErrorCodeRateLimited
	}
	return ""
}

// remapQueryError applies the ClickHouse code mapping to a transport error.
func remapQueryError(err error, c *capture) error {
	var e *Error
	if !errors.As(err, &e) || e.HTTPStatus == 0 {
		return err
	}
	status, header, body := c.get()
	if status != e.HTTPStatus {
		return err
	}
	chCode := ClickHouseErrorCode(header, body)
	code := CodeForClickHouse(chCode)
	if code == "" || (code == e.Code && chCode != 241) {
		return err
	}
	out := *e
	out.Code = code
	out.Retryable = code.Retryable()
	if chCode == 241 {
		out.Message += " (narrow the time range or sample the cold table)"
	}
	return &out
}

// remapIngestError applies the Better Stack ingest overrides: 403 (invalid source
// token) → AUTHENTICATION, 402 (quota exceeded) → RATE_LIMITED, not retryable;
// 406 and 413 stay VALIDATION.
func remapIngestError(err error) error {
	var e *Error
	if !errors.As(err, &e) {
		return err
	}
	var code ErrorCode
	switch e.HTTPStatus {
	case http.StatusForbidden:
		code = ErrorCodeAuthentication
	case http.StatusPaymentRequired:
		code = ErrorCodeRateLimited
	default:
		return err
	}
	out := *e
	out.Code = code
	out.Retryable = false
	return &out
}
