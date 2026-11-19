package betterstack

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	testQueryUser     = "query-user"
	testQueryPassword = "query-PASSWORD-5678"
	testTable         = "t1_grow_sdk"
)

// fixture is a recorded stub exchange (testdata/fixtures, recorded by the prep agent
// against the sdk-kit Better Stack stub).
type fixture struct {
	Response struct {
		Status  int               `json:"status"`
		Headers map[string]string `json:"headers"`
		Body    json.RawMessage   `json:"body"`
	} `json:"response"`
}

func loadFixture(t *testing.T, name string) fixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var f fixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func (f fixture) body() []byte {
	var s string
	if json.Unmarshal(f.Response.Body, &s) == nil {
		return []byte(s)
	}
	if string(f.Response.Body) == "null" {
		return nil
	}
	var buf bytes.Buffer
	if json.Compact(&buf, f.Response.Body) == nil {
		return buf.Bytes()
	}
	return f.Response.Body
}

// serve writes the recorded response.
func (f fixture) serve(w http.ResponseWriter) {
	for k, v := range f.Response.Headers {
		if strings.EqualFold(k, "content-length") {
			continue
		}
		w.Header().Set(k, v)
	}
	w.WriteHeader(f.Response.Status)
	_, _ = w.Write(f.body())
}

// fixtureHandler serves one fixture for every request.
func fixtureHandler(t *testing.T, name string) func(w http.ResponseWriter, r *http.Request, n int) {
	f := loadFixture(t, name)
	return func(w http.ResponseWriter, _ *http.Request, _ int) { f.serve(w) }
}

// fakeIngest is an in-memory ingesting host: it checks the Bearer token, decodes JSON,
// NDJSON, MessagePack (optionally gzip) and stores the events.
type fakeIngest struct {
	mu       sync.Mutex
	events   []map[string]any
	types    []string
	encoding []string
	bodies   int
}

func (f *fakeIngest) handler(w http.ResponseWriter, r *http.Request, _ int) {
	if r.Header.Get("Authorization") != "Bearer "+testToken {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte("Unauthorized"))
		return
	}
	var body io.Reader = r.Body
	if r.Header.Get("Content-Encoding") == "gzip" {
		zr, err := gzip.NewReader(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusNotAcceptable)
			return
		}
		body = zr
	}
	data, _ := io.ReadAll(body)
	ct := r.Header.Get("Content-Type")
	var evs []map[string]any
	var err error
	switch ct {
	case contentTypeJSON:
		var v any
		err = json.Unmarshal(data, &v)
		switch x := v.(type) {
		case []any:
			for _, e := range x {
				evs = append(evs, e.(map[string]any))
			}
		case map[string]any:
			evs = append(evs, x)
		}
	case contentTypeNDJSON:
		for _, line := range bytes.Split(bytes.TrimSpace(data), []byte("\n")) {
			var m map[string]any
			if err = json.Unmarshal(line, &m); err != nil {
				break
			}
			evs = append(evs, m)
		}
	case contentTypeMsgPack:
		var v any
		v, _, err = decodeMsgPack(data)
		if arr, ok := v.([]any); ok {
			for _, e := range arr {
				evs = append(evs, e.(map[string]any))
			}
		}
	default:
		err = fmt.Errorf("content type %q", ct)
	}
	if err != nil {
		w.WriteHeader(http.StatusNotAcceptable)
		_, _ = w.Write([]byte("Couldn't parse JSON content."))
		return
	}
	f.mu.Lock()
	f.events = append(f.events, evs...)
	f.types = append(f.types, ct)
	f.encoding = append(f.encoding, r.Header.Get("Content-Encoding"))
	f.bodies++
	f.mu.Unlock()
	w.WriteHeader(http.StatusAccepted)
}

func (f *fakeIngest) snapshot() ([]map[string]any, []string, []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]map[string]any(nil), f.events...), append([]string(nil), f.types...), append([]string(nil), f.encoding...)
}

