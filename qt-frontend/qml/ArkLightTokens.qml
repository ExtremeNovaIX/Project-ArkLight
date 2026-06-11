import QtQuick

QtObject {
    readonly property color paper: "#F6F0DC"
    readonly property color panel: "#F1E9D4"
    readonly property color panelAlt: "#EBE7DC"
    readonly property color shell: "#F2EFE6"
    readonly property color inputPaper: "#F8F5EC"
    readonly property color card: "#FFFFFF"
    readonly property color ink: "#1A1A1A"
    readonly property color orange: "#E85D04"
    readonly property color teal: "#4D908E"
    readonly property color chatGrid: "#786848"
    readonly property color statusRed: "#8F2A1F"
    readonly property color statusRedLight: "#A63B28"
    readonly property color statusYellow: "#F0B649"
    readonly property color statusYellowDark: "#DEA03B"
    readonly property color statusTeal: "#67B6B0"
    readonly property color statusTealDark: "#4C8E8B"
    readonly property string sansFont: "Inter"
    readonly property string monoFont: "JetBrains Mono"

    function inkAlpha(alpha) {
        return Qt.rgba(26 / 255, 26 / 255, 26 / 255, alpha)
    }

    function whiteAlpha(alpha) {
        return Qt.rgba(1, 1, 1, alpha)
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
}
