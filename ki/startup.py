from __future__ import annotations

from .shared import *
from .services import OllamaStartupWorker

class BootGlitchLogo(QWidget):
    """Small, lightweight glitch animation for Ki's startup screen."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.tick = 0
        self.setMinimumHeight(100)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.animate)
        self._timer.start(85)

    def animate(self):
        self.tick += 1
        self.update()

    def paintEvent(self, _event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setFont(QFont("Segoe UI", 48, QFont.Weight.Bold))
        rect = self.rect()
        if self.tick % 11 in (0, 1):
            band_y = 23 + (self.tick * 7) % max(1, rect.height() - 32)
            painter.save()
            painter.setClipRect(0, band_y, rect.width(), 5)
            painter.setPen(QColor(91, 226, 255, 180))
            painter.drawText(rect.translated(4, 0), Qt.AlignmentFlag.AlignCenter, "KI")
            painter.restore()
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QColor(91, 226, 255, 125))
            painter.drawRoundedRect(26, band_y + 1, 34, 2, 1, 1)
            painter.drawRoundedRect(rect.width() - 57, band_y + 2, 29, 2, 1, 1)
        painter.setPen(QColor("#f4f5f7"))
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter, "KI")

class KiBootWindow(QDialog):
    retryRequested = Signal()
    continueOfflineRequested = Signal()

    def __init__(self):
        super().__init__()
        self.setWindowTitle("Ki · Starting")
        self.setWindowFlags(Qt.WindowType.Dialog | Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.WindowTitleHint | Qt.WindowType.WindowCloseButtonHint)
        self.setFixedSize(390, 290)
        self.setStyleSheet("""
            QDialog { background:#202124; color:#f5f6f8; }
            QLabel { color:#f5f6f8; }
            QPushButton { background:#34373d; color:#f5f6f8; border:1px solid #484c54; border-radius:12px; padding:8px 14px; min-height:34px; }
            QPushButton:hover { background:#42464e; }
            QPushButton#continueButton { background:#f1f2f4; color:#202124; border:0; font-weight:700; }
            QProgressBar { background:#303238; border:0; border-radius:3px; min-height:5px; max-height:5px; }
            QProgressBar::chunk { background:#62d7f2; border-radius:3px; }
        """)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 16, 28, 22)
        layout.setSpacing(8)
        self.logo = BootGlitchLogo()
        layout.addWidget(self.logo)
        self.title = QLabel("STARTING KI")
        self.title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.title.setStyleSheet("font-size:11px; font-weight:700; letter-spacing:2px; color:#aeb6c2;")
        layout.addWidget(self.title)
        self.status = QLabel("Connecting to Ollama on this laptop…")
        self.status.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status.setWordWrap(True)
        self.status.setStyleSheet("font-size:13px; color:#e5e7eb; padding:2px 0;")
        layout.addWidget(self.status)
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setTextVisible(False)
        layout.addWidget(self.progress)
        self.actions = QHBoxLayout()
        self.actions.addStretch(1)
        self.retry_button = QPushButton("Try again")
        self.retry_button.clicked.connect(self.retryRequested.emit)
        self.continue_button = QPushButton("Open Ki offline")
        self.continue_button.setObjectName("continueButton")
        self.continue_button.clicked.connect(self.continueOfflineRequested.emit)
        self.retry_button.hide()
        self.continue_button.hide()
        self.actions.addWidget(self.retry_button)
        self.actions.addWidget(self.continue_button)
        layout.addLayout(self.actions)
        self._closing_allowed = False
        screen = QApplication.primaryScreen()
        if screen is not None:
            self.move(screen.availableGeometry().center() - self.rect().center())

    def show_starting(self):
        self._closing_allowed = False
        self.title.setText("STARTING KI")
        self.status.setText("Connecting to Ollama on this laptop…")
        self.progress.show()
        self.retry_button.hide()
        self.continue_button.hide()

    def show_start_error(self, message):
        self._closing_allowed = True
        self.title.setText("OLLAMA NOT READY")
        self.status.setText(message)
        self.progress.hide()
        self.retry_button.show()
        self.continue_button.show()

    def closeEvent(self, event):
        if self._closing_allowed:
            QApplication.instance().quit()
            super().closeEvent(event)
        else:
            event.ignore()

class KiStartupController(QObject):
    def __init__(self, robot, splash, parent=None):
        super().__init__(parent)
        self.robot = robot
        self.splash = splash
        self.worker = None
        self.splash.retryRequested.connect(self.start)
        self.splash.continueOfflineRequested.connect(self.open_ki)

    def start(self):
        if self.worker is not None and self.worker.isRunning():
            return
        self.splash.show_starting()
        self.worker = OllamaStartupWorker(self)
        self.worker.ready.connect(self.startup_finished)
        self.worker.finished.connect(self.worker.deleteLater)
        self.worker.start()

    def startup_finished(self, ready, message):
        self.worker = None
        if ready:
            self.splash.status.setText("Ollama is ready · Opening Ki…")
            QTimer.singleShot(450, self.open_ki)
        else:
            self.splash.show_start_error(message)

    def open_ki(self):
        self.splash.hide()
        self.robot.play_entrance_glitch()
