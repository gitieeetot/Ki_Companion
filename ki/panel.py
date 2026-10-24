from __future__ import annotations

from .shared import *
from .dashboard import DraggableHeader

class CompanionWindow(QDialog):
    """Single topbar window that hosts Ki chat and desktop controls."""

    def __init__(self, chat, dashboard, parent=None):
        super().__init__(parent)
        self.chat = chat
        self.dashboard = dashboard
        self.setWindowTitle("Ki")
        self.setWindowFlags(
            Qt.WindowType.Window
            | Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
        )
        self.setMinimumSize(420, 460)
        self.resize(760, 700)
        self.setStyleSheet("""
            QDialog#companionWindow { background:#212121; color:#f5f6f8; border:1px solid #3b3b3b; border-radius:15px; }
            QFrame#companionTopbar { background:#2b2b2b; border:1px solid #414141; border-radius:12px; }
            QLabel#brandMark { background:#e7e7e7; color:#202020; font-size:11px; font-weight:800; border-radius:8px; min-width:27px; max-width:27px; min-height:27px; max-height:27px; qproperty-alignment: AlignCenter; }
            QLabel#brandTitle { color:#ffffff; font-size:14px; font-weight:700; }
            QLabel#brandStatus { color:#aeb3bd; font-size:9px; letter-spacing:0.5px; }
            QPushButton#panelNavigation, QPushButton#panelNavigationSelected { min-height:34px; padding:0 13px; border-radius:9px; }
            QPushButton#panelNavigation { background:#2b2b2b; color:#b9bdc6; border:1px solid #3c3c3c; }
            QPushButton#panelNavigation:hover { background:#383838; color:#ffffff; }
            QPushButton#panelNavigationSelected { background:#454545; color:#ffffff; border:1px solid #595959; }
            QPushButton#panelNavigation:checked, QPushButton#panelNavigationSelected:checked { background:#454545; color:#ffffff; border:1px solid #595959; }
            QPushButton#topbarAction { min-width:34px; max-width:34px; min-height:34px; max-height:34px; padding:0; border-radius:9px; background:#383838; color:#f2f3f6; border:1px solid #484848; }
            QPushButton#topbarAction:hover { background:#484848; }
            QPushButton#windowControl { min-width:32px; max-width:32px; min-height:32px; max-height:32px; padding:0; border-radius:9px; background:#303030; color:#dfe2e8; border:1px solid #424242; font-size:16px; }
            QPushButton#windowControl:hover { background:#484848; }
            QStackedWidget#companionPages { background:#212121; border:0; }
        """)
        self.setObjectName("companionWindow")

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)
        root.setSpacing(10)

        topbar = DraggableHeader()
        topbar.setObjectName("companionTopbar")
        bar = QHBoxLayout(topbar)
        bar.setContentsMargins(10, 7, 8, 7)
        bar.setSpacing(6)

        brand_mark = QLabel("K")
        brand_mark.setObjectName("brandMark")
        bar.addWidget(brand_mark)
        brand_col = QVBoxLayout()
        brand_col.setSpacing(1)
        brand_title = QLabel("Ki")
        brand_title.setObjectName("brandTitle")
        brand_status = QLabel("LOCAL COMPANION")
        self.brand_status = brand_status
        brand_status.setObjectName("brandStatus")
        brand_col.addWidget(brand_title)
        brand_col.addWidget(brand_status)
        bar.addLayout(brand_col)
        bar.addSpacing(8)

        self.chat_button = QPushButton()
        self.chat_button.setIcon(line_icon("chat"))
        self.chat_button.setIconSize(QSize(19, 19))
        self.chat_button.setFixedSize(36, 34)
        self.chat_button.setCheckable(True)
        self.chat_button.setToolTip("Chat")
        self.chat_button.setAccessibleName("Chat")
        self.chat_button.clicked.connect(lambda: self.show_page("chat"))
        self.controls_button = QPushButton()
        self.controls_button.setIcon(line_icon("controls"))
        self.controls_button.setIconSize(QSize(19, 19))
        self.controls_button.setFixedSize(36, 34)
        self.controls_button.setCheckable(True)
        self.controls_button.setToolTip("Control panel")
        self.controls_button.setAccessibleName("Control panel")
        self.controls_button.clicked.connect(lambda: self.show_page("controls"))
        bar.addWidget(self.chat_button)
        bar.addWidget(self.controls_button)
        bar.addStretch()
        self.audio_state_label = self.dashboard.audio_state_label
        bar.addWidget(self.audio_state_label)

        self.summary_button = QPushButton()
        self.summary_button.setObjectName("topbarAction")
        self.summary_button.setIcon(line_icon("activity"))
        self.summary_button.setIconSize(QSize(19, 19))
        self.summary_button.setToolTip("View activity log and summary")
        self.summary_button.setAccessibleName("Activity log and summary")
        self.summary_button.clicked.connect(self.dashboard.open_ki_summary)
        bar.addWidget(self.summary_button)
        self.add_app_button = QPushButton()
        self.add_app_button.setObjectName("topbarAction")
        self.add_app_button.setIcon(line_icon("add_app"))
        self.add_app_button.setIconSize(QSize(19, 19))
        self.add_app_button.setToolTip("Add an app shortcut to the control panel")
        self.add_app_button.setAccessibleName("Add app")
        self.add_app_button.clicked.connect(self.dashboard.add_app)
        bar.addWidget(self.add_app_button)
        self.settings_button = QPushButton()
        self.settings_button.setIcon(line_icon("settings"))
        self.settings_button.setIconSize(QSize(19, 19))
        self.settings_button.setAccessibleName("Voice and sound settings")
        self.settings_button.setObjectName("windowControl")
        self.settings_button.setToolTip("Voice and sound settings")
        self.settings_button.clicked.connect(self.dashboard.open_audio_settings)
        bar.addWidget(self.settings_button)
        minimize_button = QPushButton()
        minimize_button.setIcon(line_icon("minimize"))
        minimize_button.setIconSize(QSize(18, 18))
        minimize_button.setAccessibleName("Minimize Ki's panel")
        minimize_button.setObjectName("windowControl")
        minimize_button.setToolTip("Minimize Ki's panel")
        minimize_button.clicked.connect(self.showMinimized)
        bar.addWidget(minimize_button)
        close_button = QPushButton()
        close_button.setIcon(line_icon("close"))
        close_button.setIconSize(QSize(17, 17))
        close_button.setAccessibleName("Hide Ki's panel")
        close_button.setObjectName("windowControl")
        close_button.setToolTip("Hide Ki's panel")
        close_button.clicked.connect(self.hide)
        bar.addWidget(close_button)
        root.addWidget(topbar)

        self.pages = QStackedWidget()
        self.pages.setObjectName("companionPages")
        self.pages.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.chat.window_header.hide()
        self.chat.navigation_bar.hide()
        self.dashboard.window_header.hide()
        self.dashboard.navigation_bar.hide()
        self.dashboard.resize_grip.hide()
        for page in (self.chat, self.dashboard):
            page.setParent(self.pages, Qt.WindowType.Widget)
            page.setStyleSheet(page.styleSheet() + "\nQDialog { background:#212121; color:#f5f6f8; border:0; border-radius:0; }")
            self.pages.addWidget(page)
        root.addWidget(self.pages, 1)
        resize_row = QHBoxLayout()
        resize_row.setContentsMargins(0, 0, 2, 0)
        resize_row.addStretch()
        self.resize_grip = QSizeGrip(self)
        resize_row.addWidget(self.resize_grip)
        root.addLayout(resize_row)
        self.pages.currentChanged.connect(self.update_navigation)
        self.update_navigation()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if not hasattr(self, "audio_state_label"):
            return
        compact = self.width() < 620
        self.audio_state_label.setVisible(not compact)
        self.brand_status.setVisible(self.width() >= 540)

    def show_page(self, page):
        page_index = 0 if page == "chat" else 1
        self.pages.setCurrentIndex(page_index)
        self.showNormal()
        self.show()
        self.raise_()
        self.activateWindow()
        if page_index == 0:
            self.chat.entry.setFocus()

    def update_navigation(self, _index=None):
        chat_selected = self.pages.currentWidget() is self.chat
        self.chat_button.setChecked(chat_selected)
        self.controls_button.setChecked(not chat_selected)
        self.chat_button.setObjectName("panelNavigationSelected" if chat_selected else "panelNavigation")
        self.controls_button.setObjectName("panelNavigation" if chat_selected else "panelNavigationSelected")
        for button in (self.chat_button, self.controls_button):
            button.style().unpolish(button)
            button.style().polish(button)
        self.summary_button.setVisible(not chat_selected)
        self.add_app_button.setVisible(not chat_selected)

    def closeEvent(self, event):
        event.ignore()
        self.hide()
