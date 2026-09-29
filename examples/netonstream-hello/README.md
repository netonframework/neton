# netonstream-hello

A minimal application on the netonstream engine (`neton-http-netonstream`): HTTP/1.1 and h2c on
one port, gzip, SSE and cookies, optionally TLS with ALPN.

The engine uses `com.netonstream:io`, `http` and `tls` version 0.1.0 from Maven Central
(see `neton-http-netonstream/README.md`).

```bash
./gradlew :examples:netonstream-hello:linkDebugExecutableMacosArm64
cd examples/netonstream-hello
./build/bin/macosArm64/debugExecutable/netonstream-hello.kexe
```

```bash
curl -i http://127.0.0.1:8080/                                  # HTTP/1.1
curl -i --http2-prior-knowledge http://127.0.0.1:8080/          # h2c on the same port
curl -i --compressed http://127.0.0.1:8080/items                # gzip
curl -N http://127.0.0.1:8080/stream?count=5                    # SSE
curl -i http://127.0.0.1:8080/login                             # two Set-Cookie fields
```

TLS (test certificate only):

```bash
mkdir -p certs
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout certs/key.pem -out certs/cert.pem -days 1 -subj /CN=localhost \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
./build/bin/macosArm64/debugExecutable/netonstream-hello.kexe --tls --server.port=8443
curl -i --cacert certs/cert.pem https://localhost:8443/            # ALPN h2
curl -i --http1.1 --cacert certs/cert.pem https://localhost:8443/  # ALPN http/1.1
```

TLS is configured with the `tls { }` DSL because the framework's `application.conf` parser does
not read arrays yet: `alpnProtocols = ["h2", "http/1.1"]` in `[http.tls]` falls back to
`http/1.1` (for every engine).
