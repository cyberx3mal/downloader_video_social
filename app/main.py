import asyncio
import shutil
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote, urlparse

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from starlette.background import BackgroundTask

from . import downloader
from .cache import TTLCache
from .config import settings

_hits: dict[str, deque] = defaultdict(deque)
_cache = TTLCache(ttl=settings.cache_ttl, maxlen=settings.cache_max)


async def _cleanup_loop() -> None:
    while True:
        await asyncio.sleep(600)
        now = time.monotonic()
        root = Path(settings.download_dir)
        while _stale and now - _stale[0][1] > 1800:
            _stale.popleft()
        if root.exists():
            for child in root.iterdir():
                try:
                    age = now - child.stat().st_mtime
                    if child.is_dir() and age > 1800:
                        shutil.rmtree(child, ignore_errors=True)
                    elif child.is_file() and age > 1800:
                        child.unlink(missing_ok=True)
                except OSError:
                    pass


@asynccontextmanager
async def lifespan(app: FastAPI):
    Path(settings.download_dir).mkdir(parents=True, exist_ok=True)
    task = asyncio.create_task(_cleanup_loop())
    yield
    task.cancel()


app = FastAPI(title="Video Downloader", docs_url=None, redoc_url=None, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def no_cache_html(request: Request, call_next):
    response = await call_next(request)
    if response.headers.get("content-type", "").startswith("text/html"):
        response.headers["Cache-Control"] = "no-cache"
    return response


def _check_key(request: Request, key: str | None) -> None:
    if not settings.access_key:
        return
    provided = key or request.headers.get("x-access-key")
    if provided != settings.access_key:
        raise HTTPException(status_code=403, detail="Неверный ключ доступа")


def _rate_limit(request: Request, limit: int) -> None:
    now = time.monotonic()
    queue = _hits[request.client.host]
    while queue and now - queue[0] > 60:
        queue.popleft()
    if len(queue) >= limit:
        raise HTTPException(status_code=429, detail="Слишком много запросов, подождите минуту")
    queue.append(now)


class ResolveRequest(BaseModel):
    url: str
    key: str | None = None


@app.get("/api/health")
async def health():
    return {"status": "ok"}


@app.post("/api/resolve")
async def resolve(req: ResolveRequest, request: Request):
    _check_key(request, req.key)
    _rate_limit(request, settings.rate_resolve)
    url = await downloader.expand_url(req.url.strip())
    cached = _cache.get(url)
    if cached:
        return cached
    try:
        result = await asyncio.to_thread(downloader.resolve_sync, url)
    except downloader.ExtractError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    _cache.set(url, result)
    return result


def _content_disposition(filename: str) -> str:
    ascii_name = filename.encode("ascii", "ignore").decode().strip() or "video"
    quoted = quote(filename)
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quoted}"


async def _get_or_resolve(url: str) -> dict:
    url = await downloader.expand_url(url)
    result = _cache.get(url)
    if not result:
        try:
            result = await asyncio.to_thread(downloader.resolve_sync, url)
        except downloader.ExtractError as exc:
            raise HTTPException(status_code=422, detail=str(exc))
        _cache.set(url, result)
    return result


@app.get("/api/download")
async def download(
    request: Request,
    url: str,
    quality: str = "best",
    key: str | None = None,
):
    _check_key(request, request.query_params.get("key"))
    _rate_limit(request, settings.rate_download)
    try:
        downloader.check_url(url)
    except downloader.ExtractError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    if quality != "audio" and quality != "best":
        try:
            int(quality)
        except ValueError:
            raise HTTPException(status_code=422, detail="Неизвестное качество")

    await _get_or_resolve(url)
    try:
        path, work, filename, media_type = await asyncio.to_thread(
            downloader.download_sync, url, quality
        )
    except downloader.ExtractError as exc:
        raise HTTPException(status_code=422, detail=str(exc))

    return FileResponse(
        path,
        media_type=media_type,
        headers={"Content-Disposition": _content_disposition(filename)},
        background=BackgroundTask(shutil.rmtree, work, True),
    )


@app.get("/api/thumb")
async def thumb(url: str):
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise HTTPException(status_code=422, detail="Некорректная ссылка")
    try:
        async with httpx.AsyncClient(
            follow_redirects=True,
            timeout=20,
            headers={"User-Agent": "Mozilla/5.0"},
        ) as client:
            response = await client.get(url)
            response.raise_for_status()
    except httpx.HTTPError:
        raise HTTPException(status_code=404, detail="Превью недоступно")
    return Response(
        content=response.content,
        media_type=response.headers.get("content-type", "image/jpeg"),
        headers={"Cache-Control": "public, max-age=3600"},
    )


app.mount("/", StaticFiles(directory="app/static", html=True), name="static")
