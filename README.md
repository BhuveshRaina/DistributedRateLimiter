<div align="center">

<img src="drl-logo.png" alt="Distributed Rate Limiter Logo" width="200" height="200">

# 🚀 Distributed Rate Limiter

**High-performance, Redis-backed rate limiter service with multiple algorithms and REST API**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7.x-red.svg)](https://redis.io/)

[📖 Documentation](#-documentation) • [🚀 Quick Start](#-quick-start) • [💡 Examples](#-examples)

</div>

---

## 🎯 Overview

A production-ready distributed rate limiter supporting **five algorithms** (Token Bucket, Sliding Window, Fixed Window, Leaky Bucket, and Composite) with Redis backing for high-performance API protection. Perfect for microservices, SaaS platforms, and any application requiring sophisticated rate limiting with algorithm flexibility, multi-dimensional limits, and traffic shaping capabilities.

### ✨ Key Features

- 🏃‍♂️ **High Performance**: 50,000+ requests/second with <2ms P95 latency
- 🎯 **Five Algorithms**: Token Bucket, Sliding Window, Fixed Window, Leaky Bucket, and Composite
- 🤖 **Adaptive Rate Limiting**: Automatic limit optimization based on AIMD policy and system health
- 🌐 **Distributed**: Redis-backed for multi-instance deployments
- 🛡️ **Thread Safe**: Concurrent request handling with atomic operations
- 📊 **Rich Metrics**: Built-in Prometheus metrics and performance monitoring
- 🔧 **Flexible Configuration**: Per-key limits, burst handling, and dynamic rules

### 📊 Performance Characteristics

| Metric | Value |
|--------|--------|
| **Throughput** | 50,000+ RPS |
| **Latency P95** | <2ms |
| **Memory Usage** | ~200MB baseline + buckets |
| **Redis Ops** | 2-3 per rate limit check |
| **CPU Usage** | <5% at 10K RPS |

---

## 📚 Documentation

### API Documentation
- **[Complete API Reference](docs/API.md)** - Comprehensive API documentation with examples
- **[Interactive API Documentation](http://localhost:8080/swagger-ui/index.html)** - Swagger UI (when running)

### 🎨 Interactive Web Dashboard

A modern, real-time React-based dashboard for monitoring and managing your distributed rate limiter.

📁 **Location**: [`/examples/web-dashboard`](examples/web-dashboard/)

**Features:**
- **📊 Real-time Monitoring** - Live metrics with 5-second updates from backend
- **🎯 Algorithm Comparison** - Interactive simulation of algorithms
- **📈 Load Testing** - Production-grade benchmarking via backend API
- **⚙️ Configuration Management** - CRUD operations for limits
- **🔑 API Key Management** - Active keys tracking with statistics
- **🧠 Adaptive Rate Limiting** - Automatic rate limit management based on AIMD policy

### Usage Examples
- [Java/Spring Boot Integration](docs/examples/java-client.md)
- [Python Client](docs/examples/python-client.md)
- [Node.js Client](docs/examples/nodejs-client.md)
- [Go Client](docs/examples/go-client.md)
- [cURL Examples](docs/examples/curl-examples.md)

### Architecture & Design
- [Architecture Decision Records](docs/adr/README.md)
- [Adaptive Rate Limiting Guide](docs/ADAPTIVE_RATE_LIMITING.md)

---

## 🚀 Quick Start

### Prerequisites

- **Java 21+**
- **Redis server**

### 1. Start the Backend

```bash
./mvnw spring-boot:run
```

### 2. Start the Dashboard

```bash
cd examples/web-dashboard
npm install
npm run dev
# Dashboard available at http://localhost:5173
```

### 3. Verify Health

```bash
curl http://localhost:8080/actuator/health
```

### 4. Test Rate Limiting

```bash
curl -X POST http://localhost:8080/api/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key": "user:123", "tokens": 1}'
```

---

## 🧮 Rate Limiting Algorithms

The rate limiter supports five different algorithms:

#### 🪣 Token Bucket (Default)
- **Best for**: APIs requiring burst handling with smooth long-term rates.

#### 🌊 Sliding Window
- **Best for**: Consistent rate enforcement with precise timing.

#### 🕐 Fixed Window  
- **Best for**: Memory-efficient rate limiting with predictable resets.

#### 🚰 Leaky Bucket
- **Best for**: Traffic shaping and consistent output rates.

#### 🔄 Composite
- **Best for**: Enterprise scenarios requiring multiple simultaneous limits.

---

## 🏗️ Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Client App    │───▶│  Rate Limiter   │───▶│     Redis       │
│                 │    │   (Port 8080)   │    │   (Distributed  │
│                 │    │                 │    │     State)      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │   Monitoring    │
                       │   & Metrics     │
                       │  (Prometheus)   │
                       └─────────────────┘
```

---

## 🧪 Testing

```bash
# Run all tests
./mvnw test
```

---

## 🤖 Development with AI

This project was developed with assistance from **GitHub Copilot**, which helped accelerate development while maintaining high standards for code quality, testing, and documentation.
