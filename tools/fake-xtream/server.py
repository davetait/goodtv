#!/usr/bin/env python3
"""Fake Xtream Codes panel for GoodTV development. Python 3 standard library only.

Serves player_api.php (auth, categories, live/vod/series lists, vod_info, series_info) with a fixed
catalog, and redirects every stream route to a public HLS test stream so the full URL-building and
playback path is exercised. Credentials: demo / demo. Deliberately mixes JSON types the way real
panels do (some ids as strings, some as numbers).

    python3 tools/fake-xtream/server.py --port 8089 --name Home
    # from the Android emulator the host is reachable as http://10.0.2.2:8089
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

USER, PASSWORD = "demo", "demo"

# Live routes redirect to HLS playlists; movie/series routes to progressive MP4s, because a player
# chooses its extractor from the URL it was given (.m3u8 vs .mp4), exactly as with a real panel.
LIVE_STREAMS = [
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
    "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
]
VOD_STREAMS = [
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
    "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
]


def build_catalog(prefix):
    def cat(cid, name):
        return {"category_id": str(cid), "category_name": f"{prefix} {name}".strip(), "parent_id": 0}

    live_cats = [cat(1, "News"), cat(2, "Sports"), cat(3, "Kids")]
    live = []
    for i in range(1, 13):
        cid = 1 + (i - 1) % 3
        sid = 100 + i
        live.append({
            "num": i, "name": f"{prefix} Channel {i}".strip(), "stream_type": "live",
            "stream_id": str(sid) if i % 2 else sid,           # mixed types on purpose
            "stream_icon": "", "epg_channel_id": f"ch{i}.epg", "added": "1609459200",
            "category_id": str(cid) if i % 3 else cid, "custom_sid": "", "tv_archive": 0,
            "direct_source": "", "tv_archive_duration": 0,
        })
    vod_cats = [cat(10, "Action"), cat(11, "Drama")]
    vod = []
    for i in range(1, 32):
        vod.append({
            "num": i, "name": f"Action Movie {i:02d}", "stream_type": "movie", "stream_id": 1000 + i,
            "stream_icon": "", "rating": "7.5" if i % 2 else 7.5, "rating_5based": 3.75, "added": str(1700000000 + i),
            "category_id": "10", "container_extension": "mp4", "custom_sid": "", "direct_source": "",
        })
    for i in range(1, 6):
        vod.append({
            "num": 31 + i, "name": f"Drama Movie {i}", "stream_type": "movie", "stream_id": str(2000 + i),
            "stream_icon": "", "rating": 8.1, "added": str(1700001000 + i),
            "category_id": 11, "container_extension": "mp4",
        })
    series_cats = [cat(20, "Drama Series")]
    series = [
        {"num": 1, "name": "The Fake Show", "series_id": 3001, "cover": "", "plot": "Two seasons of placeholder drama.",
         "cast": "A. Actor, B. Actor", "director": "C. Director", "genre": "Drama", "releaseDate": "2024-01-15",
         "last_modified": "1700002000", "rating": "8.7", "rating_5based": 4.35, "backdrop_path": [], "youtube_trailer": "",
         "episode_run_time": "45", "category_id": "20"},
        {"num": 2, "name": "Another Fake Show", "series_id": "3002", "cover": "", "plot": "More placeholder drama.",
         "genre": "Drama", "releaseDate": "2023-06-01", "rating": 7.2, "category_id": 20},
    ]
    return live_cats, live, vod_cats, vod, series_cats, series


def series_info(series_id, series):
    s = next((x for x in series if str(x["series_id"]) == str(series_id)), None)
    if s is None:
        return {}
    seasons, episodes = [], {}
    for season in (1, 2):
        seasons.append({"air_date": f"202{3 + season}-01-01", "episode_count": 3, "id": season, "name": f"Season {season}",
                        "overview": "", "season_number": season, "cover": "", "cover_big": ""})
        episodes[str(season)] = [
            {"id": str(int(series_id) * 100 + season * 10 + ep), "episode_num": ep, "title": f"S{season}E{ep} Placeholder",
             "container_extension": "mp4", "season": season,
             "info": {"movie_image": "", "plot": f"Episode {ep} of season {season}.", "releasedate": f"202{3 + season}-0{ep}-01",
                      "rating": "8.0", "duration_secs": 2700, "duration": "00:45:00"}}
            for ep in (1, 2, 3)
        ]
    info = {k: s.get(k) for k in ("name", "cover", "plot", "cast", "director", "genre", "releaseDate", "rating", "category_id")}
    info["backdrop_path"] = []
    return {"seasons": seasons, "info": info, "episodes": episodes}


def vod_info(vod_id, vod):
    m = next((x for x in vod if str(x["stream_id"]) == str(vod_id)), None)
    if m is None:
        return {}
    return {
        "info": {"movie_image": "", "tmdb_id": "0", "backdrop_path": "", "youtube_trailer": "", "genre": "Action",
                 "plot": f"Placeholder plot for {m['name']}.", "cast": "A. Actor", "rating": m.get("rating", "7.0"),
                 "director": "C. Director", "releasedate": "2024-05-01", "duration_secs": "5400", "duration": "01:30:00"},
        "movie_data": {"stream_id": m["stream_id"], "name": m["name"], "added": m["added"],
                       "category_id": m["category_id"], "container_extension": m["container_extension"]},
    }


def make_handler(prefix):
    live_cats, live, vod_cats, vod, series_cats, series = build_catalog(prefix)

    class Handler(BaseHTTPRequestHandler):
        def _json(self, payload, code=200):
            body = json.dumps(payload).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _redirect(self, url):
            self.send_response(302)
            self.send_header("Location", url)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            url = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(url.query).items()}
            parts = url.path.strip("/").split("/")
            if url.path == "/player_api.php":
                if q.get("username") != USER or q.get("password") != PASSWORD:
                    return self._json({"user_info": {"auth": 0, "status": "Disabled"}})
                action = q.get("action")
                if action is None:
                    return self._json({
                        "user_info": {"username": USER, "password": PASSWORD, "message": "", "auth": 1, "status": "Active",
                                      "exp_date": "1924905600", "is_trial": "0", "active_cons": "0", "created_at": "1700000000",
                                      "max_connections": "2", "allowed_output_formats": ["m3u8"]},
                        "server_info": {"url": "127.0.0.1", "port": str(self.server.server_port), "https_port": "",
                                        "server_protocol": "http", "timezone": "UTC", "timestamp_now": 1700000000},
                    })
                table = {
                    "get_live_categories": lambda: live_cats,
                    "get_vod_categories": lambda: vod_cats,
                    "get_series_categories": lambda: series_cats,
                    "get_live_streams": lambda: [x for x in live if "category_id" not in q or str(x["category_id"]) == q["category_id"]],
                    "get_vod_streams": lambda: [x for x in vod if "category_id" not in q or str(x["category_id"]) == q["category_id"]],
                    "get_series": lambda: [x for x in series if "category_id" not in q or str(x["category_id"]) == q["category_id"]],
                    "get_vod_info": lambda: vod_info(q.get("vod_id"), vod),
                    "get_series_info": lambda: series_info(q.get("series_id"), series),
                    "get_short_epg": lambda: {"epg_listings": []},
                }
                fn = table.get(action)
                return self._json(fn()) if fn else self._json([], 404)
            if len(parts) == 4 and parts[0] in ("live", "movie", "series"):
                if parts[1] != USER or parts[2] != PASSWORD:
                    return self._json({"error": "forbidden"}, 403)
                ident = parts[3].rsplit(".", 1)[0]
                pool = LIVE_STREAMS if parts[0] == "live" else VOD_STREAMS
                try:
                    idx = int(ident) % len(pool)
                except ValueError:
                    idx = 0
                return self._redirect(pool[idx])
            self._json({"error": "not found"}, 404)

        def log_message(self, fmt, *args):
            print(f"[{prefix or 'fake-xtream'}] {self.path}", flush=True)

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--name", default="", help="prefix for category/channel names so two instances are distinguishable")
    args = ap.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(args.name))
    print(f"fake Xtream panel on http://{args.host}:{args.port}  (user {USER} / pass {PASSWORD}); emulator: http://10.0.2.2:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
