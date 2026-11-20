"""Draws the Manga Loud demo chapter: six original 800x1100 panels as SVG. All art is drawn here
from simple shapes, so the demo can be published with the project."""
import math
import os
import random

OUT = os.path.dirname(os.path.abspath(__file__))  # Writes 01.svg to 06.svg next to this script.
os.makedirs(OUT, exist_ok=True)
W, H = 800, 1100
FONT = "'Comic Sans MS', 'Comic Neue', 'Chalkboard SE', sans-serif"
SHOUT_FONT = "Impact, 'Arial Black', sans-serif"

SKIN = '#F6D2B4'
INK = '#1b1b22'


def wrap(lines, x, y, size, font=FONT, weight='700', fill=INK, anchor='middle', spacing=1.22, italic=False):
    style = 'font-style:italic;' if italic else ''
    out = [f'<text x="{x}" y="{y}" font-family="{font}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
           f'text-anchor="{anchor}" style="{style}">']
    for i, line in enumerate(lines):
        dy = 0 if i == 0 else size * spacing
        out.append(f'<tspan x="{x}" dy="{dy:.0f}">{line}</tspan>')
    out.append('</text>')
    return ''.join(out)


def text_top(lines, cy, size, spacing=1.22):
    """The first line's baseline so the block is centred on cy."""
    block = size * spacing * (len(lines) - 1)
    return cy - block / 2 + size * 0.35


def bubble(cx, cy, rx, ry, lines, size=30, tail=None):
    """A round speech bubble. tail=(x, y) is where its point ends."""
    parts = []
    if tail:
        tx, ty = tail
        a = math.atan2(ty - cy, tx - cx)
        b1 = (cx + rx * 0.85 * math.cos(a - 0.22), cy + ry * 0.85 * math.sin(a - 0.22))
        b2 = (cx + rx * 0.85 * math.cos(a + 0.22), cy + ry * 0.85 * math.sin(a + 0.22))
        parts.append(f'<path d="M{b1[0]:.0f},{b1[1]:.0f} L{tx},{ty} L{b2[0]:.0f},{b2[1]:.0f} Z" fill="#fff" stroke="{INK}" stroke-width="4" stroke-linejoin="round"/>')
    parts.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#fff" stroke="{INK}" stroke-width="4"/>')
    if tail:  # Hide the tail's base inside the bubble.
        parts.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx - 3}" ry="{ry - 3}" fill="#fff"/>')
    parts.append(wrap(lines, cx, text_top(lines, cy, size), size))
    return ''.join(parts)


def shout(cx, cy, r, lines, size=40, seed=1, tail=None):
    """A jagged shout bubble."""
    rnd = random.Random(seed)
    pts = []
    n = 22
    for i in range(n * 2):
        a = math.pi * i / n
        rr = r * (1.22 + rnd.uniform(0, 0.12)) if i % 2 == 0 else r * 0.98
        pts.append((cx + rr * 1.35 * math.cos(a), cy + rr * 0.9 * math.sin(a)))
    if tail:
        k = min(range(0, n * 2, 2), key=lambda i: (pts[i][0] - tail[0]) ** 2 + (pts[i][1] - tail[1]) ** 2)
        pts[k] = tail
    d = 'M' + ' L'.join(f'{x:.0f},{y:.0f}' for x, y in pts) + ' Z'
    return (f'<path d="{d}" fill="#FFF7D6" stroke="{INK}" stroke-width="4" stroke-linejoin="round"/>'
            + wrap(lines, cx, text_top(lines, cy, size, 1.1), size, font=SHOUT_FONT, weight='400', spacing=1.1))


