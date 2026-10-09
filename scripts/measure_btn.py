import asyncio

from playwright.async_api import async_playwright

URL = "http://127.0.0.1:8000/"
LINK = "https://vk.ru/clip-234106433_456243137?c=1"
KEY = "change-me-long-random-string"

MEASURE = """() => {
  const r = (sel) => {
    const el = document.querySelector(sel);
    const b = el.getBoundingClientRect();
    return { w: Math.round(b.width), h: Math.round(b.height), fs: getComputedStyle(el).fontSize };
  };
  return { btn: r('#dl'), chips: r('.chips'), card: r('#card'), viewport: window.innerWidth };
}"""


async def main() -> None:
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        page = await browser.new_page(viewport={"width": 420, "height": 900})
        page.on("dialog", lambda d: asyncio.ensure_future(d.accept(KEY)))
        await page.goto(URL)
        await page.fill("#url", LINK)
        await page.click("#go")
        await page.wait_for_selector("#card:not([hidden])", timeout=60000)
        await page.wait_for_timeout(2000)
        for vw in (420, 390, 1280):
            await page.set_viewport_size({"width": vw, "height": 900})
            await page.wait_for_timeout(300)
            state = await page.evaluate(MEASURE)
            print(f"viewport {vw}px -> {state}")
        await page.screenshot(path="test_btn.png", full_page=True)
        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
