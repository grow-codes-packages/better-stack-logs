package betterstack

import (
	"encoding/json"
	"errors"
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

func (textValue) String() string { return "txt" }

func TestBindParams(t *testing.T) {
	ts := time.Date(2026, 11, 18, 21, 0, 0, 123456789, time.UTC)
	params := map[string]any{
		"s": `a'b\c`, "i": 3, "t": "t1_x_logs", "tm": ts, "d": ts, "n": nil, "b": true,
		"arr": []string{"x", "y"}, "f": 1.5,
	}
	sql := "SELECT {s:String}, {i:UInt32}, '{s:String}', `{s:String}`, \"{s:String}\" FROM remote({t:Identifier}) " +
		"WHERE dt > {tm:DateTime64(6)} AND day = {d:Date} AND x = {n:Nullable(String)} AND y = {b:Bool} " +
		"AND z IN {arr:Array(String)} AND f = { f : Float64 } -- {s:String}\n/* {s:String} */ AND {not a placeholder} 'it''s {s:String}' /* open"
	got, err := BindParams(sql, params)
	if err != nil {
		t.Fatal(err)
	}
	want := `SELECT 'a\'b\\c', 3, '{s:String}', ` + "`{s:String}`" + `, "{s:String}" FROM remote(t1_x_logs) ` +
		`WHERE dt > '2026-11-18 21:00:00.123456' AND day = '2026-11-18' AND x = NULL AND y = true ` +
		`AND z IN ['x', 'y'] AND f = 1.5 -- {s:String}` + "\n" + `/* {s:String} */ AND {not a placeholder} 'it''s {s:String}' /* open`
	if got != want {
		t.Fatalf("got\n%s\nwant\n%s", got, want)
	}
	if got, _ := BindParams("x -- {s:String}", params); got != "x -- {s:String}" {
		t.Fatalf("comment at end: %q", got)
	}
	if got, _ := BindParams("'unterminated {s:String}", params); got != "'unterminated {s:String}" {
		t.Fatalf("unterminated: %q", got)
	}
	if _, err := BindParams("{t:Identifier}", map[string]any{"t": "x; DROP"}); !errors.Is(err, ErrValidation) {
		t.Fatalf("identifier: %v", err)
	}
	if _, err := BindParams("{m:String}", map[string]any{"m": map[string]any{}}); !errors.Is(err, ErrValidation) {
		t.Fatalf("map: %v", err)
	}
	if _, err := BindParams("{a:Array(String)}", map[string]any{"a": []any{map[string]any{}}}); !errors.Is(err, ErrValidation) {
		t.Fatalf("array of maps: %v", err)
	}
}

func TestFormatSQLValue(t *testing.T) {
	cases := map[string]any{
		"-7": int64(-7), "8": int32(8), "9": uint64(9), "10": uint(10), "11": int8(11), "12": uint16(12),
		"0.5": float32(0.5), "nan": math.NaN(), "inf": math.Inf(1), "-inf": math.Inf(-1),
		"'42'": json.Number("42"), "'txt'": textValue{}, "false": false, "[1, 2]": [2]int{1, 2},
	}
	for want, v := range cases {
		got, err := FormatSQLValue(v, "String")
		if err != nil || got != want {
			t.Errorf("%v: %q %v", v, got, err)
		}
	}
	if got, _ := FormatSQLValue("x", "LowCardinality(Nullable(String))"); got != "'x'" {
		t.Fatal(got)
	}
	if QuoteSQLString(`'\`) != `'\'\\'` || !strings.HasPrefix(FormatSQLTime(time.Unix(0, 0)), "1970-01-01 00:00:00.000000") {
		t.Fatal("quote")
	}
}