def thought(cx, cy, rx, ry, lines, size=28, toward=None):
    """A cloud: bumps around an oval, and little circles leading to the thinker."""
    parts = []
    for i in range(14):
        a = 2 * math.pi * i / 14
        parts.append(f'<circle cx="{cx + rx * math.cos(a):.0f}" cy="{cy + ry * math.sin(a):.0f}" r="{min(rx, ry) * 0.42:.0f}" fill="#fff" stroke="{INK}" stroke-width="4"/>')
    parts.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#fff"/>')
    if toward:
        tx, ty = toward
        for j, s in enumerate((16, 11, 7)):
            f = 0.45 + j * 0.2
            parts.append(f'<circle cx="{cx + (tx - cx) * f:.0f}" cy="{cy + ry + (ty - cy - ry) * f:.0f}" r="{s}" fill="#fff" stroke="{INK}" stroke-width="3.5"/>')
    parts.append(wrap(lines, cx, text_top(lines, cy, size), size, italic=True))
    return ''.join(parts)


def whisper(cx, cy, rx, ry, lines, size=24, tail=None):
    """A small dashed bubble for a whisper."""
    parts = []
    if tail:
        parts.append(f'<path d="M{cx - 18},{cy + ry - 6} L{tail[0]},{tail[1]} L{cx + 18},{cy + ry - 6}" fill="#fff" stroke="{INK}" stroke-width="3" stroke-dasharray="7 6"/>')
    parts.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#fff" stroke="{INK}" stroke-width="3" stroke-dasharray="7 6"/>')
    parts.append(wrap(lines, cx, text_top(lines, cy, size), size, weight='400', italic=True))
    return ''.join(parts)


def narration(x, y, w, h, lines, size=28):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="#FFF3D4" stroke="{INK}" stroke-width="4"/>'
            + wrap(lines, x + w / 2, text_top(lines, y + h / 2, size), size, weight='400'))


def game_window(cx, cy, w, h, lines, size=30):
    x, y = cx - w / 2, cy - h / 2
    return (f'<rect x="{x + 6}" y="{y + 8}" width="{w}" height="{h}" rx="6" fill="#00000055"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="#16594f" stroke="#7ff0d8" stroke-width="5"/>'
            f'<rect x="{x + 10}" y="{y + 10}" width="{w - 20}" height="{h - 20}" rx="3" fill="none" stroke="#7ff0d8" stroke-width="1.5" opacity="0.7"/>'
            f'<path d="M{cx - 40},{y} L{cx},{y - 26} L{cx + 40},{y} Z" fill="#7ff0d8"/>'
            f'<path d="M{cx - 14},{y - 4} L{cx},{y - 30} L{cx + 14},{y - 4} Z" fill="#3a6cf0"/>'
            + wrap(lines, cx, text_top(lines, cy, size, 1.25), size, font=SHOUT_FONT, weight='400', fill='#F2FFFB', spacing=1.25))


def sfx(x, y, word, size=120, rot=-10, fill='#FF6A2B'):
    return (f'<g transform="rotate({rot} {x} {y})"><text x="{x}" y="{y}" font-family="{SHOUT_FONT}" font-size="{size}" '
            f'fill="{fill}" stroke="{INK}" stroke-width="7" paint-order="stroke" text-anchor="middle" letter-spacing="4">{word}</text></g>')


