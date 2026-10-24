from __future__ import annotations

from .shared import *
from .chat import ChatWindow
from .dashboard import *
from .panel import CompanionWindow
from .robot import RobotWidget
from .startup import KiBootWindow, KiStartupController

_KI_INSTANCE_MUTEX = None
_KI_TOGGLE_EVENT = None
_KI_MUTEX_NAME = "Local\\KiDesktopAssistant"
_KI_TOGGLE_EVENT_NAME = "Local\\KiDesktopAssistantToggle"

class KiShortcutEventFilter(QObject):
    """Keep Ki's polled global talk chords from activating focused UI controls."""

    def eventFilter(self, watched, event):
        if event.type() in (QEvent.Type.KeyPress, QEvent.Type.KeyRelease):
            modifiers = event.modifiers()
            chord = modifiers & Qt.KeyboardModifier.ControlModifier and modifiers & Qt.KeyboardModifier.AltModifier
            if chord and event.key() in (Qt.Key.Key_Space, Qt.Key.Key_Backspace):
                return True
        return super().eventFilter(watched, event)

def main():
    global _KI_INSTANCE_MUTEX, _KI_TOGGLE_EVENT
    kernel32 = None
    if sys.platform == "win32":
        kernel32 = ctypes.windll.kernel32
        kernel32.CreateEventW.argtypes = (ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR)
        kernel32.CreateEventW.restype = ctypes.c_void_p
        kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR)
        kernel32.CreateMutexW.restype = ctypes.c_void_p
        kernel32.OpenEventW.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR)
        kernel32.OpenEventW.restype = ctypes.c_void_p
        kernel32.SetEvent.argtypes = (ctypes.c_void_p,)
        kernel32.SetEvent.restype = wintypes.BOOL
        kernel32.WaitForSingleObject.argtypes = (ctypes.c_void_p, wintypes.DWORD)
        kernel32.WaitForSingleObject.restype = wintypes.DWORD
        kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
        kernel32.CloseHandle.restype = wintypes.BOOL
        _KI_TOGGLE_EVENT = kernel32.CreateEventW(None, False, False, _KI_TOGGLE_EVENT_NAME)
        _KI_INSTANCE_MUTEX = kernel32.CreateMutexW(None, False, _KI_MUTEX_NAME)
        if kernel32.GetLastError() == 183:
            # A second launch from the desktop shortcut wakes the existing Ki instance.
            event_handle = kernel32.OpenEventW(0x0002, False, _KI_TOGGLE_EVENT_NAME)
            if event_handle:
                kernel32.SetEvent(event_handle)
                kernel32.CloseHandle(event_handle)
            return
    app = QApplication(sys.argv)
    app.setApplicationName("Ki&Tot")
    app.setQuitOnLastWindowClosed(False)
    if kernel32 is not None and _KI_TOGGLE_EVENT:
        toggle_timer = QTimer(app)
        def check_toggle_request():
            if kernel32.WaitForSingleObject(_KI_TOGGLE_EVENT, 0) == 0:
                robot.handle_launch_hotkey()
        toggle_timer.timeout.connect(check_toggle_request)
        toggle_timer.start(100)
    shortcut_filter = KiShortcutEventFilter(app)
    app.installEventFilter(shortcut_filter)
    robot = RobotWidget()
    splash = KiBootWindow()
    startup = KiStartupController(robot, splash, app)
    app.aboutToQuit.connect(robot.agent_monitor.close_server)
    app.aboutToQuit.connect(robot.phone_bridge.stop)
    splash.show()
    startup.start()
    exit_code = app.exec()
    if kernel32 is not None:
        if _KI_TOGGLE_EVENT:
            kernel32.CloseHandle(_KI_TOGGLE_EVENT)
            _KI_TOGGLE_EVENT = None
        if _KI_INSTANCE_MUTEX:
            kernel32.CloseHandle(_KI_INSTANCE_MUTEX)
            _KI_INSTANCE_MUTEX = None
    sys.exit(exit_code)
