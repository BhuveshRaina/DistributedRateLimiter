# API Usage Examples

This directory contains code examples demonstrating how to integrate with the Distributed Rate Limiter API in various programming languages.

## 🎨 Interactive Web Dashboard

Try our comprehensive web dashboard for real-time monitoring and management:

📁 **Location**: [`/examples/web-dashboard`](../../examples/web-dashboard/)

### Features
- 📊 **Real-time Monitoring** - Live metrics, charts, and activity feeds
- 🔧 **Algorithm Comparison** - Interactive testing of all algorithms
- 🔑 **API Key Management** - Complete lifecycle management with usage tracking
- ⚙️ **Configuration UI** - Visual configuration management
- 🧪 **Load Testing** - Integrated performance testing suite

### Quick Start
```bash
cd examples/web-dashboard
npm install
npm run dev
# Dashboard available at http://localhost:5173
```

---

## Available Examples

### 🎨 Interactive Dashboard
- [Web Dashboard](../../examples/web-dashboard/README.md) - Full-featured monitoring and management UI

### 💻 Client Libraries
- [Java/Spring Boot](./java-client.md) - Complete integration example
- [Python](./python-client.md) - Simple requests-based client
- [Node.js](./nodejs-client.md) - Express.js middleware example
- [Go](./go-client.md) - Native HTTP client implementation

### 📝 Testing & Examples
- [cURL](./curl-examples.md) - Command-line testing examples
- [Leaky Bucket](./leaky-bucket-examples.md) - Traffic shaping examples
- [Composite Rate Limiting](../../examples/composite-rate-limiting.md) - Multi-algorithm examples

## Authentication

Most examples include optional API key authentication:

```bash
# Without API key (if not required)
curl -X POST http://localhost:8080/api/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"user:123","tokens":1}'

# With API key
curl -X POST http://localhost:8080/api/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"user:123","tokens":1,"apiKey":"your-api-key"}'
```

## Response Handling

All clients should handle these HTTP status codes:

- `200 OK` - Request allowed, proceed
- `429 Too Many Requests` - Rate limit exceeded, apply backoff
- `401 Unauthorized` - Invalid API key
- `403 Forbidden` - IP address blocked

Example response:
```json
{
  "key": "user:123",
  "tokensRequested": 1,
  "allowed": true
}
```
