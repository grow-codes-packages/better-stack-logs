//! `LogRecord` → Better Stack event mapping and the three ingest encodings (JSON, NDJSON,
//! MessagePack with the timestamp extension), API.md §3.

use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value};

use crate::config::Encoding;
use crate::core::{Error, Result};
use crate::logging::LogRecord;

/// RFC 3339 UTC with nanoseconds (`2026-11-18T21:00:00.123456789Z`), the `dt` form for
/// JSON and NDJSON. Times before the epoch are clamped to it.
pub fn format_event_time(t: SystemTime) -> String {
    let t = if t < UNIX_EPOCH { UNIX_EPOCH } else { t };
    humantime::format_rfc3339_nanos(t).to_string()
}

/// Maps a record to the Better Stack event object (the JSON stored in the `raw` column):
/// `{dt, message, level, severity_number, span: {trace_id, span_id}, trace_flags, resource,
/// attributes, observed_dt}`. `span.trace_id` / `span.span_id` are Better Stack's log ↔ trace
/// correlation fields. Empty `resource` / `attributes` are left out.
pub fn to_better_stack_event(record: &LogRecord) -> Map<String, Value> {
    let mut ev = Map::new();
    ev.insert("dt".into(), format_event_time(record.timestamp).into());
    ev.insert("message".into(), record.body.clone().into());
    let level = if record.severity_text.is_empty() {
        record.severity.text()
    } else {
        record.severity_text.as_str()
    };
    ev.insert("level".into(), level.to_lowercase().into());
    ev.insert("severity_number".into(), record.severity.number().into());
    if record.trace_id.is_some() || record.span_id.is_some() {
        let mut span = Map::new();
        if let Some(t) = &record.trace_id {
            span.insert("trace_id".into(), t.clone().into());
        }
        if let Some(s) = &record.span_id {
            span.insert("span_id".into(), s.clone().into());
        }
        ev.insert("span".into(), Value::Object(span));
    }
    if let Some(f) = record.trace_flags {
        ev.insert("trace_flags".into(), f.into());
    }
    if !record.resource.is_empty() {
        ev.insert(
            "resource".into(),
            Value::Object(record.resource.clone().into_iter().collect()),
        );
    }
    if !record.attributes.is_empty() {
        ev.insert(
            "attributes".into(),
            Value::Object(record.attributes.clone().into_iter().collect()),
        );
    }
    if let Some(o) = record.observed_timestamp {
        ev.insert("observed_dt".into(), format_event_time(o).into());
    }
    ev
}

/// Encodes one record as an event for `encoding`: the JSON object for JSON/NDJSON, the
/// MessagePack map (with `dt` / `observed_dt` as timestamp extensions) for MessagePack.
pub fn encode_record(record: &LogRecord, encoding: Encoding) -> Vec<u8> {
    let event = to_better_stack_event(record);
    match encoding {
        Encoding::Msgpack => {
            let mut w = Vec::with_capacity(128);
            write_map_header(&mut w, event.len());
            for (k, v) in &event {
                write_str(&mut w, k);
                match (k.as_str(), record.observed_timestamp) {
                    ("dt", _) => write_timestamp(&mut w, record.timestamp),
                    ("observed_dt", Some(o)) => write_timestamp(&mut w, o),
                    _ => write_value(&mut w, v),
                }
            }
            w
        }
        Encoding::Json | Encoding::Ndjson => Value::Object(event).to_string().into_bytes(),
    }
}

/// Encodes one raw event (a JSON object) for `encoding`. `VALIDATION` when it is not an object.
pub fn encode_event(event: &Value, encoding: Encoding) -> Result<Vec<u8>> {
    if !event.is_object() {
        return Err(Error::validation(
            "logs.sendEvents: every event must be a JSON object",
        ));
    }
    Ok(match encoding {
        Encoding::Msgpack => encode_msgpack(event),
        Encoding::Json | Encoding::Ndjson => event.to_string().into_bytes(),
    })
}

/// Upper bound of the framing bytes per request (array header, brackets).
pub(crate) const FRAME_OVERHEAD: usize = 8;

