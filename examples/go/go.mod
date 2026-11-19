module github.com/grow-codes-packages/better-stack-logs/examples/go

go 1.26.0

require github.com/grow-codes-packages/better-stack-logs/go v0.0.0

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	go.opentelemetry.io/otel v1.47.0 // indirect
	go.opentelemetry.io/otel/trace v1.47.0 // indirect
	go.opentelemetry.io/proto/otlp v1.11.0 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
)

replace github.com/grow-codes-packages/better-stack-logs/go => ../../go
