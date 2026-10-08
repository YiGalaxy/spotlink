"""压缩已审阅的母图并登记来源；不读取凭证、不调用图片模型。"""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageOps, ImageDraw

root = Path('/workspace')
source = root / '.local/images/2026-10-08'
target = root / 'frontend/public/images'
target.mkdir(parents=True, exist_ok=True)
prompts = json.loads((root / 'design/商品图片提示词.json').read_text(encoding='utf-8'))
entries, tiles = [], []
for asset in prompts['assets']:
    raw = source / (asset['id'] + '.png')
    image = Image.open(raw).convert('RGB')
    size = (1200, 800) if asset['id'] in ('home-commodity-hero', 'login-warehouse') else (800, 800)
    result = ImageOps.fit(image, size, method=Image.Resampling.LANCZOS)
    output = target / (asset['id'] + '.webp')
    result.save(output, 'WEBP', quality=86, method=6)
    limit = 300_000 if size[0] == 1200 else 150_000
    if output.stat().st_size > limit:
        raise ValueError(f'{asset["id"]} exceeds asset budget')
    entries.append({
        'id': asset['id'], 'sourceType': 'AI生成品类或仓储示意', 'model': prompts['model'],
        'date': prompts['date'], 'prompt': f'design/商品图片提示词.json#{asset["id"]}',
        'original': str(raw.relative_to(root)), 'originalSize': list(image.size),
        'originalSha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
        'file': str(output.relative_to(root)), 'size': list(result.size),
        'bytes': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    })
    tile = Image.new('RGB', (400, 280), '#faf7f1')
    tile.paste(ImageOps.fit(result, (400, 240)), (0, 0))
    ImageDraw.Draw(tile).text((12, 251), asset['id'], fill='#302b27')
    tiles.append(tile)
sheet = Image.new('RGB', (1600, 560), 'white')
for i, tile in enumerate(tiles):
    sheet.paste(tile, ((i % 4) * 400, (i // 4) * 280))
sheet.save(source / 'contact-sheet.jpg')
(root / 'design/图片资源清单.json').write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps([{'id': e['id'], 'bytes': e['bytes'], 'size': e['size']} for e in entries], ensure_ascii=False))