/// Joins encoded events into one request body.
pub fn frame(encoding: Encoding, events: &[Vec<u8>]) -> Vec<u8> {
    let total: usize = events.iter().map(|e| e.len() + 1).sum::<usize>() + FRAME_OVERHEAD;
    let mut out = Vec::with_capacity(total);
    match encoding {
        Encoding::Json => {
            out.push(b'[');
            for (i, e) in events.iter().enumerate() {
                if i > 0 {
                    out.push(b',');
                }
                out.extend_from_slice(e);
            }
            out.push(b']');
        }
        Encoding::Ndjson => {
            for e in events {
                out.extend_from_slice(e);
                out.push(b'\n');
            }
        }
        Encoding::Msgpack => {
            write_array_header(&mut out, events.len());
            for e in events {
                out.extend_from_slice(e);
            }
        }
    }
    out
}

// --- MessagePack ---------------------------------------------------------------------------

/// Encodes a JSON value as MessagePack.
pub fn encode_msgpack(value: &Value) -> Vec<u8> {
    let mut w = Vec::with_capacity(64);
    write_value(&mut w, value);
    w
}

fn write_len(
    w: &mut Vec<u8>,
    n: usize,
    fix: Option<(u8, usize)>,
    c8: Option<u8>,
    c16: u8,
    c32: u8,
) {
    match fix {
        Some((base, max)) if n <= max => w.push(base | n as u8),
        _ => match c8 {
            Some(c) if n < 256 => w.extend_from_slice(&[c, n as u8]),
            _ if n < 65_536 => {
                w.push(c16);
                w.extend_from_slice(&(n as u16).to_be_bytes());
            }
            _ => {
                w.push(c32);
                w.extend_from_slice(&(n as u32).to_be_bytes());
            }
        },
    }
}

fn write_array_header(w: &mut Vec<u8>, n: usize) {
    write_len(w, n, Some((0x90, 15)), None, 0xdc, 0xdd);
}

fn write_map_header(w: &mut Vec<u8>, n: usize) {
    write_len(w, n, Some((0x80, 15)), None, 0xde, 0xdf);
}

fn write_str(w: &mut Vec<u8>, s: &str) {
    write_len(w, s.len(), Some((0xa0, 31)), Some(0xd9), 0xda, 0xdb);
    w.extend_from_slice(s.as_bytes());
}

fn write_int(w: &mut Vec<u8>, v: i64) {
    if v >= 0 {
        write_uint(w, v as u64);
    } else if v >= -32 {
        w.push(v as u8);
    } else if v >= -128 {
        w.extend_from_slice(&[0xd0, v as u8]);
    } else if v >= -32_768 {
        w.push(0xd1);
        w.extend_from_slice(&(v as i16).to_be_bytes());
    } else if v >= -2_147_483_648 {
        w.push(0xd2);
        w.extend_from_slice(&(v as i32).to_be_bytes());
    } else {
        w.push(0xd3);
        w.extend_from_slice(&v.to_be_bytes());
    }
}

fn write_uint(w: &mut Vec<u8>, v: u64) {
    if v < 128 {
        w.push(v as u8);
    } else if v < 256 {
        w.extend_from_slice(&[0xcc, v as u8]);
    } else if v < 65_536 {
        w.push(0xcd);
        w.extend_from_slice(&(v as u16).to_be_bytes());
    } else if v < 4_294_967_296 {
        w.push(0xce);
        w.extend_from_slice(&(v as u32).to_be_bytes());
    } else {
        w.push(0xcf);
        w.extend_from_slice(&v.to_be_bytes());
    }
}

fn write_value(w: &mut Vec<u8>, value: &Value) {
    match value {
        Value::Null => w.push(0xc0),
        Value::Bool(b) => w.push(if *b { 0xc3 } else { 0xc2 }),
        Value::Number(n) => {
            if let Some(u) = n.as_u64() {
                write_uint(w, u);
            } else if let Some(i) = n.as_i64() {
                write_int(w, i);
            } else {
                w.push(0xcb);
                w.extend_from_slice(&n.as_f64().unwrap_or(0.0).to_be_bytes());
            }
        }
        Value::String(s) => write_str(w, s),
        Value::Array(items) => {
            write_array_header(w, items.len());
            for item in items {
                write_value(w, item);
            }
        }
        Value::Object(map) => {
            write_map_header(w, map.len());
            for (k, v) in map {
                write_str(w, k);
                write_value(w, v);
            }
        }
    }
}

