const API = (window.DOWNLOADER_API_BASE || "").replace(/\/+$/, "");
const NATIVE = typeof AndroidDownloader !== "undefined" && AndroidDownloader;
let key = localStorage.getItem("dl_key") || "";

// Ссылки каких соцсетей приложение умеет разбирать.
const SITE_RE = /(?:https?:\/\/)?(?:www\.)?(?:instagram\.com|tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com|vk\.com|vkvideo\.ru|youtube\.com|youtu\.be|m\.youtube\.com)\/[^\s"'<>]+/i;

const $ = (id) => document.getElementById(id);
const form = $("form");
const input = $("url");
const status = $("status");
const card = $("card");
const pasteBtn = $("paste");
const clearBtn = $("clear");

let current = null;
let pending = null;

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function fmtDuration(sec) {
  if (!sec) return "";
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function fmtSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / (1024 * 1024);
  if (mb >= 1024) return `~${(mb / 1024).toFixed(1)} ГБ`;
  return `~${mb < 1 ? mb.toFixed(1) : Math.round(mb)} МБ`;
}

function showStatus(text, isError) {
  status.hidden = !text;
  status.textContent = text || "";
  status.classList.toggle("error", !!isError);
}

function dlUrl(quality) {
  const params = new URLSearchParams({ url: current.url, quality });
  if (key) params.set("key", key);
  return `${API}/api/download?${params}`;
}

function updateClearBtn() {
  clearBtn.hidden = !input.value.trim();
}

// Вытаскивает из произвольного текста первую ссылку на поддерживаемую соцсеть.
function pickSiteLink(text) {
  if (!text) return "";
  const m = String(text).match(SITE_RE);
  if (!m) return "";
  let u = m[0].replace(/[)\]}"'.,;:!?]+$/, "");
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  return u;
}

// Показывает картинку превью.
function showThumb() {
  const thumb = $("thumb");
  const player = $("player");
  player.hidden = true;
  player.pause();
  player.removeAttribute("src");
  player.load();
  thumb.hidden = false;
  thumb.style.display = "";
  thumb.onerror = () => { thumb.style.display = "none"; };
  thumb.src = current && current.thumbnail
    ? current.thumbnail
    : "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
}

// Клик по превью: если есть прямая ссылка на поток — играем сразу,
// иначе качаем низкокачественную копию и играем её в карточке.
function playPreview() {
  if (!NATIVE || !current || !current.url) return;
  if (current.playUrl) {
    const player = $("player");
    $("thumb").style.display = "none";
    player.hidden = false;
    player.src = current.playUrl;
    player.play().catch(() => {
      showStatus("Этот формат нельзя воспроизвести прямо здесь.", true);
      showThumb();
    });
    return;
  }
  showStatus("Загружаем видео для просмотра...");
  try {
    AndroidDownloader.preparePreview(current.url);
  } catch (e) {
    showStatus(e.message || "Не удалось запустить просмотр", true);
  }
}

function render(data) {
  current = data;
  showThumb();

  $("title").textContent = data.title || "Без названия";
  $("uploader").textContent = data.uploader || "";
  const extra = [];
  if (data.platform) extra.push(String(data.platform).replace(/Extractor$/i, ""));
  $("extra").textContent = extra.join(" · ");
  const dur = $("dur");
  if (data.duration) {
    dur.hidden = false;
    dur.textContent = fmtDuration(data.duration);
  } else {
    dur.hidden = true;
  }

  const chips = $("quality");
  chips.innerHTML = "";
  for (const opt of data.options || []) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "chip";
    b.dataset.q = opt.id;
    const size = fmtSize(opt.size);
    b.innerHTML = `${esc(opt.label)}${size ? `<span>${size}</span>` : ""}`;
    b.addEventListener("click", () => {
      chips.querySelectorAll(".chip").forEach((c) => c.classList.remove("on"));
      b.classList.add("on");
      update();
    });
    chips.appendChild(b);
  }
  if (chips.firstChild) chips.firstChild.classList.add("on");

  const update = () => {
    const sel = chips.querySelector(".chip.on");
    const q = sel ? sel.dataset.q : "best";
    if (NATIVE) {
      $("dl").dataset.q = q;
    } else {
      $("dl").href = dlUrl(q);
    }
  };
  update();

  card.hidden = false;
}

function apiResolve(url) {
  return fetch(`${API}/api/resolve`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ url, key: key || null }),
  }).then(async (res) => {
    if (res.status === 403) {
      const entered = prompt("Введите ключ доступа:");
      if (entered) {
        key = entered.trim();
        localStorage.setItem("dl_key", key);
        return apiResolve(url);
      }
      throw new Error("Нужен ключ доступа");
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || `Ошибка ${res.status}`);
    return data;
  });
}

function nativeResolve(url) {
  return new Promise((resolve, reject) => {
    pending = { resolve, reject };
    try {
      AndroidDownloader.resolve(url);
    } catch (e) {
      pending = null;
      reject(e);
    }
  });
}

