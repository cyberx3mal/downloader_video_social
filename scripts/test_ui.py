import asyncio
import sys

from playwright.async_api import async_playwright

URL = "http://127.0.0.1:8000/"
LINK = "https://vk.ru/clip-234106433_456243137?c=1"
KEY = "change-me-long-random-string"


async def main() -> None:
    console_msgs: list[str] = []
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        page = await browser.new_page(viewport={"width": 420, "height": 900})
        page.on("console", lambda m: console_msgs.append(f"[{m.type}] {m.text}"))
        page.on("pageerror", lambda e: console_msgs.append(f"[PAGEERROR] {e}"))
        page.on(
            "dialog",
            lambda d: asyncio.ensure_future(d.accept(KEY)),
        )

        await page.goto(URL)
        await page.fill("#url", LINK)
        await page.click("#go")
        try:
            await page.wait_for_selector("#card:not([hidden])", timeout=60000)
        except Exception:
            print("CARD DID NOT APPEAR")
        await page.wait_for_timeout(2500)

        state = await page.evaluate(
            """() => ({
                cardHidden: document.getElementById('card').hidden,
                thumbVisible: getComputedStyle(document.getElementById('thumb')).display !== 'none',
                thumbLoaded: document.getElementById('thumb').naturalWidth,
                chips: [...document.querySelectorAll('.chip')].map(c => c.textContent.trim()),
                dlText: document.getElementById('dl').textContent,
                dlHref: document.getElementById('dl').href.slice(0, 90),
                status: document.getElementById('status').textContent,
            })"""
        )
        for k, v in state.items():
            print(f"{k}: {v}")
        print("--- console ---")
        for m in console_msgs:
            print(m)

        await page.screenshot(path="test_page.png", full_page=True)
        await browser.close()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
