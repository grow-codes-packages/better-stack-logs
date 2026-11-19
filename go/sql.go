package betterstack

import (
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

// paramEscaper escapes a query parameter value for ClickHouse, which parses
// param_<name> values in the escaped (TabSeparated) text format.
var paramEscaper = strings.NewReplacer(`\`, `\\`, "\t", `\t`, "\n", `\n`, "\r", `\r`)

// EscapeParamValue escapes s for a param_<name> URL parameter: ClickHouse parses query
// parameter values in the escaped (TabSeparated) text format, so backslash → \\,
// tab → \t, newline → \n and carriage return → \r (DECISIONS 12). Query applies it to
// every string value.
func EscapeParamValue(s string) string {
	return paramEscaper.Replace(s)
}

// paramText renders a Query parameter value as the text of its param_<name> URL
// parameter: nil → \N (NULL), strings, json.Number and fmt.Stringer → escaped text
// (EscapeParamValue), bool → true/false, integers and finite floats → numbers,
// time.Time → UTC "YYYY-MM-DD hh:mm:ss.ffffff". Other types (and NaN/Inf) are an error.
func paramText(v any) (string, error) {
	switch x := v.(type) {
	case nil:
		return `\N`, nil
	case string:
		return EscapeParamValue(x), nil
	case bool:
		return strconv.FormatBool(x), nil
	case int:
		return strconv.Itoa(x), nil
	case int8:
		return strconv.FormatInt(int64(x), 10), nil
	case int16:
		return strconv.FormatInt(int64(x), 10), nil
	case int32:
		return strconv.FormatInt(int64(x), 10), nil
	case int64:
		return strconv.FormatInt(x, 10), nil
	case uint:
		return strconv.FormatUint(uint64(x), 10), nil
	case uint8:
		return strconv.FormatUint(uint64(x), 10), nil
	case uint16:
		return strconv.FormatUint(uint64(x), 10), nil
	case uint32:
		return strconv.FormatUint(uint64(x), 10), nil
	case uint64:
		return strconv.FormatUint(x, 10), nil
	case float32:
		return formatParamFloat(float64(x))
	case float64:
		return formatParamFloat(x)
	case json.Number:
		return EscapeParamValue(x.String()), nil
	case time.Time:
		return FormatSQLTime(x), nil
	case fmt.Stringer:
		return EscapeParamValue(x.String()), nil
	}
	return "", fmt.Errorf("unsupported value type %T", v)
}

func formatParamFloat(f float64) (string, error) {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return "", fmt.Errorf("must be a finite number")
	}
	return strconv.FormatFloat(f, 'g', -1, 64), nil
}

// sqlStringEscaper escapes backslash, single quote, newline, carriage return, tab and NUL.
var sqlStringEscaper = strings.NewReplacer(`\`, `\\`, `'`, `\'`, "\n", `\n`, "\r", `\r`, "\t", `\t`, "\x00", `\0`)

// QuoteSQLString returns s as a single-quoted ClickHouse string literal (backslash,
// single quote, newline, carriage return, tab and NUL escaped with a backslash).
func QuoteSQLString(s string) string {
	return "'" + sqlStringEscaper.Replace(s) + "'"
}

// FormatSQLTime renders t as a UTC "YYYY-MM-DD hh:mm:ss.ffffff" string (the dt format).
func FormatSQLTime(t time.Time) string {
	return t.UTC().Format("2006-01-02 15:04:05.000000")
}
