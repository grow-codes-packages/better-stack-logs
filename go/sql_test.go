package betterstack

import (
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"
)

func marshalJSONForTest(v any) (string, error) {
	b, err := json.Marshal(v)
	return string(b), err
}

type textValue struct{}

func (textValue) String() string { return "t\\x" }

func TestEscapeParamValue(t *testing.T) {
	cases := map[string]string{
		`plain 'quoted' "text"`: `plain 'quoted' "text"`,
		"a\\b\tc\nd\re":         `a\\b\tc\nd\re`,
		`\N`:                    `\\N`,
	}
	for in, want := range cases {
		if got := EscapeParamValue(in); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
	}
}

func TestParamText(t *testing.T) {
	ts := time.Date(2026, 11, 18, 21, 0, 0, 123456789, time.FixedZone("x", 3600))
	cases := []struct {
		v    any
		want string
	}{
		{nil, `\N`}, {`C:\tmp`, `C:\\tmp`}, {true, "true"}, {false, "false"},
		{-7, "-7"}, {int8(8), "8"}, {int16(9), "9"}, {int32(10), "10"}, {int64(11), "11"},
		{uint(12), "12"}, {uint8(13), "13"}, {uint16(14), "14"}, {uint32(15), "15"}, {uint64(16), "16"},
		{float32(0.5), "0.5"}, {1.25, "1.25"}, {json.Number("42"), "42"}, {textValue{}, `t\\x`},
		{ts, "2026-11-18 20:00:00.123456"},
	}
	for _, tc := range cases {
		got, err := paramText(tc.v)
		if err != nil || got != tc.want {
			t.Errorf("%#v: got %q %v want %q", tc.v, got, err, tc.want)
		}
	}
	for _, bad := range []any{math.NaN(), math.Inf(1), float32(math.Inf(-1)), map[string]any{}, []string{"x"}} {
		if _, err := paramText(bad); err == nil {
			t.Errorf("%#v: want error", bad)
		}
	}
	if QuoteSQLString(`'\`) != `'\'\\'` || !strings.HasPrefix(FormatSQLTime(time.Unix(0, 0)), "1970-01-01 00:00:00.000000") {
		t.Fatal("quote")
	}
}
