"""Package only the controlled example sources; no Maven outputs or local caches."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

website = Path(__file__).resolve().parent.parent
root = website / 'demo/projects'
destination = website / 'public/demo/projects.zip'
destination.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(destination, 'w', compression=ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob('*')):
        if any(part in {'target', '.cache', '.DS_Store'} for part in path.parts):
            continue
        if path.is_symlink():
            raise ValueError(f'Symlinks are not demo sources: {path.name}')
        if not path.is_file():
            continue
        info = ZipInfo(str(path.relative_to(root)), (2026, 10, 24, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.external_attr = (0o755 if path.name == 'mvnw' else 0o644) << 16
        archive.writestr(info, path.read_bytes())
print(f'Packaged demo projects: {destination.name} ({destination.stat().st_size} bytes)')
