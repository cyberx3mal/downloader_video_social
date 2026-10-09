const API = (window.DOWNLOADER_API_BASE || "").replace(/\/+$/, "");
let key = localStorage.getItem("dl_key") || "";

const $ = (id) => document.getElementById(id);
const form = $("form");
const input = $("url");
const status = $("status");
const card = $("card");

let current = null;

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
  const params = new URLSearchParams({
    url: current.url,
    quality,
  });
  if (key) params.set("key", key);
  return `${API}/api/download?${params}`;
}

function render(data) {
  current = data;
  const thumb = $("thumb");
  thumb.style.display = "";
  thumb.onerror = () => { thumb.style.display = "none"; };
  thumb.src = data.thumbnail
    ? `${API}/api/thumb?url=${encodeURIComponent(data.thumbnail)}`
    : "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
  $("title").textContent = data.title || "Без названия";
  $("uploader").textContent = data.uploader || "";
  const extra = [];
  if (data.platform) extra.push(data.platform.replace(/Extractor$/i, ""));
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
  for (const opt of data.options) {
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
  chips.querySelector(".chip").classList.add("on");

  const dl = $("dl");
  const update = () => {
    const sel = chips.querySelector(".chip.on");
    dl.href = dlUrl(sel ? sel.dataset.q : "best");
  };
  update();

  card.hidden = false;
}

async function resolve(url) {
  card.hidden = true;
  $("go").disabled = true;
  showStatus("Получаем информацию о видео...");
  try {
    const res = await fetch(`${API}/api/resolve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, key: key || null }),
    });
    if (res.status === 403) {
      const entered = prompt("Введите ключ доступа:");
      if (entered) {
        key = entered.trim();
        localStorage.setItem("dl_key", key);
        $("go").disabled = false;
        return resolve(url);
      }
      throw new Error("Нужен ключ доступа");
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || `Ошибка ${res.status}`);
    render(data);
    showStatus("");
  } catch (err) {
    showStatus(err.message || "Не удалось обработать ссылку", true);
  } finally {
    $("go").disabled = false;
  }
}

form.addEventListener("submit", (e) => {
  e.preventDefault();
  const url = input.value.trim();
  if (url) resolve(url);
});

$("paste").addEventListener("click", async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (text) {
      input.value = text.trim();
      resolve(text.trim());
    }
  } catch {
    input.focus();
  }
});