def face(cx, cy, s, expr):
    """Eyes and mouth. expr: happy, shout, worried, smug, surprised, calm, think."""
    e = []
    ey = cy - 4 * s
    for side in (-1, 1):
        ex = cx + side * 22 * s
        if expr == 'smug' or expr == 'calm':
            e.append(f'<path d="M{ex - 12 * s},{ey} Q{ex},{ey - 8 * s} {ex + 12 * s},{ey}" stroke="{INK}" stroke-width="{4 * s}" fill="none" stroke-linecap="round"/>')
        else:
            big = 1.25 if expr in ('surprised', 'shout') else 1
            e.append(f'<ellipse cx="{ex}" cy="{ey}" rx="{9 * s * big}" ry="{13 * s * big}" fill="#fff" stroke="{INK}" stroke-width="{3 * s}"/>')
            e.append(f'<circle cx="{ex + side * 1 * s}" cy="{ey + 2 * s}" r="{6 * s}" fill="{INK}"/>')
            e.append(f'<circle cx="{ex + 3 * s}" cy="{ey - 3 * s}" r="{2 * s}" fill="#fff"/>')
        if expr in ('shout', 'worried'):
            tilt = -1 if expr == 'shout' else 1
            e.append(f'<path d="M{ex - 14 * s},{ey - 22 * s - tilt * side * 5 * s} L{ex + 14 * s},{ey - 22 * s + tilt * side * 5 * s}" stroke="{INK}" stroke-width="{4 * s}" stroke-linecap="round"/>')
    my = cy + 28 * s
    mouths = {
        'happy': f'<path d="M{cx - 18 * s},{my} Q{cx},{my + 20 * s} {cx + 18 * s},{my}" fill="#7a2a2a" stroke="{INK}" stroke-width="{3 * s}"/>',
        'shout': f'<ellipse cx="{cx}" cy="{my + 6 * s}" rx="{16 * s}" ry="{14 * s}" fill="#7a2a2a" stroke="{INK}" stroke-width="{3 * s}"/>',
        'surprised': f'<ellipse cx="{cx}" cy="{my + 4 * s}" rx="{8 * s}" ry="{10 * s}" fill="#7a2a2a" stroke="{INK}" stroke-width="{3 * s}"/>',
        'worried': f'<path d="M{cx - 14 * s},{my + 8 * s} Q{cx},{my - 4 * s} {cx + 14 * s},{my + 8 * s}" fill="none" stroke="{INK}" stroke-width="{3 * s}"/>',
        'smug': f'<path d="M{cx - 16 * s},{my} Q{cx + 4 * s},{my + 10 * s} {cx + 18 * s},{my - 6 * s}" fill="none" stroke="{INK}" stroke-width="{3 * s}"/>',
        'calm': f'<path d="M{cx - 10 * s},{my + 2 * s} L{cx + 10 * s},{my + 2 * s}" stroke="{INK}" stroke-width="{3 * s}"/>',
        'think': f'<path d="M{cx - 10 * s},{my + 4 * s} Q{cx},{my} {cx + 12 * s},{my + 2 * s}" fill="none" stroke="{INK}" stroke-width="{3 * s}"/>',
    }
    e.append(mouths[expr])
    e.append(f'<ellipse cx="{cx - 34 * s}" cy="{cy + 16 * s}" rx="{9 * s}" ry="{5 * s}" fill="#f19a8a" opacity="0.6"/>')
    e.append(f'<ellipse cx="{cx + 34 * s}" cy="{cy + 16 * s}" rx="{9 * s}" ry="{5 * s}" fill="#f19a8a" opacity="0.6"/>')
    return ''.join(e)


def kai(cx, cy, s=1.0, expr='happy'):
    """Kai: spiky black hair, red scarf, blue jacket."""
    p = [f'<path d="M{cx - 80 * s},{cy + 260 * s} Q{cx - 70 * s},{cy + 95 * s} {cx},{cy + 90 * s} Q{cx + 70 * s},{cy + 95 * s} {cx + 80 * s},{cy + 260 * s} Z" fill="#2f5da8" stroke="{INK}" stroke-width="{4 * s}"/>',
         f'<path d="M{cx - 46 * s},{cy + 82 * s} Q{cx},{cy + 112 * s} {cx + 46 * s},{cy + 82 * s} L{cx + 40 * s},{cy + 104 * s} Q{cx},{cy + 128 * s} {cx - 40 * s},{cy + 104 * s} Z" fill="#d8352a" stroke="{INK}" stroke-width="{3 * s}"/>',
         f'<path d="M{cx + 30 * s},{cy + 100 * s} L{cx + 58 * s},{cy + 170 * s} L{cx + 36 * s},{cy + 172 * s} Z" fill="#d8352a" stroke="{INK}" stroke-width="{3 * s}"/>',
         f'<ellipse cx="{cx}" cy="{cy}" rx="{64 * s}" ry="{72 * s}" fill="{SKIN}" stroke="{INK}" stroke-width="{4 * s}"/>']
    spikes = [(-70, -10), (-78, -50), (-55, -95), (-20, -115), (15, -118), (52, -100), (78, -60), (72, -15)]
    d = f'M{cx - 66 * s},{cy - 2 * s} '
    for i, (dx, dy) in enumerate(spikes):
        d += f'L{cx + dx * 1.15 * s},{cy + dy * 1.12 * s} L{cx + dx * 0.72 * s},{cy + (dy * 0.62 - 18) * s} '
    d += f'L{cx + 66 * s},{cy - 2 * s} Q{cx + 30 * s},{cy - 38 * s} {cx},{cy - 30 * s} Q{cx - 34 * s},{cy - 40 * s} {cx - 66 * s},{cy - 2 * s} Z'
    p.append(f'<path d="{d}" fill="#22232c" stroke="{INK}" stroke-width="{3 * s}" stroke-linejoin="round"/>')
    p.append(face(cx, cy + 8 * s, s, expr))
    return ''.join(p)


