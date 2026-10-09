import asyncio
import re
import shutil
import tempfile
from pathlib import Path
from urllib.parse import urlparse

import httpx
import yt_dlp

from .config import settings


class ExtractError(Exception):
    pass


BASE_OPTS = {
    "quiet": True,
    "no_warnings": True,
    "noplaylist": True,
    "socket_timeout": 20,
    "retries": 3,
    "extractor_retries": 1,
    "max_filesize": settings.max_filesize,
}

QUALITY_STEPS = [2160, 1440, 1080, 720, 480, 360]

_SHARE_RE = re.compile(r"instagram\.com/share/|(?:vm|vt)\.tiktok\.com/")


def check_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ExtractError("Некорректная ссылка")
    return url


def _expand_sync(url: str) -> str:
    if not _SHARE_RE.search(url):
        return url
    try:
        with httpx.Client(
            follow_redirects=True,
            timeout=15,
            headers={"User-Agent": "Mozilla/5.0"},
        ) as client:
            response = client.get(url)
            return str(response.url)
    except httpx.HTTPError:
        return url


async def expand_url(url: str) -> str:
    return await asyncio.to_thread(_expand_sync, check_url(url))


def _friendly(message: str) -> str:
    low = message.lower()
    if "login" in low or "cookies" in low or "rate-limit" in low or "rate limit" in low:
        return (
            "Платформа требует авторизацию или временно ограничила доступ "
            "с этого адреса. Попробуйте позже или другую ссылку."
        )
    if "unsupported url" in low:
        return "Ссылка не поддерживается. Нужна ссылка на конкретное видео, рилс или клип."
    if "private" in low:
        return "Видео приватное — скачать без доступа к аккаунту нельзя."
    if "winerror" in low or "connection reset" in low or "timed out" in low or "unable to download api page" in low:
        return (
            "Сайт платформы недоступен из вашей сети "
            "(например, YouTube заблокирован, TikTok ограничен для российских IP). "
            "С зарубежного VPS будет работать. Попробуйте ссылку с другой платформы."
        )
    if "http error" in low or "unable to download" in low:
        return "Платформа не отдала видео. Попробуйте ещё раз позже."
    return "Не удалось получить видео: " + message[:300]


def _has_audio(info: dict) -> bool:
    for fmt in info.get("formats") or []:
        if fmt.get("vcodec") in (None, "none") and fmt.get("acodec") not in (None, "none"):
            return True
        if fmt.get("acodec") not in (None, "none") and fmt.get("height"):
            return True
    return False


def _fmt_size(fmt: dict) -> int:
    return fmt.get("filesize") or fmt.get("filesize_approx") or 0


def _estimate_size(info: dict, cap: int | None = None, audio_only: bool = False):
    formats = info.get("formats") or []
    if audio_only:
        sizes = [
            _fmt_size(f)
            for f in formats
            if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")
        ]
        return max(sizes) if sizes else None
    videos = [
        f
        for f in formats
        if f.get("vcodec") not in (None, "none")
        and (cap is None or (f.get("height") or 0) <= cap)
    ]
    if not videos:
        return None
    best = max(videos, key=lambda f: ((f.get("height") or 0), _fmt_size(f)))
    total = _fmt_size(best)
    if best.get("acodec") in (None, "none"):
        audios = [
            _fmt_size(f)
            for f in formats
            if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")
        ]
        total += max(audios) if audios else 0
    return total or None


def _sanitize(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\n\r\t]+', " ", name).strip()
    return name[:80].strip() or "video"


def _build_result(src_url: str, info: dict) -> dict:
    heights = sorted(
        {
            f["height"]
            for f in (info.get("formats") or [])
            if f.get("height") and f.get("vcodec") not in (None, "none")
        },
        reverse=True,
    )
    qualities = [h for h in QUALITY_STEPS if h in heights] or heights[:3]

    options = [{"id": "best", "label": "Лучшее качество", "size": _estimate_size(info)}]
    for h in qualities:
        options.append(
            {
                "id": str(h),
                "label": f"{h}p",
                "size": _estimate_size(info, cap=h),
            }
        )
    if _has_audio(info):
        options.append(
            {
                "id": "audio",
                "label": "Только звук (m4a)",
                "size": _estimate_size(info, audio_only=True),
            }
        )

    return {
        "url": src_url,
        "title": (info.get("title") or "video").strip(),
        "uploader": (
            info.get("uploader")
            or info.get("channel")
            or info.get("uploader_id")
            or ""
        ),
        "duration": int(info.get("duration") or 0),
        "platform": info.get("extractor_key") or info.get("extractor") or "",
        "thumbnail": info.get("thumbnail") or "",
        "options": options,
    }


def resolve_sync(url: str) -> dict:
    opts = dict(BASE_OPTS)
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as exc:
        raise ExtractError(_friendly(str(exc))) from exc
    if not info:
        raise ExtractError("Не удалось получить данные по ссылке")
    if info.get("_type") in ("playlist", "multi_video"):
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise ExtractError("По ссылке не нашлось видео")
        info = entries[0]
    return _build_result(url, info)


def format_selector(quality: str) -> str:
    if quality == "best":
        return "bv*+ba/b"
    height = int(quality)
    return f"bv*[height<={height}]+ba/b[height<={height}]/bv*+ba/b"


def download_sync(url: str, quality: str) -> tuple[Path, Path, str, str]:
    root = Path(settings.download_dir)
    root.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(dir=root))

    if quality == "audio":
        fmt = "ba/b"
        opts_extra = {
            "postprocessors": [
                {"key": "FFmpegExtractAudio", "preferredcodec": "m4a"}
            ]
        }
    else:
        fmt = format_selector(quality)
        opts_extra = {"merge_output_format": "mp4"}

    opts = {
        **BASE_OPTS,
        "format": fmt,
        "format_sort": ["vcodec:h264", "acodec:aac"],
        "outtmpl": {"default": str(work / "%(id)s.%(ext)s")},
        "paths": {"temp": str(work)},
        **opts_extra,
    }

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if not info:
                raise ExtractError("Платформа не отдала видео")
            if info.get("_type") in ("playlist", "multi_video"):
                entries = [e for e in (info.get("entries") or []) if e]
                if not entries:
                    raise ExtractError("По ссылке не нашлось видео")
                info = entries[0]
            requested = info.get("requested_downloads") or [{}]
            filepath = requested[-1].get("filepath")
            if not filepath:
                raise ExtractError("Файл не найден после скачивания")
            ext = (requested[-1].get("ext") or Path(filepath).suffix.lstrip(".") or "mp4").lower()
            title = _sanitize(info.get("title") or "video")
            media_type = {
                "mp4": "video/mp4",
                "m4a": "audio/mp4",
                "webm": "video/webm",
                "mkv": "video/x-matroska",
            }.get(ext, "application/octet-stream")
            return Path(filepath), work, f"{title}.{ext}", media_type
    except Exception:
        shutil.rmtree(work, ignore_errors=True)
        raise
