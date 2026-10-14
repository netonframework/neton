import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

base = pathlib.Path('/tmp/neton-lan-20260918')
framework = sys.argv[1]
binary = (base/'neton-httparena.kexe' if framework == 'neton' else
          base/'frameworks/actix/target/release/httparena-actix')
with tempfile.TemporaryDirectory(prefix='neton-runtime-') as work:
    root = pathlib.Path(work)
    shutil.copy2(binary, root/'server')
    (root/'server').chmod(0o755)
    libs = subprocess.check_output(['ldd', str(binary)], text=True)
    if 'not found' in libs:
        raise RuntimeError(libs)
    for path in set(re.findall(r'/\S+', libs)):
        dest = root/path.lstrip('/')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest, follow_symlinks=True)
    (root/'etc').mkdir(exist_ok=True)
    if framework == 'neton':
        shutil.copytree(base/'config', root/'config')
    archive = subprocess.Popen(['tar', '-C', work, '-cf', '-', '.'], stdout=subprocess.PIPE)
    subprocess.run(['docker', 'import', '--change', 'CMD ["/server"]',
                    '--change', 'WORKDIR /', '-', f'httparena-{framework}'],
                   stdin=archive.stdout, check=True)
    archive.stdout.close()
    if archive.wait():
        raise RuntimeError('Runtime archive failed')
