import time
from threading import RLock


class TTLCache:
    def __init__(self, ttl: int, maxlen: int) -> None:
        self._ttl = ttl
        self._maxlen = maxlen
        self._data: dict[str, tuple[float, object]] = {}
        self._lock = RLock()

    def get(self, key: str):
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            ts, value = item
            if time.monotonic() - ts > self._ttl:
                del self._data[key]
                return None
            return value

    def set(self, key: str, value: object) -> None:
        with self._lock:
            if len(self._data) >= self._maxlen:
                oldest = min(self._data, key=lambda k: self._data[k][0])
                del self._data[oldest]
            self._data[key] = (time.monotonic(), value)

    def invalidate(self, key: str) -> None:
        with self._lock:
            self._data.pop(key, None)