def mira(cx, cy, s=1.0, expr='worried'):
    """Mira: orange twin tails, green dress."""
    p = []
    for side in (-1, 1):
        p.append(f'<path d="M{cx + side * 58 * s},{cy - 30 * s} Q{cx + side * 120 * s},{cy + 10 * s} {cx + side * 96 * s},{cy + 150 * s} Q{cx + side * 70 * s},{cy + 60 * s} {cx + side * 50 * s},{cy - 10 * s} Z" fill="#ff8a2a" stroke="{INK}" stroke-width="{3 * s}"/>')
    p.append(f'<path d="M{cx - 78 * s},{cy + 260 * s} Q{cx - 64 * s},{cy + 95 * s} {cx},{cy + 90 * s} Q{cx + 64 * s},{cy + 95 * s} {cx + 78 * s},{cy + 260 * s} Z" fill="#2f9a6a" stroke="{INK}" stroke-width="{4 * s}"/>')
    p.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{60 * s}" ry="{68 * s}" fill="{SKIN}" stroke="{INK}" stroke-width="{4 * s}"/>')
    p.append(f'<path d="M{cx - 64 * s},{cy + 6 * s} Q{cx - 70 * s},{cy - 80 * s} {cx},{cy - 78 * s} Q{cx + 70 * s},{cy - 80 * s} {cx + 64 * s},{cy + 6 * s} Q{cx + 40 * s},{cy - 30 * s} {cx + 10 * s},{cy - 26 * s} L{cx},{cy - 6 * s} L{cx - 10 * s},{cy - 26 * s} Q{cx - 40 * s},{cy - 30 * s} {cx - 64 * s},{cy + 6 * s} Z" fill="#ff8a2a" stroke="{INK}" stroke-width="{3 * s}"/>')
    for side in (-1, 1):
        p.append(f'<circle cx="{cx + side * 56 * s}" cy="{cy - 40 * s}" r="{10 * s}" fill="#ffd23f" stroke="{INK}" stroke-width="{3 * s}"/>')
    p.append(face(cx, cy + 10 * s, s * 0.95, expr))
    return ''.join(p)


