from __future__ import annotations

from .shared import *
from .services import OpenCodeMonitor
from .chat import ChatWindow
from .dashboard import DashboardWindow, GitToolsDialog
from .panel import CompanionWindow
from .phone_bridge import PhoneCompanionServer

WALK_STEPS = 20
WALK_DISTANCE_X = 120
WALK_DISTANCE_Y = 70


class RobotWidget(QWidget):
    """Draggable companion face with expressive single- and double-click reactions."""

    def __init__(self):
        super().__init__()
        self.setWindowTitle("Ki Starter")
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.Tool)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.resize(104, 104)
        self.setMouseTracking(True)
        self.setCursor(Qt.CursorShape.OpenHandCursor)
        self.setAcceptDrops(True)
        self.state = "idle"
        self.phase = 0.0
        self._drag_offset = None
        self._press_global = None
        self._dragging = False
        self._hotkey_down = False
        self._stop_hotkey_down = False
        self._quit_requested = False
        self._transition_kind = None
        self._transition_started = 0.0
        self._transition_duration = 0.0
        self.expression = "excited"
        self.expression_until = time.monotonic() + 1.8
        self._click_fx_until = 0.0
        self._click_fx_kind = "spark"
        self.music_singing = False
        self._random_dance_until = 0.0
        self._next_random_dance = time.monotonic() + random.uniform(20, 40)
        self._idle_pose = None
        self._idle_pose_until = 0.0
        self._next_idle_pose = time.monotonic() + random.uniform(25, 45)
        self._walk_origin = None
        self._walk_started = 0.0
        self._walk_duration = 0.0
        self._walk_return_start = None
        self._walk_return_target = None
        self._walk_return_started = 0.0
        self._walk_return_duration = 0.0
        self.idle_expression = "neutral"
        self._next_idle_expression = time.monotonic() + random.uniform(6, 12)
        self._idle_expression_until = 0.0
        self.gaze_x = 0.0
        self.gaze_y = 0.0
        self._last_drag_global = None
        self._device_tick = 0
        self._last_cpu_times = None
        self.device_mood = "neutral"
        self.battery_percent = 255
        self.audio_ready = False
        self.audio_connecting = False
        self.audio_status = "off"
        self.audio_source_status = ""
        self.drag_hover = False
        self.eating_until = 0.0
        self._next_blink = time.monotonic() + random.uniform(3.0, 6.0)
        self._blink_until = 0.0
        self.chat = ChatWindow(self.set_state, self.react)
        self.chat.audio_ready_callback = self.set_audio_ready
        self.chat.music_state_callback = self.set_music_singing
        self.agent_monitor = OpenCodeMonitor()
        self.phone_bridge = PhoneCompanionServer()
        self.phone_bridge.agent_monitor = self.agent_monitor
        self.phone_bridge.chat_received.connect(self.chat.show_phone_conversation)
        self.phone_bridge.speak_requested.connect(self.chat.speak_phone_reply)
        self.phone_bridge.vscode_context_requested.connect(self.chat.get_vscode_selection_for_phone)
        self.dashboard = DashboardWindow(self.chat, self.agent_monitor)
        self.dashboard.quit_callback = self.request_quit
        self.dashboard.phone_bridge = self.phone_bridge
        self.companion = CompanionWindow(self.chat, self.dashboard)
        self.chat.dashboard_navigation_callback = lambda: self.show_companion("controls")
        self.chat.dashboard_settings_callback = self.open_dashboard_audio_settings
        self.dashboard.chat_navigation_callback = lambda: self.show_companion("chat")
        self.animator = QTimer(self)
        self.animator.setInterval(40)
        self.animator.timeout.connect(self.animate)
        self.animator.start()
        self.hotkey_timer = QTimer(self)
        self.hotkey_timer.setInterval(20)
        self.hotkey_timer.timeout.connect(self.poll_hold_to_talk)
        self.hotkey_timer.start()
        screen = QApplication.primaryScreen()
        if screen:
            area = screen.availableGeometry()
            self.move(self.clamp_to_safe_area(
                QPoint(area.right() - self.width() - 36, area.bottom() - self.height() - 44),
                screen,
            ))

    def open_chat(self):
        self.show_companion("chat")

    def open_dashboard(self):
        self.show_companion("controls")

    def react_to_tap(self):
        self._click_fx_kind = random.choice(("spark", "wave", "heart"))
        self._click_fx_until = time.monotonic() + 0.72
        self.react(random.choice(("happy", "wink", "curious", "excited")), 0.9)

    def clamp_to_safe_area(self, position, screen=None):
        screen = screen or QApplication.screenAt(
            position + QPoint(self.width() // 2, self.height() // 2)
        ) or QApplication.primaryScreen()
        if screen is None:
            return position
        area = screen.availableGeometry()
        margin_x = min(28, max(0, (area.width() - self.width()) // 2))
        margin_y = min(28, max(0, (area.height() - self.height()) // 2))
        min_x = area.left() + margin_x
        max_x = area.right() - self.width() + 1 - margin_x
        min_y = area.top() + margin_y
        max_y = area.bottom() - self.height() + 1 - margin_y
        return QPoint(
            max(min_x, min(position.x(), max_x)),
            max(min_y, min(position.y(), max_y)),
        )

    def interrupt_idle_pose(self, expression="surprised"):
        if self._walk_origin is not None and self._walk_return_start is None:
            self._walk_return_start = self.pos()
            self._walk_return_target = self._walk_origin
            self._walk_return_started = time.monotonic()
            self._walk_return_duration = 0.65
            self.react(expression, 1.0)
        self._walk_origin = None
        self._walk_started = 0.0
        self._walk_duration = 0.0
        self._idle_pose = None
        self._idle_pose_until = 0.0
        if self._walk_return_start is None:
            self._walk_return_target = None
            self._walk_return_started = 0.0
            self._walk_return_duration = 0.0

    def finish_walk_return(self):
        if self._walk_return_target is not None:
            self.move(self.clamp_to_safe_area(self._walk_return_target))
        self._walk_return_start = None
        self._walk_return_target = None
        self._walk_return_started = 0.0
        self._walk_return_duration = 0.0

    def finish_idle_pose(self):
        self._walk_origin = None
        self._walk_started = 0.0
        self._walk_duration = 0.0
        self._idle_pose = None
        self._idle_pose_until = 0.0

    def handle_face_double_click(self):
        self.react("surprised", 0.8)
        if self.companion.isMinimized():
            return
        page = "controls" if self.companion.isVisible() else "chat"
        self.show_companion(page)

    def show_companion(self, page):
        panel_width = self.companion.width()
        panel_height = self.companion.height()
        screen = QApplication.screenAt(self.frameGeometry().center()) or QApplication.primaryScreen()
        if screen:
            area = screen.availableGeometry()
            x = self.x() + self.width() + 12
            if x + panel_width > area.right():
                x = self.x() - panel_width - 12
            x = max(area.left(), min(x, area.right() - panel_width))
            y = max(area.top(), min(self.y() - 8, area.bottom() - panel_height))
            self.companion.move(x, y)
        self.companion.show_page(page)

    def play_entrance_glitch(self):
        screen = QApplication.screenAt(self.frameGeometry().center()) or QApplication.primaryScreen()
        if screen is None:
            self.show()
            return
        self._transition_kind = "entrance"
        self._transition_started = time.monotonic()
        self._transition_duration = 0.85
        self.setWindowOpacity(0.0)
        self.show()
        self.raise_()
        self.react("surprised", 1.0)

    def open_dashboard_audio_settings(self):
        self.show_companion("controls")
        QTimer.singleShot(100, self.dashboard.open_audio_settings)

    def handle_launch_hotkey(self):
        """Close Ki when its desktop shortcut is pressed again."""
        self.request_quit()

    def request_quit(self):
        if self._quit_requested:
            return
        self._quit_requested = True
        self.companion.hide()
        self.dashboard.hide()
        self.chat.stop_spoken_reply()
        if self.chat.voice_worker is not None and self.chat.voice_worker.isRunning():
            self.chat.voice_worker.stop_listening()
        if not self.isVisible():
            QApplication.quit()
            return
        self._transition_kind = "exit"
        self._transition_started = time.monotonic()
        self._transition_duration = 0.55
        self.setToolTip("Ki is powering down…")

    def react(self, expression, duration=1.8):
        """Show a short, friendly expression in response to an app event."""
        self.expression = expression
        self.expression_until = time.monotonic() + duration
        self.update()

    def set_audio_ready(self, status, source_names=""):
        self.audio_status = status if status in {"connecting", "ready", "off", "error"} else "off"
        self.audio_connecting = status == "connecting"
        self.audio_ready = status == "ready"
        self.audio_source_status = source_names
        self.update()
        self.setToolTip(self.state_tooltip())

    def set_music_singing(self, playing):
        playing = bool(playing)
        if playing != self.music_singing:
            self._random_dance_until = 0.0
            self._next_random_dance = time.monotonic() + random.uniform(20, 40)
            self.interrupt_idle_pose("surprised")
            self._next_idle_pose = time.monotonic() + random.uniform(25, 45)
        self.music_singing = playing
        if self.music_singing:
            self.react("excited", 1.1)
        self.update()
        self.setToolTip(self.state_tooltip())

    def set_state(self, state):
        if state != "idle":
            self._random_dance_until = 0.0
            self._next_random_dance = time.monotonic() + random.uniform(20, 40)
            self.interrupt_idle_pose("concerned")
            self._next_idle_pose = time.monotonic() + random.uniform(25, 45)
        self.state = state
        self.setToolTip(self.state_tooltip())
        self.update()

    def state_tooltip(self):
        if self.audio_connecting:
            return f"Starting audio input · {self.audio_source_status.upper()}"
        if self.audio_ready:
            listening = " · Ki is listening" if self.state == "listening" else ""
            return f"Audio ready · {self.audio_source_status.upper()}{listening}"
        if self.audio_status == "error":
            return "Audio input could not start · check the microphone and privacy settings"
        if self.music_singing and self.state == "idle":
            return "Ki is dancing and singing along · use the dashboard Play/Pause control to stop"
        labels = {
            "idle": "Ki is ready",
            "listening": "Ki is listening",
            "thinking": "Ki is thinking",
            "speaking": "Ki is speaking",
        }
        activity = {
            "charging": f"Laptop is charging · {self.battery_percent}%",
            "low_battery": f"Laptop battery is low · {self.battery_percent}%",
            "busy": "Laptop is busy",
            "idle": "Laptop is mostly idle",
            "neutral": "Laptop activity is moderate",
        }.get(self.device_mood, "")
        description = labels.get(self.state, "Ki")
        if self.audio_status == "off":
            description += " · audio input off"
        if activity:
            description += f" · {activity}"
        return description

    def sample_laptop_status(self):
        if sys.platform != "win32":
            return
        class PowerStatus(ctypes.Structure):
            _fields_ = [
                ("ACLineStatus", wintypes.BYTE),
                ("BatteryFlag", wintypes.BYTE),
                ("BatteryLifePercent", wintypes.BYTE),
                ("SystemStatusFlag", wintypes.BYTE),
                ("BatteryLifeTime", wintypes.DWORD),
                ("BatteryFullLifeTime", wintypes.DWORD),
            ]

        try:
            status = PowerStatus()
            if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status)):
                return
            previous = self.device_mood
            self.battery_percent = status.BatteryLifePercent
            if status.BatteryLifePercent != 255 and status.BatteryLifePercent <= 15 and status.ACLineStatus != 1:
                self.device_mood = "low_battery"
            elif status.ACLineStatus == 1 and status.BatteryLifePercent != 255 and status.BatteryLifePercent < 100:
                self.device_mood = "charging"
            else:
                self.device_mood = "neutral"

            class FileTime(ctypes.Structure):
                _fields_ = [("low", wintypes.DWORD), ("high", wintypes.DWORD)]

            idle_time, kernel_time, user_time = FileTime(), FileTime(), FileTime()
            get_times = ctypes.windll.kernel32.GetSystemTimes
            if get_times(ctypes.byref(idle_time), ctypes.byref(kernel_time), ctypes.byref(user_time)):
                def as_int(value):
                    return (value.high << 32) | value.low
                current = (as_int(idle_time), as_int(kernel_time), as_int(user_time))
                if self._last_cpu_times is not None:
                    old_idle, old_kernel, old_user = self._last_cpu_times
                    idle_delta = current[0] - old_idle
                    total_delta = (current[1] - old_kernel) + (current[2] - old_user)
                    if total_delta > 0 and self.device_mood not in ("charging", "low_battery"):
                        load = 100 * (total_delta - idle_delta) / total_delta
                        self.device_mood = "busy" if load >= 75 else "idle" if load <= 20 else "neutral"
                self._last_cpu_times = current
            if self.state == "idle" and previous != self.device_mood:
                self.update()
            self.setToolTip(self.state_tooltip())
        except (AttributeError, OSError, TypeError, ValueError):
            pass

    def animate(self):
        self.phase = (self.phase + 0.14) % math.tau
        self._device_tick += 1
        now = time.monotonic()
        if self._transition_kind:
            progress = min(1.0, (now - self._transition_started) / self._transition_duration)
            strength = 1 - progress if self._transition_kind == "entrance" else progress
            opacity = progress if self._transition_kind == "entrance" else 1 - progress
            if strength > 0.65 and int(now * 30) % 7 == 0:
                opacity *= 0.35
            self.setWindowOpacity(max(0.0, min(1.0, opacity)))
            if progress >= 1:
                was_exiting = self._transition_kind == "exit"
                self._transition_kind = None
                self.setWindowOpacity(1.0)
                if was_exiting:
                    QApplication.quit()
                    return
        if now >= self._next_blink:
            self._blink_until = now + 0.16
            self._next_blink = now + random.uniform(3.2, 7.0)
        if self.expression and now >= self.expression_until:
            self.expression = None
        if now >= self._click_fx_until:
            self._click_fx_kind = ""
        if self._walk_return_start is not None and self._walk_return_target is not None:
            progress = min(
                1.0,
                max(0.0, (now - self._walk_return_started) / self._walk_return_duration),
            )
            eased = progress * progress * (3 - 2 * progress)
            start = self._walk_return_start
            target = self._walk_return_target
            return_position = QPoint(
                round(start.x() + (target.x() - start.x()) * eased),
                round(start.y() + (target.y() - start.y()) * eased),
            )
            self.move(self.clamp_to_safe_area(return_position))
            if progress >= 1:
                self.finish_walk_return()
        if self.state == "idle" and not self.music_singing:
            if self._random_dance_until and now >= self._random_dance_until:
                self._random_dance_until = 0.0
            if not self._random_dance_until and now >= self._next_random_dance:
                self._random_dance_until = now + random.uniform(3.0, 5.0)
                self._next_random_dance = self._random_dance_until + random.uniform(35, 75)
                self.react(random.choice(("happy", "excited", "wink")), 1.2)
            if self._idle_pose and now >= self._idle_pose_until:
                self.finish_idle_pose()
                self._next_idle_pose = now + random.uniform(45, 90)
            if (
                not self._idle_pose
                and not self._random_dance_until
                and self._drag_offset is None
                and not self.audio_connecting
                and not self.audio_ready
                and not self._transition_kind
                and now >= self._next_idle_pose
            ):
                self._idle_pose = random.choice(("stand", "walk", "sit", "hide"))
                pose_duration = random.uniform(12.0, 15.0) if self._idle_pose == "walk" else random.uniform(3.0, 5.0)
                self._idle_pose_until = now + pose_duration
                self._next_idle_pose = self._idle_pose_until
                if self._idle_pose == "walk":
                    self._walk_origin = self.pos()
                    self._walk_started = now
                    self._walk_duration = self._idle_pose_until - now
                if self._idle_pose == "stand":
                    self.react("excited", 1.0)
                elif self._idle_pose == "sit":
                    self.react("sleepy", 1.0)
                else:
                    self.react("curious", 1.0)
            if self._idle_pose == "walk" and self._walk_origin is not None:
                progress = min(1.0, max(0.0, (now - self._walk_started) / self._walk_duration))
                envelope = math.sin(math.pi * progress)
                step_phase = math.tau * (WALK_STEPS / 2) * progress
                horizontal = round(WALK_DISTANCE_X * math.sin(step_phase) * envelope)
                vertical = round(WALK_DISTANCE_Y * math.sin(step_phase * 2) * envelope)
                walk_position = self._walk_origin + QPoint(horizontal, vertical)
                self.move(self.clamp_to_safe_area(walk_position))
        if self.state == "idle" and not self.expression and now >= self._next_idle_expression:
            self.idle_expression = random.choice(("happy", "curious", "sleepy", "surprised", "wink", "excited", "confused"))
            self._idle_expression_until = now + random.uniform(1.2, 2.4)
            self._next_idle_expression = now + random.uniform(10, 22)
        elif now >= self._idle_expression_until:
            self.idle_expression = "neutral"
        if self._drag_offset is None:
            self.gaze_x *= 0.92
            self.gaze_y *= 0.92
        if self._device_tick >= 50:
            self._device_tick = 0
            self.sample_laptop_status()
        self.update()

    def poll_hold_to_talk(self):
        if sys.platform != "win32" or self._quit_requested:
            return
        user32 = ctypes.windll.user32
        key_down = 0x8000
        control_down = bool(user32.GetAsyncKeyState(0x11) & key_down)
        alt_down = bool(user32.GetAsyncKeyState(0x12) & key_down)
        space_down = bool(user32.GetAsyncKeyState(0x20) & key_down)
        backspace_down = bool(user32.GetAsyncKeyState(0x08) & key_down)
        chord_down = control_down and alt_down and space_down
        stop_chord = control_down and alt_down and backspace_down
        if stop_chord and not self._stop_hotkey_down:
            self.chat.stop_spoken_reply()
        self._stop_hotkey_down = stop_chord
        if chord_down and not self._hotkey_down:
            self._hotkey_down = True
            self.chat.start_hold_to_talk()
        elif not chord_down and self._hotkey_down:
            self._hotkey_down = False
            self.chat.stop_hold_to_talk()

    def paintEvent(self, _event):
        now = time.monotonic()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        pulse = 0.5 + 0.5 * math.sin(self.phase)
        dancing = self.state == "idle" and (
            self.music_singing or now < self._random_dance_until
        )
        if dancing:
            bounce = 1.8 * (0.5 - 0.5 * math.cos(self.phase * 2))
            sway = 3.0 * math.sin(self.phase)
            p.translate(52, 52 + bounce)
            p.rotate(sway)
            p.translate(-52, -52)
        elif self.state in {"listening", "thinking", "speaking"}:
            p.translate(0, math.sin(self.phase * 1.8) * 1.2)
        glow = QRadialGradient(52, 51, 52)
        glow.setColorAt(0, QColor(224, 166, 75, 12 + int(pulse * 10)))
        glow.setColorAt(1, QColor(224, 166, 75, 0))
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(glow)
        p.drawEllipse(QRectF(2, 2, 100, 100))
        panel = QRectF(8, 10, 88, 72)
        p.setBrush(QColor("#303236"))
        p.setPen(QPen(QColor("#74777b") if self.drag_hover else QColor("#414347"), 1.8 if self.drag_hover else 1.0))
        p.drawRoundedRect(panel, 2.5, 2.5)
        if self.expression:
            mood = self.expression
        elif self.music_singing and self.state == "idle":
            mood = "singing"
        elif self.state == "idle" and self.device_mood in ("charging", "low_battery", "busy"):
            mood = self.device_mood
        else:
            mood = self.idle_expression if self.idle_expression != "neutral" else "neutral"
        eye_y = 43
        gaze_x = self.gaze_x * 0.25
        gaze_y = self.gaze_y * 0.18
        blinking = now < self._blink_until
        eye_color = QColor("#f0f0f0")
        left_eye_x = 36 + gaze_x
        right_eye_x = 68 + gaze_x
        if blinking or mood == "wink":
            p.setPen(QPen(eye_color, 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawLine(QPointF(left_eye_x - 2.7, eye_y + gaze_y), QPointF(left_eye_x + 2.7, eye_y + gaze_y))
            if mood != "wink":
                p.drawLine(QPointF(right_eye_x - 2.7, eye_y + gaze_y), QPointF(right_eye_x + 2.7, eye_y + gaze_y))
            else:
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(eye_color)
                p.drawEllipse(QRectF(right_eye_x - 2.6, eye_y + gaze_y - 2.6, 5.2, 5.2))
        elif mood in {"happy", "charging"}:
            p.setPen(QPen(eye_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawArc(QRectF(left_eye_x - 3.2, eye_y + gaze_y - 2.5, 6.4, 5.5), 200 * 16, 140 * 16)
            p.drawArc(QRectF(right_eye_x - 3.2, eye_y + gaze_y - 2.5, 6.4, 5.5), 200 * 16, 140 * 16)
        elif mood in {"sleepy", "low_battery"}:
            p.setPen(QPen(eye_color, 1.6, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawLine(QPointF(left_eye_x - 2.6, eye_y + gaze_y + 1), QPointF(left_eye_x + 2.6, eye_y + gaze_y + 1))
            p.drawLine(QPointF(right_eye_x - 2.6, eye_y + gaze_y + 1), QPointF(right_eye_x + 2.6, eye_y + gaze_y + 1))
        else:
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(eye_color)
            eye_radius = 2.9 if mood in {"excited", "surprised"} else 2.4
            p.drawEllipse(QRectF(left_eye_x - eye_radius, eye_y + gaze_y - eye_radius, eye_radius * 2, eye_radius * 2))
            p.drawEllipse(QRectF(right_eye_x - eye_radius, eye_y + gaze_y - eye_radius, eye_radius * 2, eye_radius * 2))

        mouth_color = QColor("#f0f0f0")
        mouth_center_x = 52
        mouth_center_y = 62
        mouth_width = 19
        if self.state == "speaking" or (self.music_singing and self.state == "idle"):
            mouth_height = 2.5 + 7 * (0.5 + 0.5 * math.sin(self.phase * (10 if self.music_singing else 7)))
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(mouth_color)
            p.drawRoundedRect(QRectF(mouth_center_x - mouth_width / 2, mouth_center_y - mouth_height / 2, mouth_width, mouth_height), 4, 4)
        elif self.state == "listening":
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(mouth_color)
            mouth_size = 3 + 2 * pulse
            p.drawEllipse(QRectF(mouth_center_x - mouth_size / 2, mouth_center_y - mouth_size / 2, mouth_size, mouth_size))
        elif now < self.eating_until:
            chew_height = 2.5 + 6 * (0.5 + 0.5 * math.sin(self.phase * 12))
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(mouth_color)
            p.drawRoundedRect(
                QRectF(mouth_center_x - 4.5, mouth_center_y - chew_height / 2, 9, chew_height),
                3,
                3,
            )
        elif self.state == "thinking":
            dash_offset = 2 * math.sin(self.phase * 2)
            p.setPen(QPen(mouth_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawLine(QPointF(mouth_center_x - 9 + dash_offset, mouth_center_y), QPointF(mouth_center_x + 9 + dash_offset, mouth_center_y))
        elif mood in {"happy", "charging"}:
            p.setPen(QPen(mouth_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawArc(QRectF(mouth_center_x - 9, mouth_center_y - 4, 18, 8), 200 * 16, 140 * 16)
        elif mood in {"sad", "concerned", "low_battery"}:
            p.setPen(QPen(mouth_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawArc(QRectF(mouth_center_x - 8, mouth_center_y - 1, 16, 8), 20 * 16, 140 * 16)
        elif mood in {"surprised", "excited"}:
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(mouth_color)
            p.drawEllipse(QRectF(mouth_center_x - 2, mouth_center_y - 3, 4, 6))
        elif mood in {"curious", "confused"}:
            p.setPen(QPen(mouth_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawLine(QPointF(mouth_center_x - 1, mouth_center_y + 1, ), QPointF(mouth_center_x + 7, mouth_center_y - 1))
        else:
            p.setPen(QPen(mouth_color, 1.5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            p.drawLine(QPointF(mouth_center_x - 9, mouth_center_y), QPointF(mouth_center_x + 9, mouth_center_y))

        ring_color = QColor("#ff303d") if self.audio_ready else QColor("#8fe8ff") if self.state == "listening" else QColor("#e0a64b")
        if self.device_mood == "charging" and not self.audio_ready:
            ring_color = QColor("#8fe8ff")
        glow_color = QColor(ring_color)
        glow_color.setAlpha(80 + int(pulse * 100))
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(glow_color)
        p.drawEllipse(QRectF(52 - 6 - pulse, 93 - 6 - pulse, 12 + pulse * 2, 12 + pulse * 2))
        p.setPen(QPen(ring_color, 1.3))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawEllipse(QRectF(48.5, 89.5, 7, 7))
        if self._transition_kind:
            progress = min(1.0, (time.monotonic() - self._transition_started) / self._transition_duration)
            strength = 1 - progress if self._transition_kind == "entrance" else progress
            glitch_tick = int(self.phase * 5)
            if glitch_tick % 4 != 0:
                for index in range(4):
                    if (glitch_tick + index * 3) % 5 < 2:
                        band_y = 13 + ((glitch_tick * 9 + index * 15) % 62)
                        band_x = int(math.sin(self.phase * 11 + index * 2.1) * 5 * strength)
                        color = QColor("#e0a64b")
                        color.setAlpha(int((55 + index * 10) * strength))
                        p.fillRect(QRectF(8 + band_x, band_y, 30 + index * 12, 1.5), color)
                        color.setAlpha(int(30 * strength))
                        p.fillRect(QRectF(64 - band_x, band_y + 2, 22, 1), color)
        if now < self._click_fx_until:
            remaining = (self._click_fx_until - now) / 0.72
            glow_alpha = int(150 * remaining)
            p.setPen(QPen(QColor(224, 166, 75, glow_alpha), 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            if self._click_fx_kind == "wave":
                wave_width = int((1 - remaining) * 20) + 4
                p.drawArc(QRectF(52 - wave_width, 52 - wave_width, wave_width * 2, wave_width * 2), 35 * 16, 265 * 16)
            elif self._click_fx_kind == "heart":
                p.setFont(QFont("Segoe UI Symbol", 13, QFont.Weight.Bold))
                p.drawText(QRectF(37, 4 - int((1 - remaining) * 5), 30, 23), Qt.AlignmentFlag.AlignCenter, "♥")
            else:
                for x, y, direction in ((16, 36, 1), (87, 39, -1), (30, 82, 1)):
                    p.drawLine(x, y - 4, x, y + 4)
                    p.drawLine(x - 4, y, x + 4, y)
                    p.drawLine(x - 3 * direction, y - 3, x + 3 * direction, y + 3)

    def clear_click_expression(self):
        if time.monotonic() >= self.expression_until:
            self.expression = None
            self.update()

    def mousePressEvent(self, event):
        if self._transition_kind or self._quit_requested:
            event.ignore()
            return
        if event.button() == Qt.MouseButton.LeftButton and self._walk_origin is not None:
            self.interrupt_idle_pose("surprised")
        if event.button() == Qt.MouseButton.RightButton:
            self.show_companion_tools(event.globalPosition().toPoint())
            event.accept()
            return
        if event.button() == Qt.MouseButton.LeftButton:
            self._press_global = event.globalPosition().toPoint()
            self._drag_offset = self._press_global - self.frameGeometry().topLeft()
            self._last_drag_global = self._press_global
            self._dragging = False
            event.accept()

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls() and any(url.isLocalFile() for url in event.mimeData().urls()):
            self.drag_hover = True
            self.update()
            event.acceptProposedAction()
            return
        event.ignore()

    def dragMoveEvent(self, event):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            return
        event.ignore()

    def dragLeaveEvent(self, event):
        self.drag_hover = False
        self.update()
        event.accept()

    def dropEvent(self, event: QDropEvent):
        self.drag_hover = False
        paths = [url.toLocalFile() for url in event.mimeData().urls() if url.isLocalFile()]
        if paths:
            self.receive_dropped_files(paths)
            event.acceptProposedAction()
        else:
            event.ignore()
        self.update()

    def receive_dropped_files(self, paths):
        if self.chat.project_root is not None:
            added, rejected = self.chat.add_project_paths(paths)
            if added:
                self.eating_until = time.monotonic() + 1.3
                self.react("excited", 1.2)
                self.setToolTip(f"Ki added {added} file(s) to the selected project")
            elif rejected:
                QMessageBox.information(
                    self.chat,
                    "Files not added to project",
                    "Drop supported files from inside the selected project folder.",
                )
            return
        added = self.chat.add_attachment_paths(paths)
        if added:
            self.eating_until = time.monotonic() + 1.3
            self.react("excited", 1.2)
            self.setToolTip(f"Ki added {added} file(s) to chat attachments")
        else:
            QMessageBox.information(
                self.chat,
                "Unsupported files",
                "Drop TXT, Markdown, JSON, CSV, DB, SQLite, or SQLite3 files onto Ki.",
            )

    def show_companion_tools(self, position):
        menu = QMenu(self)
        menu.setStyleSheet(
            "QMenu { background:#2b2b2b; color:#f5f6f8; border:1px solid #484848; padding:5px; } "
            "QMenu::item { padding:8px 22px; border-radius:5px; } "
            "QMenu::item:selected { background:#454545; }"
        )
        menu.addAction("Open chat", self.open_chat)
        menu.addAction("Open control panel", self.open_dashboard)
        menu.addSeparator()
        git_menu = menu.addMenu("Git · read-only")
        git_menu.addAction("Repository status", lambda: self.show_git_report("status"))
        git_menu.addAction("Changed files and diff", lambda: self.show_git_report("diff"))
        git_menu.addAction("Recent commits", lambda: self.show_git_report("log"))
        menu.exec(position)

    def show_git_report(self, report_type):
        folder = self.chat.project_root or APP_DIR
        dialog = GitToolsDialog(folder, report_type, self)
        dialog.exec()

    def mouseMoveEvent(self, event):
        local = event.position()
        self.gaze_x = max(-3.0, min(3.0, (local.x() - 52) * 0.12))
        self.gaze_y = max(-2.0, min(2.0, (local.y() - 50) * 0.08))
        if self._drag_offset is not None and event.buttons() & Qt.MouseButton.LeftButton:
            current = event.globalPosition().toPoint()
            if not self._dragging:
                distance = (current - self._press_global).manhattanLength()
                if distance < QApplication.startDragDistance():
                    self.update()
                    event.accept()
                    return
                self._walk_return_start = None
                self._walk_return_target = None
                self._walk_return_started = 0.0
                self._walk_return_duration = 0.0
                self._dragging = True
                self.setCursor(Qt.CursorShape.ClosedHandCursor)
            if self._last_drag_global is not None:
                delta = current - self._last_drag_global
                self.gaze_x = max(-3.0, min(3.0, self.gaze_x + delta.x() * 0.25))
                self.gaze_y = max(-2.0, min(2.0, self.gaze_y + delta.y() * 0.20))
            self._last_drag_global = current
            self.move(self.clamp_to_safe_area(current - self._drag_offset))
            self.raise_()
            self.update()
            event.accept()
            return
        self.update()
        super().mouseMoveEvent(event)

    def mouseDoubleClickEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self._press_global = None
            self._drag_offset = None
            self._dragging = False
            self.setCursor(Qt.CursorShape.OpenHandCursor)
            self.handle_face_double_click()
            event.accept()

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and self._drag_offset is not None:
            distance = (event.globalPosition().toPoint() - self._press_global).manhattanLength()
            was_dragging = self._dragging
            self._drag_offset = None
            self._press_global = None
            self._last_drag_global = None
            self._dragging = False
            self.setCursor(Qt.CursorShape.OpenHandCursor)
            if not was_dragging and distance < QApplication.startDragDistance():
                self.react_to_tap()
            event.accept()
