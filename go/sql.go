package betterstack

import (
	"encoding/json"
	"fmt"
	"math"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// placeholderRe matches a ClickHouse query parameter placeholder {name:Type} at the
// start of the input.
var placeholderRe = regexp.MustCompile(`^\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^{}]+?)\s*\}`)

// BindParams replaces every ClickHouse placeholder {name:Type} outside string literals,
// quoted identifiers and comments with the escaped SQL literal of params[name] (see
// FormatSQLValue). Better Stack's acceptance of server-side parameter binding is not
// verified yet, so values are bound client side; the API keeps ClickHouse's
// placeholder syntax so server-side binding can replace this without changes for
// callers. A placeholder without a value is a VALIDATION error.
func BindParams(sql string, params map[string]any) (string, error) {
	var b strings.Builder
	b.Grow(len(sql))
	for i := 0; i < len(sql); {
		c := sql[i]
		switch {
		case c == '\'' || c == '"' || c == '`':
			j := skipQuoted(sql, i)
			b.WriteString(sql[i:j])
			i = j
		case c == '-' && strings.HasPrefix(sql[i:], "--"):
			j := strings.IndexByte(sql[i:], '\n')
			if j < 0 {
				j = len(sql) - i
			}
			b.WriteString(sql[i : i+j])
			i += j
		case c == '/' && strings.HasPrefix(sql[i:], "/*"):
			j := strings.Index(sql[i+2:], "*/")
			end := len(sql)
			if j >= 0 {
				end = i + 2 + j + 2
			}
			b.WriteString(sql[i:end])
			i = end
		case c == '{':
			m := placeholderRe.FindStringSubmatch(sql[i:])
			if m == nil {
				b.WriteByte(c)
				i++
				continue
			}
			v, ok := params[m[1]]
			if !ok {
				return "", ValidationError("query", "no value for query parameter "+strconv.Quote(m[1]))
			}
			lit, err := FormatSQLValue(v, m[2])
			if err != nil {
				return "", ValidationError("query", "parameter "+strconv.Quote(m[1])+": "+err.Error())
			}
			b.WriteString(lit)
			i += len(m[0])
		default:
			b.WriteByte(c)
			i++
		}
	}
	return b.String(), nil
}

// skipQuoted returns the index after the quoted section that starts at i (backslash
// escapes and doubled quotes are honoured; an unterminated section runs to the end).
func skipQuoted(s string, i int) int {
	q := s[i]
	for j := i + 1; j < len(s); j++ {
		switch s[j] {
		case '\\':
			j++
		case q:
			if j+1 < len(s) && s[j+1] == q {
				j++
				continue
			}
			return j + 1
		}
	}
	return len(s)
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

// unwrapType strips Nullable(...) and LowCardinality(...) from a ClickHouse type.
func unwrapType(typ string) string {
	t := strings.TrimSpace(typ)
	for _, w := range []string{"Nullable(", "LowCardinality("} {
		for strings.HasPrefix(t, w) && strings.HasSuffix(t, ")") {
			t = strings.TrimSpace(t[len(w) : len(t)-1])
		}
	}
	return t
}

// FormatSQLValue renders v as a ClickHouse literal for a parameter of type typ:
// nil → NULL; strings, fmt.Stringer and json.Number → quoted strings (a bare
// identifier for type Identifier, which must match ^[A-Za-z_][A-Za-z0-9_]*$);
// bool → true/false; integers and floats → numbers (nan, inf, -inf); time.Time → a
// quoted UTC "YYYY-MM-DD hh:mm:ss.ffffff" string ("YYYY-MM-DD" for Date types);
// slices and arrays → [a, b, …]. Maps are rejected.
func FormatSQLValue(v any, typ string) (string, error) {
	t := unwrapType(typ)
	if v == nil {
		return "NULL", nil
	}
	if t == "Identifier" {
		s, ok := v.(string)
		if !ok || !IsValidIdentifier(s) {
			return "", fmt.Errorf("an Identifier must match ^[A-Za-z_][A-Za-z0-9_]*$")
		}
		return s, nil
	}
	switch x := v.(type) {
	case string:
		return QuoteSQLString(x), nil
	case bool:
		return strconv.FormatBool(x), nil
	case int:
		return strconv.Itoa(x), nil
	case int64:
		return strconv.FormatInt(x, 10), nil
	case int32:
		return strconv.FormatInt(int64(x), 10), nil
	case uint64:
		return strconv.FormatUint(x, 10), nil
	case uint:
		return strconv.FormatUint(uint64(x), 10), nil
	case float64:
		return formatSQLFloat(x), nil
	case float32:
		return formatSQLFloat(float64(x)), nil
	case json.Number:
		return QuoteSQLString(x.String()), nil
	case time.Time:
		if strings.HasPrefix(t, "Date") && !strings.HasPrefix(t, "DateTime") {
			return QuoteSQLString(x.UTC().Format("2006-01-02")), nil
		}
		return QuoteSQLString(FormatSQLTime(x)), nil
	case fmt.Stringer:
		return QuoteSQLString(x.String()), nil
	}
	rv := reflect.ValueOf(v)
	switch rv.Kind() {
	case reflect.Slice, reflect.Array:
		elem := t
		if strings.HasPrefix(t, "Array(") && strings.HasSuffix(t, ")") {
			elem = t[len("Array(") : len(t)-1]
		}
		parts := make([]string, rv.Len())
		for i := range parts {
			s, err := FormatSQLValue(rv.Index(i).Interface(), elem)
			if err != nil {
				return "", err
			}
			parts[i] = s
		}
		return "[" + strings.Join(parts, ", ") + "]", nil
	case reflect.Int8, reflect.Int16:
		return strconv.FormatInt(rv.Int(), 10), nil
	case reflect.Uint8, reflect.Uint16, reflect.Uint32:
		return strconv.FormatUint(rv.Uint(), 10), nil
	}
	return "", fmt.Errorf("unsupported value type %T", v)
}

func formatSQLFloat(f float64) string {
	switch {
	case math.IsNaN(f):
		return "nan"
	case math.IsInf(f, 1):
		return "inf"
	case math.IsInf(f, -1):
		return "-inf"
	}
	return strconv.FormatFloat(f, 'g', -1, 64)
}
