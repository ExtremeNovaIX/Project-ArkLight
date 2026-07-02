import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ComboBox {
    id: combo
    property var host
    property var tokens
    Layout.fillWidth: true
    focusPolicy: Qt.NoFocus
    font.family: tokens.sansFont
    font.pixelSize: host.sp(14)
    leftPadding: host.sp(12)
    rightPadding: host.sp(30)
    topPadding: host.sp(10)
    bottomPadding: host.sp(10)
    implicitHeight: host.sp(46)
    contentItem: Text {
        text: combo.displayText
        color: tokens.ink
        font: combo.font
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    indicator: Canvas {
        id: comboChevron
        x: combo.width - width - host.sp(13)
        y: (combo.height - height) / 2
        width: host.sp(12)
        height: host.sp(8)
        rotation: combo.popup.visible ? 180 : 0

        Behavior on rotation { NumberAnimation { duration: 170; easing.type: Easing.OutCubic } }

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            ctx.strokeStyle = tokens.inkAlpha(combo.enabled ? 0.55 : 0.24)
            ctx.lineWidth = Math.max(1, host.sp(1.4))
            ctx.lineCap = "round"
            ctx.lineJoin = "round"
            ctx.beginPath()
            ctx.moveTo(host.sp(1), host.sp(1))
            ctx.lineTo(width / 2, height - host.sp(1))
            ctx.lineTo(width - host.sp(1), host.sp(1))
            ctx.stroke()
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onRotationChanged: requestPaint()
    }
    background: Rectangle {
        radius: host.sp(tokens.radiusFrame)
        color: tokens.inputPaper
        border.color: combo.activeFocus ? tokens.orange : tokens.inkAlpha(0.2)
        border.width: 1
    }
    delegate: ItemDelegate {
        id: comboDelegate
        width: combo.popup.width - host.sp(8)
        height: host.sp(38)
        leftPadding: host.sp(12)
        rightPadding: host.sp(12)
        text: modelData
        highlighted: combo.highlightedIndex === index
        font.family: tokens.sansFont
        font.pixelSize: host.sp(13)
        contentItem: Text {
            text: comboDelegate.text
            color: comboDelegate.highlighted ? "#FFFFFF" : tokens.ink
            font: comboDelegate.font
            elide: Text.ElideRight
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: host.sp(tokens.radiusFrame)
            color: comboDelegate.highlighted ? tokens.ink : "transparent"
            Behavior on color { ColorAnimation { duration: tokens.fastMotion } }
        }
    }
    popup: Popup {
        y: combo.height + host.sp(6)
        width: combo.width
        implicitHeight: Math.min(contentItem.implicitHeight + host.sp(8), host.sp(260))
        padding: host.sp(4)
        transformOrigin: Item.Top
        opacity: 0
        scale: 0.985
        enter: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 150; easing.type: Easing.OutCubic }
                NumberAnimation { property: "scale"; from: 0.985; to: 1; duration: 180; easing.type: Easing.OutCubic }
                NumberAnimation { property: "y"; from: combo.height; to: combo.height + host.sp(6); duration: 180; easing.type: Easing.OutCubic }
            }
        }
        exit: Transition {
            ParallelAnimation {
                NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 110; easing.type: Easing.InCubic }
                NumberAnimation { property: "scale"; from: 1; to: 0.985; duration: 110; easing.type: Easing.InCubic }
            }
        }
        contentItem: ListView {
            clip: true
            implicitHeight: contentHeight
            model: combo.popup.visible ? combo.delegateModel : null
            currentIndex: combo.highlightedIndex
            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
                contentItem: Rectangle {
                    implicitWidth: host.sp(4)
                    radius: host.sp(2)
                    color: tokens.inkAlpha(0.18)
                }
                background: Rectangle { color: "transparent" }
            }
        }
        background: Rectangle {
            radius: host.sp(tokens.radiusFrame)
            color: tokens.paperLight
            border.color: tokens.inkAlpha(0.24)
            border.width: 1
        }
    }
}
