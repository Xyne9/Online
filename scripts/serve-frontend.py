"""无窗口静态预览：用 pythonw 启动，日志写 logs/frontend.log。"""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
(root / 'logs').mkdir(exist_ok=True)
sys.stdout = sys.stderr = (root / 'logs/frontend.log').open('a', encoding='utf-8', buffering=1)
ThreadingHTTPServer(('127.0.0.1', 3000), partial(SimpleHTTPRequestHandler, directory=str(root / 'prototype/app'))).serve_forever()
