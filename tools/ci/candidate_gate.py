#!/usr/bin/env python3
"""Sign locally with an ephemeral test key, then consume the staged artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

from candidate_artifacts import verify_repository, verify_signature
from reference_gate import clean_revision, require, run_logged, smoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pg-bin', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    revision = clean_revision(root)
    require(platform.system() == 'Darwin' and platform.machine() == 'arm64',
            'Full publication gate requires the macOS ARM64 publisher host')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    version = '1.0.0-candidate.' + revision[:12]
    evidence = {'passed': False, 'revision': revision, 'version': version,
                'signingIdentity': 'ephemeral-test-only', 'remotePublication': False}
    try:
        with tempfile.TemporaryDirectory(prefix='neton-candidate-key-') as keydir:
            home = Path(keydir)
            home.chmod(0o700)
            gpg = ['gpg', '--homedir', str(home), '--batch']
            try:
                subprocess.run(gpg + ['--pinentry-mode', 'loopback', '--passphrase', '',
                    '--quick-generate-key', 'Neton candidate gate <ci@example.invalid>',
                    'rsa2048', 'sign', '1d'], check=True, capture_output=True, timeout=120)
                listing = subprocess.check_output(gpg + ['--with-colons', '--list-secret-keys'], text=True)
                fingerprint = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
                evidence['testKeyFingerprint'] = fingerprint
                (output / 'test-public-key.asc').write_bytes(subprocess.check_output(
                    gpg + ['--armor', '--export', fingerprint]))
                # Never print or persist the secret key; a fresh Gradle home prevents
                # developer signing properties or init scripts overriding this key.
                secret = subprocess.check_output(gpg + ['--armor', '--export-secret-keys', fingerprint], text=True)
                env = {key: value for key, value in os.environ.items()
                       if not key.startswith('ORG_GRADLE_PROJECT_')}
                env.update(GRADLE_USER_HOME=str(output / 'gradle-home'),
                           ORG_GRADLE_PROJECT_signingInMemoryKey=secret,
                           ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='')
                repository = output / 'repository'
                common = [str(root / 'gradlew'), '--no-daemon', '--no-configuration-cache',
                          '--max-workers=2', '-Pnetonstream.local=false', '-Phyper4k.local=false']
                run_logged(common + [f'-PnetonVersion={version}', f'-Pneton.stagingRepository={repository}',
                    'publishAllPublicationsToStagingLocalRepository'], output / 'publish.log',
                    cwd=root, env=env, timeout=5400)
                env.pop('ORG_GRADLE_PROJECT_signingInMemoryKey')
                del secret
                evidence['repository'] = verify_repository(repository, version,
                    lambda file: verify_signature(file, home, fingerprint))
                consumer = output / 'consumer'
                fixture = root / 'examples/release-gate'
                consumer.mkdir()
                for directory in ('src', 'sql'):
                    shutil.copytree(fixture / directory, consumer / directory)
                shutil.copy2(fixture / 'build.gradle.kts', consumer / 'build.gradle.kts')
                (consumer / 'gradle').mkdir()
                shutil.copy2(root / 'gradle/libs.versions.toml', consumer / 'gradle/libs.versions.toml')
                (consumer / 'settings.gradle.kts').write_text(
                    'pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n'
                    'rootProject.name = "release-gate"\n')
                command = common + ['-p', str(consumer), f'-Pcandidate.version={version}',
                    f'-Pcandidate.repository={repository}', 'linkReleaseExecutableMacosArm64']
                for attempt in (1, 2):
                    run_logged(command, output / f'consumer-build-{attempt}.log',
                               cwd=consumer, env=env, timeout=2400)
                binary = consumer / 'build/bin/macosArm64/releaseExecutable/release-gate.kexe'
                evidence['binarySha256'] = hashlib.sha256(binary.read_bytes()).hexdigest()
                smoke(binary, args.pg_bin.resolve(), output, evidence)
                require(clean_revision(root) == revision, 'Candidate source changed')
                evidence['passed'] = True
            finally:
                subprocess.run(['gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'],
                               capture_output=True, timeout=30)
    except Exception as error:
        evidence['failure'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        (output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
