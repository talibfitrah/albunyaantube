# Calm zellige tiles as SVG <pattern> markup. Pieces are outlined by thin grout lines; only stars get a faint glaze.
#   khatam: 8-point stars (two squares) on the joints of a square grid, like a quiet lattice of tiles.
import math, sys

def fmt(pts):
    return "M" + "L".join(f"{x:.2f} {y:.2f}".replace(".00", "") for x, y in pts) + "Z"

def khatam_pattern(s, k):
    """Square grid of pitch s; an 8-point star of tip radius k*s on each joint; grid lines join the tips."""
    R = k * s
    ri = R * math.cos(math.pi / 4) / math.cos(math.pi / 8)
    star = lambda cx, cy: [(cx + (R if n % 2 == 0 else ri) * math.cos(math.radians(22.5 * n)),
                            cy + (R if n % 2 == 0 else ri) * math.sin(math.radians(22.5 * n))) for n in range(16)]
    stars = [star(x, y) for x in (0, s) for y in (0, s)]
    lines = f"M{R:.2f} 0H{s - R:.2f}M{R:.2f} {s:g}H{s - R:.2f}M0 {R:.2f}V{s - R:.2f}M{s:g} {R:.2f}V{s - R:.2f}"
    return s, s, stars, lines

assert sys.argv[1] == "khatam", "usage: python3 zellige.py khatam <tile size> <star ratio>"
W, H, stars, lines = khatam_pattern(float(sys.argv[2]), float(sys.argv[3]))
body = f'  <path class="z-star" d="{"".join(fmt(p) for p in stars)}"/>\n  <path class="z-line" d="{lines}"/>'
print(f'<pattern id="zellige" width="{W:.2f}" height="{H:.2f}" patternUnits="userSpaceOnUse" x="50%">\n{body}\n</pattern>')
