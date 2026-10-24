from __future__ import annotations
import math
import random
import csv
import io
import sqlite3
import difflib
import hashlib
import tempfile
import sys
import queue
import threading
import time
import datetime
import re
import ctypes
from ctypes import wintypes
import os
import shutil
from pathlib import Path
from PySide6.QtCore import Qt, QTimer, QPoint, QPointF, QRectF, QThread, Signal, QSettings, QObject, QEvent, QUrl, QSize
from PySide6.QtGui import QColor, QIcon, QPainter, QPainterPath, QPen, QPixmap, QRadialGradient, QFont, QDesktopServices, QDragEnterEvent, QDropEvent
from PySide6.QtWidgets import QApplication, QWidget, QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton, QFrame, QScrollArea, QComboBox, QMessageBox, QFileDialog, QGridLayout, QSizeGrip, QSizePolicy, QPlainTextEdit, QProgressBar, QSlider, QMenu, QStackedWidget
import json
import urllib.error
import urllib.request
from html.parser import HTMLParser
import http.server
import hmac
import secrets
import subprocess
import urllib.parse
import webbrowser

APP_DIR = Path(__file__).resolve().parent.parent

EMOJI_PATTERN = re.compile(
    "[\U0001F000-\U0001FAFF\U0001FC00-\U0001FFFD"
    "\U00002600-\U000027BF\U00002300-\U000023FF"
    "\U0001F1E6-\U0001F1FF\U0001F3FB-\U0001F3FF"
    "\uFE0F\uFE0E\u200D\u20E3]"
)

APP_COMMANDS = {
    "Calculator": "calc.exe",
    "Notepad": "notepad.exe",
}

PROJECT_TEXT_SUFFIXES = {
    ".py", ".md", ".txt", ".json", ".csv", ".html", ".css", ".js", ".ts",
    ".jsx", ".tsx", ".yml", ".yaml", ".toml", ".ini", ".sql", ".xml", ".cfg", ".bat", ".ps1",
}

PROJECT_IGNORED_DIRS = {
    ".git", ".venv", "venv", "node_modules", ".ki-backups", "__pycache__", "dist", "build",
    "target", ".tox", ".mypy_cache", ".pytest_cache", ".next", ".turbo",
}

_SELF_UPDATE_REPLY_INDEX = -1

def line_icon(name, color="#e3e5e8"):
    """Create a crisp, theme-independent line icon for Ki's compact controls."""
    pixmap = QPixmap(24, 24)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    painter.setPen(QPen(QColor(color), 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
    painter.setBrush(Qt.BrushStyle.NoBrush)
    path = QPainterPath()

    if name == "settings":
        points = []
        for index in range(24):
            angle = math.pi * 2 * index / 24 - math.pi / 2
            radius = 9 if index % 2 == 0 else 7.4
            points.append((12 + math.cos(angle) * radius, 12 + math.sin(angle) * radius))
        path.moveTo(*points[0])
        for point in points[1:]:
            path.lineTo(*point)
        path.closeSubpath()
        painter.drawPath(path)
        painter.drawEllipse(QRectF(9.2, 9.2, 5.6, 5.6))
    elif name == "chat":
        path.addRoundedRect(QRectF(4, 4.5, 16, 12.5), 3, 3)
        path.moveTo(8, 17)
        path.lineTo(6, 20)
        path.lineTo(12, 17)
        painter.drawPath(path)
        painter.drawLine(8, 9, 16, 9)
        painter.drawLine(8, 12.5, 14, 12.5)
    elif name == "controls":
        for y, knob_x in ((6, 9), (12, 15), (18, 7)):
            painter.drawLine(4, y, 20, y)
            painter.drawEllipse(QRectF(knob_x - 1.5, y - 1.5, 3, 3))
    elif name == "activity":
        painter.drawLine(5, 5, 5, 19)
        for y, width in ((7, 12), (12, 9), (17, 11)):
            painter.drawLine(10, y, 10 + width, y)
    elif name == "add_app":
        for x, y in ((4, 4), (12.5, 4), (4, 12.5)):
            painter.drawRoundedRect(QRectF(x, y, 6, 6), 1.3, 1.3)
        painter.drawLine(15.5, 12.5, 15.5, 20)
        painter.drawLine(12, 16.25, 19, 16.25)
    elif name == "play":
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(color))
        path.moveTo(8, 5)
        path.lineTo(19, 12)
        path.lineTo(8, 19)
        path.closeSubpath()
        painter.drawPath(path)
    elif name == "pause":
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(color))
        painter.drawRoundedRect(QRectF(6.5, 5, 4.2, 14), 1.2, 1.2)
        painter.drawRoundedRect(QRectF(13.3, 5, 4.2, 14), 1.2, 1.2)
    elif name in {"previous", "next"}:
        direction = -1 if name == "previous" else 1
        edge_x = 6 if direction < 0 else 18
        painter.drawLine(edge_x, 6, edge_x, 18)
        if direction < 0:
            path.moveTo(17, 6)
            path.lineTo(8, 12)
            path.lineTo(17, 18)
        else:
            path.moveTo(7, 6)
            path.lineTo(16, 12)
            path.lineTo(7, 18)
        path.closeSubpath()
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(color))
        painter.drawPath(path)
    elif name == "minimize":
        painter.drawLine(5, 12, 19, 12)
    elif name == "close":
        painter.drawLine(6, 6, 18, 18)
        painter.drawLine(18, 6, 6, 18)

    painter.end()
    return QIcon(pixmap)

