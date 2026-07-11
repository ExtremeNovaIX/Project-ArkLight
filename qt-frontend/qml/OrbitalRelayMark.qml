import QtQuick

Item {
    id: root
    property var host
    property var tokens
    property real pulse: 1

    function sp(value) {
        return host ? host.sp(value) : value
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        antialiasing: true

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)

            const cx = width / 2
            const cy = height / 2
            const radius = Math.min(width, height) * 0.46
            ctx.translate(cx, cy)

            ctx.fillStyle = root.tokens ? root.tokens.ink : "#202322"
            ctx.beginPath()
            ctx.arc(0, 0, radius, 0, Math.PI * 2)
            ctx.fill()

            ctx.strokeStyle = root.tokens ? root.tokens.paperLight : "#FFFDF8"
            ctx.lineWidth = Math.max(2, radius * 0.075)
            ctx.beginPath()
            ctx.arc(0, 0, radius * 0.73, 0, Math.PI * 2)
            ctx.stroke()

            ctx.lineWidth = Math.max(1.5, radius * 0.055)
            ctx.beginPath()
            ctx.arc(0, 0, radius * 0.48, 0, Math.PI * 2)
            ctx.stroke()

            const slotWidth = radius * 0.72
            const slotHeight = radius * 0.23
            ctx.fillStyle = root.tokens ? root.tokens.paperLight : "#FFFDF8"
            ctx.fillRect(-slotWidth / 2, -slotHeight / 2, slotWidth, slotHeight)

            ctx.fillStyle = root.tokens ? root.tokens.ink : "#202322"
            ctx.fillRect(-slotWidth * 0.31, -slotHeight * 0.13, slotWidth * 0.62, slotHeight * 0.26)

            ctx.strokeStyle = root.tokens ? root.tokens.orange : "#F26422"
            ctx.lineWidth = Math.max(2, radius * 0.09)
            ctx.beginPath()
            ctx.arc(0, 0, radius * 0.88, Math.PI * 0.30, Math.PI * 0.43)
            ctx.stroke()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }
}
