import QtQuick

QtObject {
    readonly property color paper: "#F6F0DC"
    readonly property color panel: "#F1E9D4"
    readonly property color panelAlt: "#EBE7DC"
    readonly property color shell: "#F2EFE6"
    readonly property color inputPaper: "#F8F5EC"
    readonly property color paperLight: "#FFFDF8"
    readonly property color card: "#FFFFFF"
    readonly property color ink: "#1A1A1A"
    readonly property color black: "#0A0A0A"
    readonly property color blackPanel: "#12110E"
    readonly property color blackPanelAlt: "#191712"
    readonly property color blackLine: "#2B281F"
    readonly property color orange: "#E85D04"
    readonly property color orangeDark: "#9A3412"
    readonly property color teal: "#4D908E"
    readonly property color tealDark: "#25615F"
    readonly property color chatGrid: "#786848"
    readonly property color statusRed: "#8F2A1F"
    readonly property color statusRedLight: "#A63B28"
    readonly property color statusYellow: "#F0B649"
    readonly property color statusYellowDark: "#DEA03B"
    readonly property color statusTeal: "#67B6B0"
    readonly property color statusTealDark: "#4C8E8B"
    readonly property string sansFont: "Segoe UI"
    readonly property string displayFont: "Segoe UI"
    readonly property string monoFont: "JetBrains Mono"
    readonly property int radiusBubble: 12
    readonly property int radiusControl: 8
    readonly property int radiusFrame: 4
    readonly property int outerMargin: 14
    readonly property int fastMotion: 150
    readonly property int baseMotion: 220
    readonly property int slowMotion: 560

    function inkAlpha(alpha) {
        return Qt.rgba(26 / 255, 26 / 255, 26 / 255, alpha)
    }

    function whiteAlpha(alpha) {
        return Qt.rgba(1, 1, 1, alpha)
    }

    function blackAlpha(alpha) {
        return Qt.rgba(10 / 255, 10 / 255, 10 / 255, alpha)
    }

    function orangeAlpha(alpha) {
        return Qt.rgba(232 / 255, 93 / 255, 4 / 255, alpha)
    }

    function tealAlpha(alpha) {
        return Qt.rgba(77 / 255, 144 / 255, 142 / 255, alpha)
    }

    function chatGridAlpha(alpha) {
        return Qt.rgba(120 / 255, 104 / 255, 72 / 255, alpha)
    }

    function orangeDarkAlpha(alpha) {
        return Qt.rgba(154 / 255, 52 / 255, 18 / 255, alpha)
    }

    function tealDarkAlpha(alpha) {
        return Qt.rgba(37 / 255, 97 / 255, 95 / 255, alpha)
    }
}