let waitTimer = null;

function startWaitClock() {
  stopWaitClock();
  const started = Date.now();
  waitTimer = setInterval(() => {
    const sec = Math.round((Date.now() - started) / 1000);
    if (status.hidden) return;
    const base = status.dataset.base || status.textContent.replace(/\s*\(\d+ c\)\.$/, "");
    status.dataset.base = base;
    status.textContent = `${base} (${sec} c).`;
  }, 1000);
}

function stopWaitClock() {
  if (waitTimer) { clearInterval(waitTimer); waitTimer = null; }
  delete status.dataset.base;
}

async function resolve(url) {
  card.hidden = true;
  showStatus("Получаем информацию о видео...");
  startWaitClock();
  try {
    const data = NATIVE ? await nativeResolve(url) : await apiResolve(url);
    render(data);
    showStatus("");
  } catch (err) {
    showStatus(err.message || "Не удалось обработать ссылку", true);
  } finally {
    stopWaitClock();
  }
}

function startFromInput() {
  const url = input.value.trim();
  if (!url) return;
  if (!/^https?:\/\//i.test(url)) {
    showStatus("Ссылка должна начинаться с http:// или https://", true);
    return;
  }
  resolve(url);
}

form.addEventListener("submit", (e) => e.preventDefault());

input.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    startFromInput();
  }
});

input.addEventListener("input", updateClearBtn);

clearBtn.addEventListener("click", () => {
  input.value = "";
  card.hidden = true;
  showStatus("");
  updateClearBtn();
  input.focus();
});

// Иконка слева от строки = вставка из буфера обмена.
pasteBtn.addEventListener("click", () => {
  try {
    const raw = NATIVE ? AndroidDownloader.readClipboard() : "";
    const url = pickSiteLink(raw) || (raw || "").trim();
    if (url) {
      input.value = url;
      updateClearBtn();
      startFromInput();
    } else {
      input.focus();
    }
  } catch {
    input.focus();
  }
});

// Клик по превью запускает встроенный просмотр.
$("thumb").addEventListener("click", playPreview);

$("dl").addEventListener("click", (e) => {
  if (!NATIVE) return;
  e.preventDefault();
  if (!current) return;
  const q = $("dl").dataset.q || "best";
  showStatus("Скачиваем...");
  try {
    AndroidDownloader.download(current.url, q);
  } catch (err) {
    showStatus(err.message || "Не удалось начать скачивание", true);
  }
});

// Возврат в приложение: если в буфере появилась новая ссылка — сразу обрабатываем.
window.onAppResume = () => {
  if (!NATIVE) return;
  let url = "";
  try {
    url = pickSiteLink(AndroidDownloader.readClipboard());
  } catch (ignored) {
    return;
  }
  if (!url) return;
  // Не перезапускаем то же самое.
  if (url === input.value.trim() && (card.hidden === false || status.hidden === false)) return;
  input.value = url;
  updateClearBtn();
  resolve(url);
};

// Callbacks invoked from the Android side.
window.__native = {
  onResolve(json) {
    if (!pending) return;
    try {
      pending.resolve(JSON.parse(json));
    } catch (e) {
      pending.reject(new Error("Некорректный ответ"));
    }
    pending = null;
  },
  onResolveError(msg) {
    if (!pending) return;
    pending.reject(new Error(msg));
    pending = null;
  },
  onProgress(percent) {
    showStatus(`Скачиваем... ${percent}%`);
  },
  onDownloadDone(name) {
    showStatus(`Готово: ${name}. Файл сохранён в «Загрузки/VideoDownloader».`);
  },
  onDownloadError(msg) {
    showStatus(msg || "Не удалось скачать видео", true);
  },
  onPreviewStart() {
    showStatus("Загружаем видео для просмотра...");
  },
  onPreviewProgress(percent) {
    showStatus(`Загружаем видео для просмотра... ${percent}%`);
  },
  onPreviewPath(path) {
    const player = $("player");
    $("thumb").style.display = "none";
    player.hidden = false;
    player.src = path.startsWith("file") ? path : "file://" + path;
    player.play().catch(() => {});
    showStatus("");
  },
  onPreviewDone() {},
  onPreviewError(msg) {
    showStatus(msg || "Не удалось загрузить видео для просмотра", true);
    showThumb();
  },
};

// Ссылка из "Поделиться" или из буфера при открытии приложения.
window.prefillUrl = (url) => {
  if (!url) return false;
  input.value = url;
  updateClearBtn();
  resolve(url);
  return true;
};
window.autoClipboard = window.prefillUrl;

// Первичная проверка буфера, если открыли приложение без ссылки.
if (NATIVE) {
  try {
    const url = pickSiteLink(AndroidDownloader.readClipboard());
    if (url) {
      input.value = url;
      updateClearBtn();
      resolve(url);
    }
  } catch (ignored) {
  }
}
updateClearBtn();