def smith(cx, cy, s=1.0, expr='smug'):
    """The old smith's ghost: white beard, bald, pale blue glow."""
    p = [f'<ellipse cx="{cx}" cy="{cy + 40 * s}" rx="{150 * s}" ry="{190 * s}" fill="#9fd8ff" opacity="0.25"/>',
         f'<path d="M{cx - 90 * s},{cy + 280 * s} Q{cx - 80 * s},{cy + 100 * s} {cx},{cy + 95 * s} Q{cx + 80 * s},{cy + 100 * s} {cx + 90 * s},{cy + 280 * s} Z" fill="#6b4a3a" stroke="{INK}" stroke-width="{4 * s}" opacity="0.92"/>',
         f'<ellipse cx="{cx}" cy="{cy}" rx="{66 * s}" ry="{74 * s}" fill="#dfe9f3" stroke="{INK}" stroke-width="{4 * s}"/>',
         f'<path d="M{cx - 62 * s},{cy + 20 * s} Q{cx - 50 * s},{cy + 150 * s} {cx},{cy + 170 * s} Q{cx + 50 * s},{cy + 150 * s} {cx + 62 * s},{cy + 20 * s} Q{cx + 30 * s},{cy + 50 * s} {cx},{cy + 46 * s} Q{cx - 30 * s},{cy + 50 * s} {cx - 62 * s},{cy + 20 * s} Z" fill="#ffffff" stroke="{INK}" stroke-width="{3 * s}"/>',
         f'<path d="M{cx - 40 * s},{cy - 30 * s} L{cx - 10 * s},{cy - 22 * s} M{cx + 10 * s},{cy - 22 * s} L{cx + 40 * s},{cy - 30 * s}" stroke="#fff" stroke-width="{9 * s}" stroke-linecap="round"/>']
    eyes = face(cx, cy - 6 * s, s, expr).split('<path d="M')
    p.append(face(cx, cy - 6 * s, s, expr) if expr != 'smug' else face(cx, cy - 6 * s, s, 'calm').rsplit('<path', 1)[0])
    return ''.join(p)


def forge(x, y, s=1.0, glow=False):
    p = [f'<path d="M{x - 120 * s},{y} L{x - 90 * s},{y - 180 * s} L{x + 90 * s},{y - 180 * s} L{x + 120 * s},{y} Z" fill="#5a5f6e" stroke="{INK}" stroke-width="5"/>',
         f'<path d="M{x - 60 * s},{y - 20 * s} Q{x},{y - 130 * s} {x + 60 * s},{y - 20 * s} Z" fill="{"#ffb020" if glow else "#2a2c33"}" stroke="{INK}" stroke-width="5"/>',
         f'<rect x="{x - 40 * s}" y="{y - 260 * s}" width="{80 * s}" height="{80 * s}" fill="#4a4e5a" stroke="{INK}" stroke-width="5"/>']
    if glow:
        p.insert(0, f'<circle cx="{x}" cy="{y - 60 * s}" r="{220 * s}" fill="url(#glow)"/>')
    return ''.join(p)


DEFS = '''<defs>
<radialGradient id="glow"><stop offset="0" stop-color="#ffcf4a" stop-opacity="0.85"/><stop offset="1" stop-color="#ff8a1f" stop-opacity="0"/></radialGradient>
<linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3b2d6b"/><stop offset="0.55" stop-color="#d9637a"/><stop offset="1" stop-color="#f6b26b"/></linearGradient>
<linearGradient id="night" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#151a33"/><stop offset="1" stop-color="#3a2f63"/></linearGradient>
<linearGradient id="room" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4a3a33"/><stop offset="1" stop-color="#2a201c"/></linearGradient>
<pattern id="dots" width="14" height="14" patternUnits="userSpaceOnUse"><circle cx="7" cy="7" r="2" fill="#000" opacity="0.08"/></pattern>
<pattern id="lines" width="40" height="40" patternUnits="userSpaceOnUse" patternTransform="rotate(20)"><rect width="40" height="40" fill="#ffe9b0"/><rect width="6" height="40" fill="#ffd36b"/></pattern>
</defs>'''


def panel(name, body, bg='#ffffff'):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">{DEFS}'
           f'<rect width="{W}" height="{H}" fill="{bg}"/>{body}</svg>')
    open(f'{OUT}/{name}.svg', 'w', encoding='utf-8').write(svg)


def frame(x, y, w, h, inner, fill='#fff'):
    """A comic panel border with its contents clipped inside."""
    cid = f'c{x}_{y}_{w}_{h}'
    return (f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}"/></clipPath>'
            f'<g clip-path="url(#{cid})"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}"/>{inner}</g>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="none" stroke="{INK}" stroke-width="6"/>')


