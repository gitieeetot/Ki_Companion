from __future__ import annotations

from .shared import *

_OLLAMA_START_LOCK = threading.Lock()

class VoiceInputWorker(QThread):
    """Captures selected Windows audio sources in memory and transcribes offline."""

    status = Signal(str)
    recognized = Signal(str)
    failed = Signal(str)
    level = Signal(int)
    ready = Signal(str)
    _model_lock = threading.Lock()
    _cached_model = None

    def __init__(self, device=None, hold_to_talk=False, source="microphone", continuous=False):
        super().__init__()
        self.language = "English"
        self.device = device
        self.hold_to_talk = hold_to_talk
        self.source = source if source in {"microphone", "laptop", "both"} else "microphone"
        self.continuous = continuous
        self.stop_event = threading.Event()
        self.audio_queue = queue.Queue(maxsize=16)
        self.loopback_audio = None
        self.loopback_pyaudio = None

    def stop_listening(self):
        self.stop_event.set()

    def _enqueue_audio(self, source, data):
        try:
            self.audio_queue.put_nowait((source, data))
        except queue.Full:
            try:
                self.audio_queue.get_nowait()
                self.audio_queue.put_nowait((source, data))
            except (queue.Empty, queue.Full):
                pass

    def _audio_callback(self, indata, frames, time_info, status):
        data = bytes(indata)
        try:
            samples = memoryview(data).cast("h")
            self.level.emit(audio_level_percent(samples))
        except (TypeError, ValueError):
            pass
        self._enqueue_audio("microphone", data)

    def _loopback_callback(self, in_data, frame_count, time_info, status):
        if in_data:
            import array
            samples = array.array("h")
            samples.frombytes(in_data)
            channels = max(1, int(self.loopback_channels))
            if channels > 1:
                mono = array.array("h", (sum(samples[i:i + channels]) // channels for i in range(0, len(samples) - channels + 1, channels)))
            else:
                mono = samples
            self.level.emit(audio_level_percent(mono))
            self._enqueue_audio("laptop", mono.tobytes())
        return (None, self.loopback_pyaudio.paContinue)

    def load_model(self, Model):
        with self._model_lock:
            if self._cached_model is None:
                self.status.emit("Loading offline English speech model...")
                type(self)._cached_model = Model(lang="en-us")
            return self._cached_model

    def run(self):
        mic_stream = None
        try:
            from vosk import KaldiRecognizer, Model
            model = self.load_model(Model)
            if self.stop_event.is_set():
                return
            recognizers = {}
            sample_rates = {}
            source_names = []
            if self.source in {"microphone", "both"}:
                import sounddevice as sd
                device_info = sd.query_devices(self.device, "input")
                sample_rates["microphone"] = int(device_info["default_samplerate"])
                recognizers["microphone"] = KaldiRecognizer(model, sample_rates["microphone"])
                mic_stream = sd.RawInputStream(
                    samplerate=sample_rates["microphone"],
                    blocksize=max(1024, sample_rates["microphone"] // 10),
                    dtype="int16", channels=1, device=self.device, latency="low",
                    callback=self._audio_callback,
                )
                mic_stream.start()
                source_names.append("microphone")
            if self.source in {"laptop", "both"}:
                import pyaudiowpatch as pyaudio
                self.loopback_pyaudio = pyaudio.PyAudio()
                loopback = self.loopback_pyaudio.get_default_wasapi_loopback()
                if not loopback:
                    raise RuntimeError("Windows could not find a laptop audio output to capture.")
                self.loopback_channels = max(1, int(loopback.get("maxInputChannels", 2)))
                sample_rates["laptop"] = int(loopback["defaultSampleRate"])
                recognizers["laptop"] = KaldiRecognizer(model, sample_rates["laptop"])
                self.loopback_audio = self.loopback_pyaudio.open(
                    format=pyaudio.paInt16, channels=self.loopback_channels,
                    rate=sample_rates["laptop"], input=True,
                    input_device_index=loopback["index"], frames_per_buffer=1024,
                    stream_callback=self._loopback_callback,
                )
                source_names.append("laptop audio")
            if not self.stop_event.is_set():
                self.ready.emit(" + ".join(source_names))
                self.status.emit(f"LISTENING · {' + '.join(source_names).upper()}")
            segments = []
            last_segment_at = None
            deadline = None if self.continuous else time.monotonic() + (120 if self.hold_to_talk else 30)
            capture_stopped = False
            while True:
                if deadline is not None and time.monotonic() >= deadline:
                    self.stop_event.set()
                if self.stop_event.is_set() and not capture_stopped:
                    if self.loopback_audio is not None:
                        self.loopback_audio.stop_stream()
                    if mic_stream is not None:
                        mic_stream.stop()
                    capture_stopped = True
                try:
                    source, chunk = self.audio_queue.get(timeout=0.05)
                except queue.Empty:
                    if capture_stopped:
                        break
                    if deadline is not None and time.monotonic() >= deadline:
                        self.stop_event.set()
                        continue
                    if not self.continuous and segments and last_segment_at and time.monotonic() - last_segment_at > 0.45:
                        break
                    continue
                recognizer = recognizers.get(source)
                if recognizer is None:
                    continue
                if recognizer.AcceptWaveform(chunk):
                    text = json.loads(recognizer.Result()).get("text", "").strip()
                    if text:
                        last_segment_at = time.monotonic()
                        if self.continuous:
                            self.recognized.emit(text)
                        else:
                            segments.append(text)
                if not self.continuous and segments and last_segment_at and time.monotonic() - last_segment_at > 0.45 and self.audio_queue.empty():
                    break
            final_segments = []
            for source, recognizer in recognizers.items():
                text = json.loads(recognizer.FinalResult()).get("text", "").strip()
                if text:
                    final_segments.append(text)
            if self.continuous:
                if final_segments:
                    self.recognized.emit(" ".join(final_segments))
            else:
                complete_text = " ".join(segments + final_segments).strip()
                if complete_text:
                    self.recognized.emit(complete_text)
                elif not self.stop_event.is_set():
                    self.failed.emit("I didn’t catch that. Check the selected audio source and try again.")
        except Exception as error:
            self.failed.emit(
                "Audio input could not start. Check Windows microphone privacy settings and, for laptop audio, install the updated requirements. "
                f"Details: {error}"
            )
        finally:
            try:
                if self.loopback_audio is not None:
                    self.loopback_audio.stop_stream()
                    self.loopback_audio.close()
            except Exception:
                pass
            if self.loopback_pyaudio is not None:
                try:
                    self.loopback_pyaudio.terminate()
                except Exception:
                    pass
            if mic_stream is not None:
                try:
                    mic_stream.stop()
                    mic_stream.close()
                except Exception:
                    pass

class SpeechWorker(QThread):
    """Speaks replies with the selected installed Windows voice."""

    failed = Signal(str)
    notice = Signal(str)
    speaking = Signal(bool)

    def __init__(self, text="", voice_choice=None, rate=168, streaming=False):
        super().__init__()
        self.voice_choice = voice_choice or ""
        self.rate = rate
        self.text_queue = queue.Queue()
        self.input_finished = threading.Event()
        self._speaking_state = False
        if text:
            self.text_queue.put(text_for_speech(text))
        if not streaming:
            self.input_finished.set()
        self.stop_event = threading.Event()

    def enqueue(self, text):
        cleaned = text_for_speech(text)
        if cleaned and not self.input_finished.is_set() and not self.stop_event.is_set():
            self.text_queue.put(cleaned)

    def finish_stream(self):
        self.input_finished.set()

    def stop_speaking(self):
        self.stop_event.set()

    def set_speaking_state(self, active):
        if active != self._speaking_state:
            self._speaking_state = active
            self.speaking.emit(active)

    def run(self):
        engine = None
        try:
            import pyttsx3

            engine = pyttsx3.init("sapi5")
            if self.voice_choice.startswith("sapi:"):
                engine.setProperty("voice", self.voice_choice[5:])
            else:
                voices = engine.getProperty("voices") or []
                preferred = ("aria", "jenny", "zira", "guy", "david")
                english_voices = []
                for voice in voices:
                    details = f"{voice.name} {voice.id} {getattr(voice, 'languages', [])}".casefold()
                    if any(tag in details for tag in ("en-us", "en_us", "english", "en-gb", "en_uk")):
                        english_voices.append(voice)
                selected = next((voice for name in preferred for voice in english_voices if name in voice.name.casefold()), None)
                if selected is not None:
                    engine.setProperty("voice", selected.id)
            engine.setProperty("rate", self.rate)
            engine.setProperty("volume", 0.96)
            engine.startLoop(False)
            while not self.stop_event.is_set():
                try:
                    phrase = self.text_queue.get_nowait()
                except queue.Empty:
                    if self.input_finished.is_set() and not engine.isBusy():
                        break
                    self.set_speaking_state(False)
                    time.sleep(0.025)
                    continue
                if phrase:
                    engine.say(phrase)
                    while engine.isBusy() and not self.stop_event.is_set():
                        self.set_speaking_state(True)
                        engine.iterate()
                        time.sleep(0.01)
                    self.set_speaking_state(False)
        except Exception as error:
            if not self.stop_event.is_set():
                self.failed.emit(f"Voice output is unavailable: {error}")
        if engine is not None:
            self.set_speaking_state(False)
            try:
                engine.stop()
            except Exception:
                pass
            try:
                engine.endLoop()
            except Exception:
                pass

def ollama_api_ready(timeout=0.5):
    request = urllib.request.Request("http://127.0.0.1:11434/api/tags", method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout):
            return True
    except urllib.error.HTTPError as error:
        return error.code < 500
    except (urllib.error.URLError, TimeoutError, OSError):
        return False

def find_opencode_launcher():
    """Find OpenCode's Windows launcher, including npm-installed command shims."""
    candidates = []
    for command in ("opencode.cmd", "opencode.exe", "opencode"):
        found = shutil.which(command)
        if found:
            candidates.append(Path(found))
    npm_root = Path(os.environ.get("APPDATA", "")) / "npm"
    candidates.extend((
        npm_root / "opencode.cmd",
        npm_root / "opencode.exe",
        npm_root / "node_modules" / "@opencode" / "cli" / "bin" / "opencode.exe",
    ))
    return next((str(path) for path in candidates if path.is_file()), "")

def opencode_project_config_status():
    """Confirm this project's OpenCode default is the installed local Ollama model."""
    try:
        config = json.loads((APP_DIR / "opencode.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return config.get("model") == "ollama/qwen3:1.7b"

def local_agent_tool_status():
    """Read local Ollama readiness and OpenCode installation/config without launching tools."""
    return {
        "Ollama local service": ollama_api_ready(timeout=0.35),
        "Aider CLI on PATH": bool(shutil.which("aider")),
        "OpenCode launcher": find_opencode_launcher(),
        "OpenCode local model configured": opencode_project_config_status(),
    }

class OpenCodeMonitor:
    """Opt-in, loopback-only receiver for redacted OpenCode event names."""

    ALLOWED_EVENTS = {
        "session.created", "session.updated", "session.status", "session.idle",
        "session.error", "permission.asked", "permission.replied",
        "tool.execute.before", "tool.execute.after",
    }

    def __init__(self):
        self.directory = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData" / "Local"))) / "Ki"
        self.preference_path = self.directory / "opencode_monitor_enabled.json"
        self.bridge_path = self.directory / "opencode_monitor.json"
        self.events = queue.Queue(maxsize=40)
        self.activity_history = []
        self._activity_lock = threading.Lock()
        self.server = None
        self.server_thread = None
        try:
            enabled = json.loads(self.preference_path.read_text(encoding="utf-8")).get("enabled") is True
        except (OSError, json.JSONDecodeError, AttributeError):
            enabled = False
        if enabled:
            try:
                self.enable()
            except OSError:
                self._close_server()

    @property
    def enabled(self):
        return self.server is not None

    def enable(self):
        if self.enabled:
            return
        self.directory.mkdir(parents=True, exist_ok=True)
        token = secrets.token_urlsafe(32)
        monitor = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.path != "/event":
                    self.send_error(404)
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length < 1 or length > 1024:
                        self.send_error(413)
                        return
                    body = json.loads(self.rfile.read(length).decode("utf-8"))
                except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                    self.send_error(400)
                    return
                auth = self.headers.get("Authorization", "")
                if not hmac.compare_digest(auth, "Bearer " + token):
                    self.send_error(403)
                    return
                event_type = body.get("type") if isinstance(body, dict) else None
                if event_type not in monitor.ALLOWED_EVENTS:
                    self.send_error(400)
                    return
                record = {"type": event_type, "time": time.time()}
                with monitor._activity_lock:
                    monitor.activity_history.append(record)
                    del monitor.activity_history[:-100]
                try:
                    monitor.events.put_nowait(record)
                except queue.Full:
                    try:
                        monitor.events.get_nowait()
                    except queue.Empty:
                        pass
                    monitor.events.put_nowait(record)
                self.send_response(204)
                self.end_headers()

            def log_message(self, format, *args):
                return

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True, name="KiOpenCodeMonitor")
        self.server_thread.start()
        try:
            self.bridge_path.write_text(json.dumps({"port": self.server.server_port, "token": token}), encoding="utf-8")
            self.preference_path.write_text(json.dumps({"enabled": True}), encoding="utf-8")
        except OSError:
            self._close_server()
            raise

    def disable(self):
        self.preference_path.unlink(missing_ok=True)
        self._close_server()

    def close_server(self):
        """Stop this process's listener while preserving the user's opt-in choice."""
        self._close_server()

    def _close_server(self):
        server, self.server = self.server, None
        if server is not None:
            server.shutdown()
            server.server_close()
        if self.server_thread is not None:
            self.server_thread.join(timeout=1.0)
            self.server_thread = None
        self.bridge_path.unlink(missing_ok=True)

    def recent_events(self):
        items = []
        while True:
            try:
                items.append(self.events.get_nowait())
            except queue.Empty:
                break
        return items

    def latest_event(self):
        latest = None
        while True:
            try:
                latest = self.events.get_nowait()
            except queue.Empty:
                break
        return latest

    def activity_snapshot(self):
        """Return a bounded, in-memory event history without session content."""
        with self._activity_lock:
            return list(self.activity_history)

def start_local_ollama(timeout=18.0):
    with _OLLAMA_START_LOCK:
        return _start_local_ollama_locked(timeout)

def _start_local_ollama_locked(timeout):
    """Start Ollama's local server hidden when Ki is used without VS Code."""
    if ollama_api_ready():
        return True, ""
    executable = shutil.which("ollama.exe") or shutil.which("ollama")
    candidates = (
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Ollama" / "ollama.exe",
        Path(os.environ.get("PROGRAMFILES", r"C:\Program Files")) / "Ollama" / "ollama.exe",
    )
    if not executable:
        executable = next((str(path) for path in candidates if path.is_file()), None)
    if not executable:
        return False, "I couldn't find Ollama on this laptop. Open the Ollama app, wait for it to start, then send your message again."
    try:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0
        process = subprocess.Popen(
            [executable, "serve"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=flags,
            shell=False,
        )
    except OSError as error:
        return False, f"I couldn't start Ollama ({error}). Open the Ollama app and try again."
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if ollama_api_ready():
            return True, ""
        if process.poll() is not None:
            break
        time.sleep(0.3)
    return False, "Ollama is installed, but its local server didn't start. Open Ollama from the Start menu, wait for it to finish loading, and try again."

class OllamaStartupWorker(QThread):
    ready = Signal(bool, str)

    def run(self):
        is_ready, message = start_local_ollama(timeout=30.0)
        self.ready.emit(is_ready, message)

class ChatWorker(QThread):
    """Calls only the local Ollama service; no cloud API is used."""

    completed = Signal(str)
    delta = Signal(str)
    failed = Signal(str)
    status = Signal(str)

    def __init__(self, messages, max_predict=256, stream=True):
        super().__init__()
        self.messages = messages
        self.max_predict = max_predict
        self.stream = stream

    def consume_stream(self, response):
        pieces = []
        for line in response:
            if not line.strip():
                continue
            item = json.loads(line.decode("utf-8"))
            piece = item.get("message", {}).get("content", "")
            if piece:
                pieces.append(piece)
                self.delta.emit(piece)
            if item.get("done"):
                break
        return "".join(pieces).strip()

    def run(self):
        self.status.emit("LOCAL AI  /  Connecting to Ollama…")
        ready, message = start_local_ollama()
        if not ready:
            self.failed.emit(message)
            return
        body = json.dumps({
            "model": "qwen3:1.7b",
            "messages": self.messages,
            "stream": self.stream,
            "think": False,
            "keep_alive": "5m",
            "options": {"num_ctx": 4096, "num_predict": self.max_predict, "temperature": 0.7},
        }).encode("utf-8")
        request = urllib.request.Request(
            "http://127.0.0.1:11434/api/chat",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=240) as response:
                if self.stream:
                    answer = self.consume_stream(response)
                else:
                    result = json.loads(response.read().decode("utf-8"))
                    answer = result.get("message", {}).get("content", "").strip()
            if answer:
                self.completed.emit(answer)
            else:
                self.failed.emit("Ollama returned an empty reply. Please try again.")
        except urllib.error.HTTPError as error:
            details = error.read().decode("utf-8", errors="replace")
            if error.code == 404 or "not found" in details.lower():
                self.failed.emit("The local model qwen3:1.7b is not installed yet. Run: ollama run qwen3:1.7b")
            else:
                self.failed.emit(f"Ollama returned an error ({error.code}). Check that Ollama is running, then try again.")
        except (urllib.error.URLError, TimeoutError, OSError):
            self.failed.emit("Ollama stopped responding. Open the Ollama app, wait for it to start, then try again.")
        except (json.JSONDecodeError, KeyError, TypeError):
            self.failed.emit("Ollama returned a response Ki could not read. Please try again.")

class WebSearchResultParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results = []
        self.snippets = []
        self._active_result = None
        self._in_heading = False
        self._active_snippet = None
        self._snippet_depth = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        classes = attributes.get("class", "").split()
        void_elements = {"br", "img", "meta", "link", "input", "hr"}
        if self._active_result is not None:
            if tag == "h3":
                self._in_heading = True
        elif self._active_snippet is not None:
            if tag not in void_elements:
                self._snippet_depth += 1
        elif tag == "a" and attributes.get("href") and (
            "result__a" in classes or not self.results
        ):
            self._active_result = {
                "url": attributes["href"],
                "title": "",
                "is_duckduckgo": "result__a" in classes,
                "has_heading": False,
            }
            self._in_heading = "result__a" in classes
        elif classes and set(classes).intersection({
            "result__snippet", "VwiC3b", "yXK7lf", "IsZvec"
        }):
            self._active_snippet = ""
            self._snippet_depth = 1

    def handle_endtag(self, tag):
        void_elements = {"br", "img", "meta", "link", "input", "hr"}
        if self._active_result is not None:
            if tag == "h3":
                self._in_heading = False
                self._active_result["has_heading"] = True
            if tag == "a":
                if self._active_result["is_duckduckgo"] or self._active_result["has_heading"]:
                    result = self._active_result.copy()
                    result.pop("has_heading")
                    result.pop("is_duckduckgo")
                    self.results.append(result)
                self._active_result = None
        elif self._active_snippet is not None:
            if tag not in void_elements:
                self._snippet_depth -= 1
            if self._snippet_depth <= 0:
                self.snippets.append(self._active_snippet.strip())
                self._active_snippet = None

    def handle_data(self, data):
        if self._active_result is not None and (
            self._in_heading or self._active_result["has_heading"]
        ):
            self._active_result["title"] += data
        elif self._active_snippet is not None:
            self._active_snippet += data

class WebSearchWorker(QThread):
    """Fetch a small set of public Google search snippets for an explicitly enabled query."""

    completed = Signal(object)
    failed = Signal(str)

    def __init__(self, query):
        super().__init__()
        self.query = query

    def run(self):
        encoded_query = urllib.parse.urlencode({"q": self.query[:400]})
        try:
            results = self.search_google(encoded_query)
            if not results:
                results = self.search_fallback(encoded_query)
            if not results:
                self.failed.emit("Google did not return readable results and the backup search returned none. Try again later.")
                return
            self.completed.emit(results)
        except urllib.error.HTTPError as error:
            self.failed.emit(f"Web search service returned an error ({error.code}). Try again later.")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            self.failed.emit("Web search is unavailable. Check your internet connection and try again.")

    def search_google(self, encoded_query):
        request = urllib.request.Request(
            f"https://www.google.com/search?{encoded_query}&num=10",
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"},
        )
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                page = response.read(1_500_000).decode("utf-8", errors="replace")
        except urllib.error.HTTPError:
            return []
        parser = WebSearchResultParser()
        parser.feed(page)
        return self.normalize_results(parser)

    def search_fallback(self, encoded_query):
        request = urllib.request.Request(
            f"https://html.duckduckgo.com/html/?{encoded_query}",
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"},
        )
        with urllib.request.urlopen(request, timeout=12) as response:
            page = response.read(1_500_000).decode("utf-8", errors="replace")
        parser = WebSearchResultParser()
        parser.feed(page)
        return self.normalize_results(parser)

    @staticmethod
    def normalize_results(parser):
        results = []
        for index, result in enumerate(parser.results):
            url = result["url"]
            parsed_url = urllib.parse.urlparse(url)
            if parsed_url.path == "/url":
                query_values = urllib.parse.parse_qs(parsed_url.query)
                url = query_values.get("q", query_values.get("url", [""]))[0]
            elif parsed_url.netloc.casefold().endswith("duckduckgo.com") and parsed_url.path.startswith("/l/"):
                query_values = urllib.parse.parse_qs(parsed_url.query)
                url = query_values.get("uddg", [""])[0]
            parsed_url = urllib.parse.urlparse(url)
            hostname = (parsed_url.hostname or "").casefold()
            title = re.sub(r"\s+", " ", result["title"]).strip()
            if (
                parsed_url.scheme not in {"http", "https"}
                or not parsed_url.netloc
                or hostname == "google.com"
                or hostname.endswith(".google.com")
                or hostname == "duckduckgo.com"
                or hostname.endswith(".duckduckgo.com")
                or not title
            ):
                continue
            results.append({
                "title": title[:240],
                "url": url[:2000],
                "snippet": parser.snippets[index][:1000] if index < len(parser.snippets) else "",
            })
            if len(results) >= 5:
                break
        return results
