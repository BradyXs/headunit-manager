"""Render licensed Lucide line icons as PNGs for Android API 19.

Install CairoSVG separately, then run this script. The APK build only needs the
generated PNGs, not Python or CairoSVG.
"""

from pathlib import Path
from urllib.request import urlopen
import cairosvg


ROOT = Path(__file__).resolve().parents[1]
REVISION = "a805955781e0b9ca7db722162fc720205e5ada83"
ICONS = {
    "overview": "house",
    "apps": "layout-grid",
    "files": "folder-open",
    "automation": "workflow",
    "tools": "sliders-horizontal",
    "services": "server",
    "device": "activity",
    "logs": "scroll-text",
    "network": "wifi",
    "accessibility": "accessibility",
    "adb": "terminal",
    "vehicle": "car-front",
}
DENSITIES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72}
SOURCE_DIR = ROOT / "assets" / "lucide"


def main():
    SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    for resource, source_name in ICONS.items():
        source = SOURCE_DIR / f"{source_name}.svg"
        if not source.exists():
            url = f"https://raw.githubusercontent.com/lucide-icons/lucide/{REVISION}/icons/{source_name}.svg"
            source.write_bytes(urlopen(url, timeout=20).read())
        svg = source.read_text(encoding="utf-8").replace('stroke="currentColor"', 'stroke="#FFFFFF"')
        for density, pixels in DENSITIES.items():
            target = ROOT / "app" / "src" / "main" / "res" / f"drawable-{density}" / f"ic_hm_{resource}.png"
            target.parent.mkdir(parents=True, exist_ok=True)
            cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=str(target),
                             output_width=pixels, output_height=pixels)
            print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
