# Fake Xtream Codes panel

Stdlib-only Python server that behaves like an Xtream Codes panel for development and demos.

```sh
python3 tools/fake-xtream/server.py --port 8089 --name Home
python3 tools/fake-xtream/server.py --port 8090 --name Second   # a second "provider"
```

- Credentials: `demo` / `demo`. Wrong credentials answer `{"user_info": {"auth": 0}}` like a real panel.
- Catalog: 3 live categories / 12 channels, Action (31 movies, so "See all" appears) + Drama (5), one series category with 2 series × 2 seasons × 3 episodes.
- Mixed JSON types on purpose (string and numeric ids, string and numeric ratings) to keep the lenient parser honest.
- `/live/…`, `/movie/…`, `/series/…` routes answer `302` to public HLS test streams, so playback goes through the real URL-building path.
- From the Android emulator the host machine is `http://10.0.2.2:<port>`.
