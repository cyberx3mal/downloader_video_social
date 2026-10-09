from PIL import Image, ImageDraw

SIZES = [512, 192, 180]
BG = (11, 18, 32, 255)
ARROW = (34, 211, 238, 255)


def make_icon(size: int) -> None:
    scale = size / 512
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    radius = int(110 * scale)
    draw.rounded_rectangle([0, 0, size, size], radius=radius, fill=BG)

    cx = size / 2
    shaft_w = int(56 * scale)
    shaft_top = int(120 * scale)
    shaft_bottom = int(280 * scale)
    head_half = int(130 * scale)
    head_bottom = int(400 * scale)

    draw.rectangle(
        [cx - shaft_w / 2, shaft_top, cx + shaft_w / 2, shaft_bottom],
        fill=ARROW,
    )
    draw.polygon(
        [
            (cx - head_half, shaft_bottom - int(20 * scale)),
            (cx + head_half, shaft_bottom - int(20 * scale)),
            (cx, head_bottom),
        ],
        fill=ARROW,
    )

    img.save(f"app/static/icons/icon-{size}.png")


if __name__ == "__main__":
    import pathlib

    pathlib.Path("app/static/icons").mkdir(parents=True, exist_ok=True)
    for s in SIZES:
        make_icon(s)
    print("icons generated")
