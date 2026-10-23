"""Inspect the actual device PNG export and the saved source strips; no app content needed."""
from pathlib import Path
from io import BytesIO
import argparse, json, tarfile, itertools
from PIL import Image, ImageChops, ImageCms

parser = argparse.ArgumentParser()
parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parent / 'fixtures')
parser.add_argument('--prefix', default='fixture')
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'build/fixture-validation.json')
args = parser.parse_args()
root = args.directory
with Image.open(root / f'{args.prefix}-export.png') as check:
    check.verify()
image = Image.open(root / f'{args.prefix}-export.png').convert('RGB')
with tarfile.open(root / f'{args.prefix}-strips.tar') as archive:
    manifest_member = next(m for m in archive if m.name.endswith('/manifest.json'))
    manifest = json.load(archive.extractfile(manifest_member))
    prefix = manifest_member.name.rsplit('/', 1)[0]
    y = 0
    max_channel_error = 0
    for name in manifest['strips']:
        strip = Image.open(BytesIO(archive.extractfile(prefix + '/' + name).read()))
        if strip.info.get('icc_profile'):
            profile = ImageCms.ImageCmsProfile(BytesIO(strip.info['icc_profile']))
            strip = ImageCms.profileToProfile(strip, profile, ImageCms.createProfile('sRGB'), outputMode='RGB')
        else:
            strip = strip.convert('RGB')
        segment = image.crop((0, y, strip.width, y + strip.height))
        error = max(high for low, high in ImageChops.difference(segment, strip).getextrema())
        max_channel_error = max(max_channel_error, error)
        # Android and LittleCMS may round the P3 -> sRGB transform one level differently.
        assert error <= 1, (name, error)
        y += strip.height
    assert image.size == (manifest['width'], manifest['height'])
    assert y == image.height

column = list(image.crop((5, 0, 6, image.height)).get_flattened_data())
runs = [(color, sum(1 for _ in rows)) for color, rows in itertools.groupby(column)]
long_runs = [length for color, length in runs if length > 10]
interior = long_runs[1:-1]
report = {
    'size': list(image.size),
    'source_strips': len(manifest['strips']),
    'max_channel_error_after_srgb_normalization': max_channel_error,
    'fixture_background_run_lengths': long_runs,
    'interior_panel_heights_exactly_720': bool(interior) and all(length == 720 for length in interior),
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
assert report['interior_panel_heights_exactly_720'], 'Fixture continuity failed'
