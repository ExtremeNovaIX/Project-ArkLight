import QtQuick

Item {
    id: root
    property var host
    property var tokens
    property string kind: "general"
    property bool active: false
    property bool hovered: false
    property bool focused: false
    property real glowStrength: active ? 1 : (focused ? 0.76 : (hovered ? 0.44 : 0))

    Behavior on glowStrength {
        NumberAnimation {
            duration: root.tokens ? root.tokens.fastMotion : 140
            easing.type: Easing.OutCubic
        }
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
            const radius = Math.min(width, height) * 0.28
            const bezelRadius = radius * 1.26
            const glow = root.glowStrength
            const orange = root.tokens ? root.tokens.orange : "#F26422"

            ctx.translate(cx, cy)
            ctx.lineCap = "round"
            ctx.lineJoin = "round"

            ctx.fillStyle = root.tokens ? root.tokens.blackPanel : "#11191B"
            ctx.globalAlpha = 0.96
            ctx.beginPath()
            ctx.arc(0, 0, bezelRadius, 0, Math.PI * 2)
            ctx.fill()

            ctx.strokeStyle = "rgba(255,253,248,0.16)"
            ctx.lineWidth = Math.max(1, width * 0.018)
            ctx.beginPath()
            ctx.arc(0, 0, bezelRadius - ctx.lineWidth, Math.PI * 0.10, Math.PI * 1.92)
            ctx.stroke()

            ctx.strokeStyle = "rgba(242,100,34," + (0.10 + glow * 0.34) + ")"
            ctx.lineWidth = Math.max(1, width * 0.024)
            ctx.shadowColor = "rgba(242,100,34," + (glow * 0.18) + ")"
            ctx.shadowBlur = radius * (0.04 + glow * 0.16)
            ctx.beginPath()
            ctx.arc(0, 0, bezelRadius + width * 0.012, Math.PI * 1.58, Math.PI * (1.76 + glow * 0.12))
            ctx.stroke()

            ctx.shadowColor = "rgba(255,247,226," + (glow * 0.14) + ")"
            ctx.shadowBlur = radius * (0.03 + glow * 0.12)
            ctx.strokeStyle = "rgba(255,253,248," + (0.70 + glow * 0.18) + ")"
            ctx.fillStyle = "rgba(255,253,248," + (0.72 + glow * 0.24) + ")"
            ctx.lineWidth = Math.max(1.2, width * 0.026)

            if (root.kind === "general") {
                ctx.beginPath()
                ctx.arc(-radius * 0.18, 0, radius * 0.34, Math.PI * 0.18, Math.PI * 1.84)
                ctx.stroke()

                ctx.beginPath()
                ctx.moveTo(-radius * 0.72, 0)
                ctx.lineTo(radius * 0.52, 0)
                ctx.stroke()

                ctx.beginPath()
                ctx.arc(radius * 0.46, -radius * 0.10, radius * 0.11, 0, Math.PI * 2)
                ctx.fill()
            } else if (root.kind === "services") {
                ctx.beginPath()
                ctx.arc(-radius * 0.23, radius * 0.05, radius * 0.37, 0, Math.PI * 2)
                ctx.stroke()

                ctx.beginPath()
                ctx.arc(radius * 0.24, -radius * 0.06, radius * 0.37, 0, Math.PI * 2)
                ctx.stroke()

                ctx.beginPath()
                ctx.moveTo(-radius * 0.05, -radius * 0.70)
                ctx.lineTo(radius * 0.46, -radius * 0.70)
                ctx.stroke()

                ctx.beginPath()
                ctx.arc(radius * 0.60, radius * 0.48, radius * 0.08, 0, Math.PI * 2)
                ctx.fill()
            } else if (root.kind === "game") {
                ctx.save()
                ctx.rotate(-Math.PI * 0.20)
                ctx.beginPath()
                ctx.arc(-radius * 0.12, 0, radius * 0.52, Math.PI * 0.20, Math.PI * 1.82)
                ctx.stroke()

                ctx.beginPath()
                ctx.arc(radius * 0.28, 0, radius * 0.32, Math.PI * 1.16, Math.PI * 2.76)
                ctx.stroke()
                ctx.restore()

                ctx.beginPath()
                ctx.moveTo(-radius * 0.62, radius * 0.52)
                ctx.lineTo(radius * 0.52, -radius * 0.58)
                ctx.stroke()

                ctx.beginPath()
                ctx.arc(-radius * 0.70, radius * 0.62, radius * 0.10, 0, Math.PI * 2)
                ctx.fill()
            } else {
                const bars = [-0.54, -0.27, 0, 0.27, 0.54]
                for (let index = 0; index < bars.length; index += 1) {
                    const x = bars[index] * radius
                    const heightFactor = index === 2 ? 0.86 : (index === 1 || index === 3 ? 0.62 : 0.36)
                    ctx.beginPath()
                    ctx.moveTo(x, -radius * heightFactor)
                    ctx.lineTo(x, radius * heightFactor)
                    ctx.stroke()
                }

                ctx.beginPath()
                ctx.arc(0, 0, radius * 0.72, Math.PI * 0.10, Math.PI * 0.90)
                ctx.stroke()
            }

            ctx.shadowBlur = 0
            ctx.globalAlpha = 1
            ctx.fillStyle = orange
            ctx.beginPath()
            ctx.arc(radius * 0.82, radius * 0.58, Math.max(1.5, radius * 0.095), 0, Math.PI * 2)
            ctx.fill()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onGlowStrengthChanged: canvas.requestPaint()
    onKindChanged: canvas.requestPaint()
}
