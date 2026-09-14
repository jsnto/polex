# Build stage
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /polex-backend .

# Runtime stage
FROM alpine:3.20
# iputils-ping: 'ping' health checks (Check == "ping") shell out to the
# system ping binary (non-root Go can't open raw ICMP sockets; alpine's
# ping is setuid). Without it every ping check reports offline.
RUN apk add --no-cache ca-certificates tzdata iputils-ping && adduser -D -u 10001 app
COPY --from=build /polex-backend /usr/local/bin/polex-backend
USER app
# The binary persists its config to data.json in the working directory.
WORKDIR /app/data
VOLUME /app/data
ENTRYPOINT ["polex-backend"]
