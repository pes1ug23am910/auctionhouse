#!/bin/sh
set -eu
case "${1:-app}" in
  app)
    shift
    case "${AUCTIONHOUSE_OTEL_ENABLED:-false}" in
      true)
        : "${OTEL_EXPORTER_OTLP_ENDPOINT:?Set an explicit private collector endpoint}"
        export OTEL_JAVAAGENT_CONFIGURATION_FILE="${OTEL_JAVAAGENT_CONFIGURATION_FILE:-/app/observability/agent.properties}"
        exec java -javaagent:/opt/otel/opentelemetry-javaagent.jar -cp '/app/classes:/app/lib/*' io.auctionhouse.AuctionhouseApplication "$@" ;;
      false) exec java -cp '/app/classes:/app/lib/*' io.auctionhouse.AuctionhouseApplication "$@" ;;
      *) echo 'AUCTIONHOUSE_OTEL_ENABLED must be true or false' >&2; exit 64 ;;
    esac ;;
  migrate) shift; exec java -cp '/app/classes:/app/lib/*' io.auctionhouse.ops.Migrate "$@" ;;
  *) echo 'Expected app or migrate' >&2; exit 64 ;;
esac