/// MessagePack timestamp extension (type -1): timestamp32 / 64 / 96 as needed.
fn write_timestamp(w: &mut Vec<u8>, t: SystemTime) {
    let (sec, nsec) = match t.duration_since(UNIX_EPOCH) {
        Ok(d) => (d.as_secs() as i64, d.subsec_nanos()),
        Err(e) => {
            let d = e.duration();
            let mut sec = -(d.as_secs() as i64);
            let mut nsec = d.subsec_nanos();
            if nsec > 0 {
                sec -= 1;
                nsec = 1_000_000_000 - nsec;
            }
            (sec, nsec)
        }
    };
    if (0..1 << 34).contains(&sec) {
        if nsec == 0 && sec < 1 << 32 {
            w.extend_from_slice(&[0xd6, 0xff]);
            w.extend_from_slice(&(sec as u32).to_be_bytes());
        } else {
            w.extend_from_slice(&[0xd7, 0xff]);
            let v = (u64::from(nsec) << 34) | sec as u64;
            w.extend_from_slice(&v.to_be_bytes());
        }
    } else {
        w.extend_from_slice(&[0xc7, 12, 0xff]);
        w.extend_from_slice(&nsec.to_be_bytes());
        w.extend_from_slice(&sec.to_be_bytes());
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn timestamps_cover_every_width() {
        let mut w = Vec::new();
        write_timestamp(&mut w, UNIX_EPOCH + Duration::from_secs(5));
        assert_eq!(w, [0xd6, 0xff, 0, 0, 0, 5]);
        w.clear();
        write_timestamp(&mut w, UNIX_EPOCH + Duration::new(5, 1));
        assert_eq!(w[..2], [0xd7, 0xff]);
        w.clear();
        write_timestamp(&mut w, UNIX_EPOCH - Duration::new(1, 500));
        assert_eq!(w[..3], [0xc7, 12, 0xff]);
        assert_eq!(&w[3..7], &999_999_500u32.to_be_bytes());
        assert_eq!(&w[7..], &(-2i64).to_be_bytes());
        w.clear();
        write_timestamp(&mut w, UNIX_EPOCH - Duration::from_secs(3));
        assert_eq!(&w[7..], &(-3i64).to_be_bytes());
    }

    #[test]
    fn integers_and_lengths() {
        let enc = |v: Value| encode_msgpack(&v);
        assert_eq!(enc(Value::from(-1)), [0xff]);
        assert_eq!(enc(Value::from(-100)), [0xd0, 0x9c]);
        assert_eq!(enc(Value::from(-1000))[0], 0xd1);
        assert_eq!(enc(Value::from(-100_000))[0], 0xd2);
        assert_eq!(enc(Value::from(-10_000_000_000i64))[0], 0xd3);
        assert_eq!(enc(Value::from(200))[0], 0xcc);
        assert_eq!(enc(Value::from(1000))[0], 0xcd);
        assert_eq!(enc(Value::from(100_000))[0], 0xce);
        assert_eq!(enc(Value::from(10_000_000_000u64))[0], 0xcf);
        assert_eq!(enc(Value::from(1.5))[0], 0xcb);
        assert_eq!(enc(Value::Null), [0xc0]);
        assert_eq!(enc(Value::Bool(false)), [0xc2]);
        assert_eq!(enc(Value::from("x".repeat(40)))[0], 0xd9);
        assert_eq!(enc(Value::from("x".repeat(300)))[0], 0xda);
        assert_eq!(enc(Value::from("x".repeat(70_000)))[0], 0xdb);
        assert_eq!(enc(Value::Array(vec![Value::Null; 20]))[0], 0xdc);
        assert_eq!(enc(Value::Array(vec![Value::Null; 70_000]))[0], 0xdd);
        let big: Map<String, Value> = (0..20).map(|i| (i.to_string(), Value::Null)).collect();
        assert_eq!(enc(Value::Object(big))[0], 0xde);
        let huge: Map<String, Value> = (0..70_000).map(|i| (i.to_string(), Value::Null)).collect();
        assert_eq!(enc(Value::Object(huge))[0], 0xdf);
    }
}