# 1: title and the first narration.
panel('01', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="url(#dusk)"/>'
    '<path d="M30,880 L150,760 L260,830 L400,700 L540,820 L650,740 L770,840 L770,1070 L30,1070 Z" fill="#2a2140"/>'
    + forge(400, 1010, 1.1)
    + '<text x="400" y="190" text-anchor="middle" font-family="Impact, sans-serif" font-size="96" fill="#FFD23F" stroke="#1b1b22" stroke-width="8" paint-order="stroke">MANGA LOUD</text>'
    + '<text x="400" y="260" text-anchor="middle" font-family="Comic Sans MS, sans-serif" font-size="40" font-weight="700" fill="#fff" stroke="#1b1b22" stroke-width="5" paint-order="stroke">Demo Chapter 1: The Loud Forge</text>'
    + narration(110, 400, 580, 150, ['The village of Emberfall had one rule:', 'never wake the old forge.'], 30)
    , fill='#000'), bg='#fff')

# 2: Kai excited, Mira shouting a warning.
panel('02', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="url(#room)"/><rect x="30" y="30" width="740" height="1040" fill="url(#dots)"/>'
    + forge(590, 820, 0.95, glow=True)
    + kai(250, 640, 1.15, 'happy')
    + mira(560, 470, 0.9, 'shout')
    + bubble(225, 345, 185, 100, ['Mira, look!', 'The forge is glowing!'], 31, tail=(250, 470))
    + shout(585, 160, 98, ["KAI, WAIT!", "DON'T TOUCH IT!"], 36, seed=4, tail=(570, 370))
    ))

# 3: a game window with a skill name in angle brackets.
panel('03', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="#2b2160"/>'
    + ''.join(f'<rect x="{40 + i * 46}" y="30" width="5" height="1040" fill="#b9a6ff" opacity="{0.15 + (i % 3) * 0.12}"/>' for i in range(16))
    + game_window(400, 420, 600, 300, ['A HIDDEN CLASS HAS BEEN', 'UNLOCKED: ⟨EMBER SMITH⟩.', 'YOU GAINED 300 EXPERIENCE.'], 36)
    + kai(400, 880, 0.95, 'surprised')
    ))

# 4: Kai's thought, then a cough in the smoke.
panel('04', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="#e9e2d6"/><rect x="30" y="30" width="740" height="1040" fill="url(#dots)"/>'
    + ''.join(f'<circle cx="{c[0]}" cy="{c[1]}" r="{c[2]}" fill="#9a9aa4" opacity="0.55"/>' for c in [(420, 700, 120), (540, 640, 90), (300, 760, 100), (480, 820, 130), (620, 760, 80)])
    + kai(430, 760, 1.15, 'worried')
    + thought(300, 230, 200, 100, ['...Did I just become', 'a blacksmith?'], 32, toward=(400, 560))
    + bubble(620, 480, 125, 80, ['Cough! Cough!', "I'm fine..."], 28, tail=(520, 640))
    ))

# 5: the old smith's ghost, and Mira whispering.
panel('05', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="url(#night)"/>'
    + ''.join(f'<circle cx="{random.Random(i).randint(40, 760)}" cy="{random.Random(i + 99).randint(40, 500)}" r="2.5" fill="#fff" opacity="0.7"/>' for i in range(40))
    + smith(430, 520, 1.15, 'smug')
    + mira(150, 840, 0.75, 'surprised')
    + bubble(410, 180, 330, 120, ['Hmph. Three hundred years', 'I waited... and THIS', 'is my successor?'], 30, tail=(460, 400))
    + whisper(240, 620, 130, 55, ['Is he... a ghost?'], 24, tail=(180, 740))
    ))

# 6: a sound effect, Kai's skill shout, and "to be continued".
panel('06', frame(30, 30, 740, 1040,
    '<rect x="30" y="30" width="740" height="1040" fill="url(#lines)"/>'
    + kai(400, 640, 1.3, 'shout')
    + sfx(600, 420, 'BOOM!', 130, -12)
    + shout(330, 190, 115, ["LET'S DO THIS!", '⟨SWORD DANCE⟩!'], 40, seed=9, tail=(360, 470))
    + narration(170, 930, 460, 90, ['To be continued...'], 32)
    ))
print('ok', sorted(os.listdir(OUT)))