// decodeMsgPack is a minimal MessagePack decoder for the test server (the subset the
// encoder writes); timestamps decode to time.Time.
func decodeMsgPack(b []byte) (any, []byte, error) {
	if len(b) == 0 {
		return nil, nil, io.ErrUnexpectedEOF
	}
	c, b := b[0], b[1:]
	readN := func(n int) ([]byte, error) {
		if len(b) < n {
			return nil, io.ErrUnexpectedEOF
		}
		out := b[:n]
		b = b[n:]
		return out, nil
	}
	seq := func(n int, isMap bool) (any, []byte, error) {
		if isMap {
			m := map[string]any{}
			for i := 0; i < n; i++ {
				k, rest, err := decodeMsgPack(b)
				if err != nil {
					return nil, nil, err
				}
				v, rest2, err := decodeMsgPack(rest)
				if err != nil {
					return nil, nil, err
				}
				m[k.(string)] = v
				b = rest2
			}
			return m, b, nil
		}
		arr := make([]any, 0, n)
		for i := 0; i < n; i++ {
			v, rest, err := decodeMsgPack(b)
			if err != nil {
				return nil, nil, err
			}
			arr = append(arr, v)
			b = rest
		}
		return arr, b, nil
	}
	str := func(n int) (any, []byte, error) {
		s, err := readN(n)
		return string(s), b, err
	}
	ts := func(n int) (any, []byte, error) {
		d, err := readN(n + 1)
		if err != nil {
			return nil, nil, err
		}
		if int8(d[0]) != -1 {
			return nil, nil, fmt.Errorf("ext %d", int8(d[0]))
		}
		d = d[1:]
		switch n {
		case 4:
			return time.Unix(int64(binary.BigEndian.Uint32(d)), 0).UTC(), b, nil
		case 8:
			v := binary.BigEndian.Uint64(d)
			return time.Unix(int64(v&(1<<34-1)), int64(v>>34)).UTC(), b, nil
		default:
			return time.Unix(int64(binary.BigEndian.Uint64(d[4:])), int64(binary.BigEndian.Uint32(d[:4]))).UTC(), b, nil
		}
	}
	switch {
	case c <= 0x7f:
		return int64(c), b, nil
	case c >= 0xe0:
		return int64(int8(c)), b, nil
	case c&0xe0 == 0xa0:
		return str(int(c & 0x1f))
	case c&0xf0 == 0x90:
		return seq(int(c&0x0f), false)
	case c&0xf0 == 0x80:
		return seq(int(c&0x0f), true)
	}
	switch c {
	case 0xc0:
		return nil, b, nil
	case 0xc2:
		return false, b, nil
	case 0xc3:
		return true, b, nil
	case 0xcb:
		d, err := readN(8)
		if err != nil {
			return nil, nil, err
		}
		return math.Float64frombits(binary.BigEndian.Uint64(d)), b, nil
	case 0xd2:
		d, err := readN(4)
		if err != nil {
			return nil, nil, err
		}
		return int64(int32(binary.BigEndian.Uint32(d))), b, nil
	case 0xd3:
		d, err := readN(8)
		if err != nil {
			return nil, nil, err
		}
		return int64(binary.BigEndian.Uint64(d)), b, nil
	case 0xd9:
		d, err := readN(1)
		if err != nil {
			return nil, nil, err
		}
		return str(int(d[0]))
	case 0xda:
		d, err := readN(2)
		if err != nil {
			return nil, nil, err
		}
		return str(int(binary.BigEndian.Uint16(d)))
	case 0xdb:
		d, err := readN(4)
		if err != nil {
			return nil, nil, err
		}
		return str(int(binary.BigEndian.Uint32(d)))
	case 0xdc, 0xde:
		d, err := readN(2)
		if err != nil {
			return nil, nil, err
		}
		return seq(int(binary.BigEndian.Uint16(d)), c == 0xde)
	case 0xdd, 0xdf:
		d, err := readN(4)
		if err != nil {
			return nil, nil, err
		}
		return seq(int(binary.BigEndian.Uint32(d)), c == 0xdf)
	case 0xd6:
		return ts(4)
	case 0xd7:
		return ts(8)
	case 0xc7:
		d, err := readN(1)
		if err != nil {
			return nil, nil, err
		}
		return ts(int(d[0]))
	}
	return nil, nil, fmt.Errorf("msgpack byte 0x%x", c)
}

// newQueryClient builds a query-only client for url with table testTable.
func newQueryClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	return newTestClient(t, "", func(cfg *Config) {
		cfg.URL, cfg.SourceToken = "", Secret{}
		cfg.QueryURL, cfg.QueryUsername, cfg.QueryPassword = url, testQueryUser, NewSecret(testQueryPassword)
		cfg.SourceTable = testTable
		if mutate != nil {
			mutate(cfg)
		}
	})
}

// sqlServer is a query host that records the SQL bodies and answers with handler.
type sqlServer struct {
	*testServer
	mu   sync.Mutex
	sqls []string
}

func newSQLServer(t *testing.T, handler func(w http.ResponseWriter, r *http.Request, sql string, n int)) *sqlServer {
	t.Helper()
	s := &sqlServer{}
	s.testServer = newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		b, _ := io.ReadAll(r.Body)
		s.mu.Lock()
		s.sqls = append(s.sqls, string(b))
		s.mu.Unlock()
		handler(w, r, string(b), n)
	})
	return s
}

func (s *sqlServer) sql(i int) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if i < 0 {
		i = len(s.sqls) + i
	}
	return s.sqls[i]
}