def text_for_speech(text):
    """Remove emoji pictographs from spoken output while retaining chat text."""
    cleaned = EMOJI_PATTERN.sub(" ", text)
    cleaned = re.sub(r"\s+([,.;:!?])", r"\1", cleaned)
    return re.sub(r"\s+", " ", cleaned).strip()

def audio_level_percent(samples):
    peak = max((abs(sample) for sample in samples), default=0)
    decibels = 20 * math.log10(max(peak, 32) / 32768)
    return max(0, min(100, round((decibels + 60) * (100 / 60))))

def system_speech_voices():
    """Read SAPI voice names from the registry without initializing COM in the GUI thread."""
    try:
        import winreg

        voices = []
        roots = ((winreg.HKEY_LOCAL_MACHINE, "HKEY_LOCAL_MACHINE"), (winreg.HKEY_CURRENT_USER, "HKEY_CURRENT_USER"))
        token_path = r"SOFTWARE\Microsoft\Speech\Voices\Tokens"
        for root, root_name in roots:
            try:
                with winreg.OpenKey(root, token_path) as token_key:
                    token_count = winreg.QueryInfoKey(token_key)[0]
                    for index in range(token_count):
                        token = winreg.EnumKey(token_key, index)
                        try:
                            with winreg.OpenKey(token_key, token + r"\Attributes") as attributes:
                                name = str(winreg.QueryValueEx(attributes, "Name")[0])
                                language = str(winreg.QueryValueEx(attributes, "Language")[0]).casefold()
                        except OSError:
                            name, language = token, ""
                        lowered = name.casefold()
                        voices.append({
                            "id": root_name + "\\" + token_path + "\\" + token,
                            "name": name,
                            "english": "english" in lowered or language in {"409", "809", "c09", "1009", "1409"},
                        })
            except OSError:
                continue
        return voices
    except Exception:
        return []

def requested_app(text):
    """Recognize direct requests for the small set of apps Ki may open."""
    normalized = text.casefold()
    verb = re.search(r"\b(?:open|launch|buksan)\b", normalized)
    if not verb:
        return None
    requested = normalized[verb.end():]
    for app_name, aliases in (("Calculator", ("calculator", "calc")), ("Notepad", ("notepad",))):
        if any(re.search(rf"\b{re.escape(alias)}\b", requested) for alias in aliases):
            return app_name
    return None

def safe_project_relative_path(relative):
    parts = [part.casefold() for part in relative.parts]
    name = relative.name.casefold()
    blocked_names = {
        ".env", ".netrc", ".npmrc", "credentials", "authorized_keys", "id_rsa", "id_ed25519",
    }
    blocked_prefixes = (".env.", "credentials.", "secrets.", "id_rsa.", "id_ed25519.")
    return (
        relative.suffix.casefold() in PROJECT_TEXT_SUFFIXES
        and not any(part in PROJECT_IGNORED_DIRS for part in parts[:-1])
        and name not in blocked_names
        and not name.startswith(blocked_prefixes)
    )

def current_date_reply(text):
    """Answer current local date/time questions directly from the laptop clock."""
    normalized = text.casefold()
    asks_date = bool(re.search(r"\b(?:date|day)\b", normalized))
    asks_time = bool(re.search(r"\btime\b", normalized))
    asks_about_now = bool(re.search(r"\b(?:today|current|now)\b", normalized))
    asks_which_date = bool(re.search(
        r"\bwhat(?:'s| is)?\s+(?:the\s+)?(?:date|day)(?:\s+is\s+it)?\s*[?!.]*$", normalized
    ))
    asks_which_time = bool(re.search(
        r"\bwhat(?:'s| is)?\s+(?:the\s+)?time(?:\s+is\s+it)?\s*[?!.]*$", normalized
    ))
    wants_date = asks_date and (asks_about_now or asks_which_date)
    wants_time = asks_time and (asks_about_now or asks_which_time)
    if not wants_date and not wants_time:
        return None

    now = datetime.datetime.now().astimezone()
    date_text = f"{now.strftime('%A, %B')} {now.day}, {now.year}"
    time_text = now.strftime("%I:%M %p").lstrip("0")
    if wants_date and wants_time:
        return f"It’s {date_text}, {time_text} on your laptop’s local clock."
    if wants_time:
        return f"It’s {time_text} on your laptop’s local clock."
    return f"Today is {date_text} (local date: {now:%Y-%m-%d})."

def self_update_reply(text):
    """Explain Ki's real, approval-gated path for proposing code changes to itself."""
    normalized = text.casefold()
    mentions_ki = bool(re.search(r"\b(?:you|yourself|ki|your program|your code)\b", normalized))
    asks_update = bool(re.search(r"\b(?:update|upgrade|improve|change|edit|modify)\b", normalized))
    asks_self = bool(re.search(r"\b(?:yourself|your program|your code|you)\b", normalized))
    if not (mentions_ki and asks_update and asks_self):
        return None
    global _SELF_UPDATE_REPLY_INDEX
    replies = (
        "Yeah, I can help improve Ki’s code. Pick a project file, tell me what you want changed, and I’ll show you a preview to approve. Then restart Ki to see it.",
        "Sure—I can help draft an upgrade for Ki. Choose the file in Project mode and describe the change; you can review and approve it before saving. Restart Ki to apply it.",
        "I can help with that. Select the Ki file you want to change in Project mode, and I’ll prepare a preview for you. You stay in control, and a restart loads approved changes.",
        "Yep, I can help make Ki better. You choose the file and approve any edit I suggest. After it’s saved, close and reopen Ki to use the change.",
    )
    _SELF_UPDATE_REPLY_INDEX = (_SELF_UPDATE_REPLY_INDEX + 1) % len(replies)
    return replies[_SELF_UPDATE_REPLY_INDEX]
