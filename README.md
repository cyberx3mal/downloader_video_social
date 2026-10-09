# Загрузчик видео (Instagram / TikTok / VK / YouTube)

Сервис: вставляете ссылку — получаете превью, выбираете качество, скачиваете MP4 (или звук в m4a).
Поддержка определяется yt-dlp: обычные видео YouTube и Shorts, рилсы, тиктоки (в т.ч. без водяного знака), VK видео и клипы.

**Важно для РФ:** YouTube заблокирован, TikTok ограничивает контент для российских IP — с домашнего ПК они могут не работать. На зарубежном VPS (Нидерланды/Германия) всё работает. VK и Instagram из РФ доступны.

```
Браузер/телефон → FastAPI + yt-dlp → платформа (стрим на лету, без хранения)
```

Файлы не хранятся: видео скачивается во временную папку и удаляется сразу после отправки.

## Структура

```
app/
  main.py        FastAPI: /api/resolve, /api/download, /api/thumb, раздача фронта
  downloader.py  обёртка над yt-dlp (метаданные, форматы, скачивание)
  cache.py       кэш метаданных (TTL)
  config.py      настройки из .env
  static/        фронтенд (HTML/CSS/JS + PWA)
scripts/gen_icon.py   генерация иконок PWA
Dockerfile, docker-compose.yml   деплой на VPS
.github/workflows/pages.yml      фронт на GitHub Pages (заготовка)
```

## Запуск на ПК (тесты)

Нужны: Python 3.11+, ffmpeg (лежит в `tools\ffmpeg`).

```powershell
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
$env:PATH = "$PWD\tools\ffmpeg;$env:PATH"
Copy-Item .env.example .env   # при желании поменяйте ACCESS_KEY
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Открыть http://127.0.0.1:8000

## Деплой на Render

1. Пушните проект на GitHub.
2. Dashboard → New → **Web Service** → подключите репозиторий, Runtime: **Docker**.
3. Environment Variables: `ACCESS_KEY` (и при желании остальные из таблицы ниже). `.env` на Render не нужен.
4. Health Check Path: `/api/health`. Deploy.

Render даст адрес `имя.onrender.com` с HTTPS — этот адрес сразу можно вписать в `config.example.js` для GitHub Pages.

Нюансы: бесплатный тариф засыпает после ~15 минут простоя (первый запрос ~50 сек просыпается) и требует зарубежную карту; всегда-активный тариф платный.

## Деплой на VPS (Ubuntu 24.04)

```bash
# 1. Docker
curl -fsSL https://get.docker.com | sh

# 2. Проект
#    (скопируйте папку проекта на сервер, например через scp или git clone)
cd downloader

# 3. Настройки — обязательно смените ACCESS_KEY
cp .env.example .env
nano .env

# 4. Запуск
docker compose up -d --build

# 5. Проверка
curl http://127.0.0.1:8000/api/health
```

Сервис будет на `http://IP_СЕРВЕРА:8000`.

### С доменом и HTTPS (когда появится домен)

Раскомментируйте в `docker-compose.yml` сервис `caddy` (ниже), создайте файл `Caddyfile`:

```
ваш-домен.ru {
    reverse_proxy downloader:8000
}
```

и раскомментируйте блок:

```yaml
  caddy:
    image: caddy:2
    container_name: caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
volumes:
  caddy_data:
```

### Обновление yt-dlp

Платформы часто ломают API — yt-dlp нужно обновлять (раз в 1–2 недели):

```bash
docker compose build --no-cache && docker compose up -d
```

## Ключ доступа

Если в `.env` задан `ACCESS_KEY`, все запросы требуют его:
- фронт спросит ключ один раз и запомнит (localStorage);
- API: `?key=...` в query или заголовок `X-Access-Key`.

Пустой `ACCESS_KEY` = открытый режим (только для локальных тестов).

## Конфигурация (.env)

| Переменная | По умолчанию | Описание |
|---|---|---|
| ACCESS_KEY | пусто | ключ доступа |
| RATE_LIMIT_RESOLVE | 30 | запросов /api/resolve в минуту с одного IP |
| RATE_LIMIT_DOWNLOAD | 10 | скачиваний в минуту с одного IP |
| CACHE_TTL | 900 | кэш метаданных, сек |
| MAX_FILESIZE | 2 ГБ | максимум для одного видео |
| DOWNLOAD_DIR | ./downloads | временная папка |

## Фронт на GitHub Pages + бэк на VPS

Можно пользоваться просто через `http://IP:8000` (фронт уже раздаётся бэкендом). Если хотите отдельный фронт на Pages:

1. **Создайте приватный репозиторий** на GitHub и запушьте проект (`.env` и `downloads/` не попадут — в `.gitignore`).
2. **Впишите адрес бэкенда** в `app/static/config.example.js`:
   ```js
   window.DOWNLOADER_API_BASE = "https://downloader.example.com";
   ```
   При деплое workflow копирует его в `config.js`. Локальный `config.js` (пустой) используется, когда фронт раздаётся самим бэкендом.
3. **Включите Pages**: Settings → Pages → Source: **GitHub Actions**. При каждом пуше в `main` фронт задеплоится на `https://логин.github.io/имя-репо/`.
4. **Требования к бэкенду**: только HTTPS (иначе браузер заблокирует запросы со Pages-сайта — mixed content) и домен → см. блок Caddy ниже. CORS на бэкенде уже открыт для всех.

Адрес API хранится в одном месте — `config.js`; менять код при переезде не нужно.

## Ограничения

- Приватные видео без авторизации скачать нельзя (by design: без кук аккаунтов).
- Instagram иногда требует логин даже для публичного контента — попробуйте позже; TikTok и VK работают стабильно.
- Скачивайте только публичный контент и уважайте авторские права.
