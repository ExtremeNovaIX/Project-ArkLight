import QtQuick

QtObject {
    readonly property color paper: "#F2EBDD"
    readonly property color panel: "#EDE5D5"
    readonly property color panelAlt: "#E7E0D2"
    readonly property color shell: "#F4EEE2"
    readonly property color inputPaper: "#F8F3E9"
    readonly property color paperLight: "#FFFDF8"
    readonly property color card: "#FFFFFF"
    readonly property color ink: "#202322"
    readonly property color black: "#090B0C"
    readonly property color blackPanel: "#11191B"
    readonly property color blackPanelAlt: "#172022"
    readonly property color blackLine: "#293234"
    readonly property color orange: "#F26422"
    readonly property color orangeDark: "#A74319"
    readonly property color teal: "#2A7C79"
    readonly property color tealDark: "#235E5C"
    readonly property color chatGrid: "#7C705E"
    readonly property color statusRed: "#C83227"
    readonly property color statusRedLight: "#E14A34"
    readonly property color statusYellow: "#E7B93E"
    readonly property color statusYellowDark: "#C99927"
    readonly property color statusTeal: "#66B9B3"
    readonly property color statusTealDark: "#318C88"
    readonly property string sansFont: "Microsoft YaHei UI"
    readonly property string displayFont: "Bahnschrift SemiCondensed"
    readonly property string monoFont: "Cascadia Mono"
    readonly property int radiusBubble: 4
    readonly property int radiusControl: 7
    readonly property int radiusFrame: 2
    readonly property int outerMargin: 0
    readonly property int fastMotion: 140
    readonly property int baseMotion: 210
    readonly property int slowMotion: 520

    function inkAlpha(alpha) {
        return Qt.rgba(32 / 255, 35 / 255, 34 / 255, alpha)
    }

    function whiteAlpha(alpha) {
        return Qt.rgba(1, 1, 1, alpha)
    }

    function blackAlpha(alpha) {
        return Qt.rgba(9 / 255, 11 / 255, 12 / 255, alpha)
    }

    function orangeAlpha(alpha) {
        return Qt.rgba(242 / 255, 100 / 255, 34 / 255, alpha)
    }

    function tealAlpha(alpha) {
        return Qt.rgba(42 / 255, 124 / 255, 121 / 255, alpha)
    }

    function chatGridAlpha(alpha) {
        return Qt.rgba(124 / 255, 112 / 255, 94 / 255, alpha)
    }

    function orangeDarkAlpha(alpha) {
        return Qt.rgba(167 / 255, 67 / 255, 25 / 255, alpha)
    }

    function tealDarkAlpha(alpha) {
        return Qt.rgba(35 / 255, 94 / 255, 92 / 255, alpha)
    }
}
