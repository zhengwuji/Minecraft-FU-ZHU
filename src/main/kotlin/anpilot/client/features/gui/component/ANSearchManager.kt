package anpilot.client.features.gui.component

import anpilot.client.api.gui.ANGuiRenderContext
import java.awt.Color

object ANSearchManager {
    var query: String = ""
        private set
    var isFocused: Boolean = false

    fun clear() {
        query = ""
        isFocused = false
    }

    fun onCharTyped(codePoint: Char) {
        if (!isFocused) return
        if (codePoint >= ' ' && codePoint != '\u007F') {
            query += codePoint
        }
    }

    fun onKeyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Ctrl + F shortcut toggles search focus
        val isCtrlDown = (modifiers and 2) != 0 || (modifiers and 1) != 0
        if (isCtrlDown && keyCode == 70 /* F */) {
            isFocused = !isFocused
            return true
        }

        if (!isFocused) return false

        when (keyCode) {
            259 -> { // Backspace
                if (query.isNotEmpty()) {
                    query = query.substring(0, query.length - 1)
                }
                return true
            }
            256, 257 -> { // Escape or Enter
                isFocused = false
                return true
            }
        }
        return false
    }

    fun matches(moduleName: String, description: String = ""): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val nameLower = moduleName.lowercase()
        val descLower = description.lowercase()

        if (nameLower.contains(q) || descLower.contains(q)) return true

        // Match Pinyin initials
        val pinyinInitials = getPinyinInitials(moduleName)
        if (pinyinInitials.contains(q)) return true

        return false
    }

    private fun getPinyinInitials(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val pinyin = getFirstPinyinChar(ch)
            if (pinyin != null) {
                sb.append(pinyin)
            } else {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun getFirstPinyinChar(ch: Char): Char? {
        return when (ch) {
            '自' -> 'z'
            '动' -> 'd'
            '水' -> 's'
            '晶' -> 'j'
            '挖' -> 'w'
            '掘' -> 'j'
            '重' -> 'c'
            '生' -> 's'
            '锚' -> 'm'
            '炸' -> 'z'
            '床' -> 'c'
            '预' -> 'y'
            '判' -> 'p'
            '图' -> 't'
            '腾' -> 't'
            '倒' -> 'd'
            '计' -> 'j'
            '数' -> 's'
            '击' -> 'j'
            '退' -> 't'
            '经' -> 'j'
            '验' -> 'y'
            '身' -> 's'
            '体' -> 't'
            '脚' -> 'j'
            '部' -> 'b'
            '困' -> 'k'
            '人' -> 'r'
            '反' -> 'f'
            '界' -> 'j'
            '面' -> 'm'
            '移' -> 'y'
            '速' -> 's'
            '度' -> 'd'
            '弹' -> 't'
            '射' -> 's'
            '航' -> 'h'
            '空' -> 'k'
            '放' -> 'f'
            '置' -> 'z'
            '避' -> 'b'
            '让' -> 'r'
            '跟' -> 'g'
            '随' -> 's'
            '死' -> 's'
            '亡' -> 'w'
            '幽' -> 'y'
            '灵' -> 'l'
            '进' -> 'j'
            '食' -> 's'
            '装' -> 'z'
            '备' -> 'b'
            '拆' -> 'c'
            '除' -> 'c'
            '快' -> 'k'
            '捷' -> 'j'
            '栏' -> 'l'
            '补' -> 'b'
            '充' -> 'c'
            '搭' -> 'd'
            '建' -> 'j'
            '包' -> 'b'
            '裹' -> 'g'
            '工' -> 'g'
            '具' -> 'j'
            '附' -> 'f'
            '魔' -> 'm'
            '透' -> 't'
            '视' -> 's'
            '实' -> 's'
            '体' -> 't'
            '方' -> 'f'
            '块' -> 'k'
            '容' -> 'r'
            '器' -> 'q'
            '名' -> 'm'
            '称' -> 'c'
            '标' -> 'b'
            '签' -> 'q'
            '登' -> 'd'
            '出' -> 'c'
            '勾' -> 'g'
            '勒' -> 'l'
            '云' -> 'y'
            '雾' -> 'w'
            '天' -> 't'
            '气' -> 'q'
            '模' -> 'm'
            '型' -> 'x'
            '夜' -> 'y'
            '扫' -> 's'
            '描' -> 'm'
            '扫' -> 's'
            '地' -> 'd'
            '农' -> 'n'
            '场' -> 'c'
            '钓' -> 'd'
            '鱼' -> 'y'
            '消' -> 'x'
            '息' -> 'x'
            '建' -> 'j'
            '造' -> 'z'
            else -> null
        }
    }

    fun renderSearchBar(context: ANGuiRenderContext, x: Float, y: Float, width: Float, height: Float) {
        val bgColor = if (isFocused) Color(0xF51E293B.toInt(), true) else Color(0xD90F172A.toInt(), true)
        val borderColor = if (isFocused) Color(0xFF60A5FA.toInt(), true) else Color(0x6664748B.toInt(), true)
        val textColor = if (query.isEmpty() && !isFocused) 0x8894A3B8.toInt() else 0xFFFFFFFF.toInt()

        context.borderedRoundedRect(x, y, width, height, height / 2f, 1f, bgColor, borderColor)

        val displayText = if (query.isEmpty() && !isFocused) "🔍 按 Ctrl+F 或点击搜索功能模块..." else if (isFocused) "$query|" else query
        val textY = y + (height - context.textHeight()) / 2f + 1f
        context.text(displayText, x + 12f, textY, textColor)
    }

    fun handleClick(mouseX: Double, mouseY: Double, x: Float, y: Float, width: Float, height: Float): Boolean {
        val inside = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
        isFocused = inside
        return inside
    }
}
