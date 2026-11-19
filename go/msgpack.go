package betterstack

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"math"
	"sort"
	"time"
)

// marshalMsgPack encodes v as MessagePack. Supported values: nil, bool, string,
// int64 (and other ints), float64, time.Time (timestamp extension, type -1),
// []any and map[string]any (keys sorted for stable output). Anything else is
// normalised with NormalizeAttrValue first.
func marshalMsgPack(v any) ([]byte, error) {
	var b bytes.Buffer
	if err := writeMsgPack(&b, v, 0); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

func writeMsgPack(b *bytes.Buffer, v any, depth int) error {
	if depth > 64 {
		return fmt.Errorf("msgpack: value nested too deeply")
	}
	switch x := v.(type) {
	case nil:
		b.WriteByte(0xc0)
	case bool:
		if x {
			b.WriteByte(0xc3)
		} else {
			b.WriteByte(0xc2)
		}
	case string:
		writeMsgPackString(b, x)
	case int:
		writeMsgPackInt(b, int64(x))
	case int64:
		writeMsgPackInt(b, x)
	case float64:
		b.WriteByte(0xcb)
		_ = binary.Write(b, binary.BigEndian, math.Float64bits(x))
	case time.Time:
		writeMsgPackTime(b, x)
	case []any:
		writeMsgPackArrayHeader(b, len(x))
		for _, e := range x {
			if err := writeMsgPack(b, e, depth+1); err != nil {
				return err
			}
		}
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		writeMsgPackMapHeader(b, len(keys))
		for _, k := range keys {
			writeMsgPackString(b, k)
			if err := writeMsgPack(b, x[k], depth+1); err != nil {
				return err
			}
		}
	default:
		n := NormalizeAttrValue(v)
		switch n.(type) {
		case string, bool, int64, float64, []any, map[string]any, nil:
			return writeMsgPack(b, n, depth+1)
		}
		return fmt.Errorf("msgpack: unsupported type %T", v)
	}
	return nil
}

func writeMsgPackInt(b *bytes.Buffer, i int64) {
	switch {
	case i >= 0 && i <= 0x7f:
		b.WriteByte(byte(i))
	case i < 0 && i >= -32:
		b.WriteByte(byte(int8(i))) //nolint:gosec // -32 <= i < 0, negative fixint
	case i >= math.MinInt32 && i <= math.MaxInt32:
		b.WriteByte(0xd2)
		_ = binary.Write(b, binary.BigEndian, int32(i))
	default:
		b.WriteByte(0xd3)
		_ = binary.Write(b, binary.BigEndian, i)
	}
}

func writeMsgPackString(b *bytes.Buffer, s string) {
	n := len(s)
	switch {
	case n <= 31:
		b.WriteByte(0xa0 | byte(n))
	case n <= math.MaxUint8:
		b.WriteByte(0xd9)
		b.WriteByte(byte(n))
	case n <= math.MaxUint16:
		b.WriteByte(0xda)
		_ = binary.Write(b, binary.BigEndian, uint16(n))
	default:
		b.WriteByte(0xdb)
		_ = binary.Write(b, binary.BigEndian, uint32(n)) //nolint:gosec // records are capped at 10 MiB
	}
	b.WriteString(s)
}

func writeMsgPackArrayHeader(b *bytes.Buffer, n int) {
	switch {
	case n <= 15:
		b.WriteByte(0x90 | byte(n)) //nolint:gosec // n <= 15
	case n <= math.MaxUint16:
		b.WriteByte(0xdc)
		_ = binary.Write(b, binary.BigEndian, uint16(n))
	default:
		b.WriteByte(0xdd)
		_ = binary.Write(b, binary.BigEndian, uint32(n)) //nolint:gosec // bounded by the batch size
	}
}

func writeMsgPackMapHeader(b *bytes.Buffer, n int) {
	switch {
	case n <= 15:
		b.WriteByte(0x80 | byte(n)) //nolint:gosec // n <= 15
	case n <= math.MaxUint16:
		b.WriteByte(0xde)
		_ = binary.Write(b, binary.BigEndian, uint16(n))
	default:
		b.WriteByte(0xdf)
		_ = binary.Write(b, binary.BigEndian, uint32(n)) //nolint:gosec // bounded by the record size
	}
}

// writeMsgPackTime writes the timestamp extension (type -1) in its 32, 64 or 96-bit form.
func writeMsgPackTime(b *bytes.Buffer, t time.Time) {
	sec, nsec := t.Unix(), int64(t.Nanosecond())
	switch {
	case nsec == 0 && sec >= 0 && sec <= math.MaxUint32:
		b.Write([]byte{0xd6, 0xff})
		_ = binary.Write(b, binary.BigEndian, uint32(sec))
	case sec >= 0 && sec < 1<<34:
		b.Write([]byte{0xd7, 0xff})
		_ = binary.Write(b, binary.BigEndian, uint64(nsec)<<34|uint64(sec)) //nolint:gosec // 0 <= sec < 2^34, 0 <= nsec < 1e9
	default:
		b.Write([]byte{0xc7, 12, 0xff})
		_ = binary.Write(b, binary.BigEndian, uint32(nsec)) //nolint:gosec // 0 <= nsec < 1e9
		_ = binary.Write(b, binary.BigEndian, sec)
	}
}
