import os
from pathlib import Path


def _load_dotenv() -> None:
    path = Path(__file__).resolve().parent.parent / ".env"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()

_BASE_DIR = Path(__file__).resolve().parent.parent


class Settings:
    def __init__(self) -> None:
        self.access_key = os.getenv("ACCESS_KEY", "").strip()
        self.download_dir = os.getenv("DOWNLOAD_DIR", str(_BASE_DIR / "downloads"))
        self.rate_resolve = int(os.getenv("RATE_LIMIT_RESOLVE", "30"))
        self.rate_download = int(os.getenv("RATE_LIMIT_DOWNLOAD", "10"))
        self.cache_ttl = int(os.getenv("CACHE_TTL", "900"))
        self.cache_max = int(os.getenv("CACHE_MAX", "200"))
        self.max_filesize = int(os.getenv("MAX_FILESIZE", str(2 * 1024**3)))


settings = Settings()
