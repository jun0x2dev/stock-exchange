"""시연 프레임에 자막과 누른 위치를 그려 docs/{이름}.gif로 만든다. Pillow와 한글 글꼴 경로가 필요하다.

실행: python3 e2e/make-demo-gif.py <글꼴 경로> [demo | admin-demo]
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
NAME = sys.argv[2] if len(sys.argv) > 2 else 'demo'
SRC = ROOT / 'screenshots' / NAME
BAR = 52
WIDTH = 360

font = ImageFont.truetype(sys.argv[1], 17)
images, durations = [], []
for frame in json.loads((SRC / 'frames.json').read_text()):
    shot = Image.open(SRC / frame['file']).convert('RGB')
    scale = WIDTH / shot.width
    shot = shot.resize((WIDTH, round(shot.height * scale)), Image.LANCZOS)
    if tap := frame.get('tap'):
        x, y = tap['x'] * scale, tap['y'] * scale
        mark = Image.new('RGBA', shot.size)
        ImageDraw.Draw(mark).ellipse((x - 17, y - 17, x + 17, y + 17), fill=(24, 33, 44, 90), outline=(255, 255, 255, 220), width=2)
        shot = Image.alpha_composite(shot.convert('RGBA'), mark).convert('RGB')
    canvas = Image.new('RGB', (WIDTH, shot.height + BAR), '#18212c')
    ImageDraw.Draw(canvas).text((WIDTH / 2, BAR / 2), frame['caption'], font=font, fill='white', anchor='mm')
    canvas.paste(shot, (0, BAR))
    images.append(canvas)
    durations.append(frame['ms'])

# 모든 프레임이 같은 색표를 써야 배경이 깜빡이지 않는다.
sheet = Image.new('RGB', (WIDTH, images[0].height * len(images)))
for i, image in enumerate(images):
    sheet.paste(image, (0, i * images[0].height))
palette = sheet.quantize(128, method=Image.Quantize.MEDIANCUT)
frames = [image.quantize(palette=palette, dither=Image.Dither.NONE) for image in images]
out = ROOT / 'docs' / f'{NAME}.gif'
frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)
print(out, f'{out.stat().st_size // 1024}KB', f'{len(frames)} frames', f'{sum(durations) / 1000:.1f}s')
