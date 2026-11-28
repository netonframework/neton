# Optional Consul KV configuration

Use `Neton.run { externalConfig(source, hotFiles = ...) { snapshot -> ... } }` to opt in.
Construct the Consul source from trusted local settings, supplying an application-owned
HTTP client. Keep the ACL token outside the remote group and close the client after Neton
stops. The fixture shows the API without requiring a dedicated configuration service.

The validator must check the complete effective group and must not mutate the application.
Only list files whose consumers explicitly read configuration through
`ExternalConfig.snapshot()`. Keep that snapshot for the entire request (including
suspension). Startup-only components retain their startup settings; declaring a file hot
does not reconfigure an existing component. Mixed hot and restart-only changes wait for
restart as a group.

Run the two-process contract with a local Consul executable:

```sh
./gradlew :examples:external-config-contract:linkDebugExecutableMacosArm64
python3 -B tools/ci/external_config_contract.py \
  examples/external-config-contract/build/bin/macosArm64/debugExecutable/external-config-contract.kexe
```

For Linux x64 use the `linkDebugExecutableLinuxX64` task and the corresponding
`build/bin/linuxX64` output. The runner starts an isolated loopback Consul with ACLs;
it publishes immutable groups using CAS and places controlled fault barriers between
Consul and one of the Neton processes.
