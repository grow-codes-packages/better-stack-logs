module github.com/grow-codes-packages/better-stack-logs/go/integration

go 1.26.0

require github.com/grow-codes-packages/better-stack-logs/go v0.0.0

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	go.opentelemetry.io/otel v1.47.0 // indirect
	go.opentelemetry.io/otel/trace v1.47.0 // indirect
	go.opentelemetry.io/proto/otlp v1.11.1 // indirect
	google.golang.org/protobuf v1.36.12 // indirect
)

// The consumer module uses the package from this repository, exactly as a
// user would import it; docker/Dockerfile.go builds it this way.
replace github.com/grow-codes-packages/better-stack-logs/go => ../
