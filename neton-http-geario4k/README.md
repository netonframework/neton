# Experimental Geario H1 adapter

This module contains only the Neton adapter and C interop declarations.
The Rust engine and FFI implementation remain in the sibling `geario-http`
repository. No Geario code is embedded in Neton's default HTTP adapter.

The module is excluded from the default build. Enable it explicitly with
`-Pgeario4k.experimental=true`. It does not participate in framework Maven
publishing or change the default HTTP engine. The existing experimental
`neton.http.geario.GearioHttpAdapter` class name is retained; the module name
does not imply a published Geario4k engine exists.

This is a buffered HTTP/1 baseline prototype, not a production adapter.
It has not passed the HTTP engine conformance suite. Cancellation, shutdown,
and FFI lifetime guarantees require validation before production use.

The benchmark application is outside the framework in the sibling
`neton-engine-baseline` directory. Historical benchmark reports under
`neton-http/docs/benchmarks` are unrelated and are not changed here.
