package jvmdiag

/** 超过 maxChars 就截断并附明确标记,让模型知道信息不全。 */
fun truncate(text: String, maxChars: Int, keep: Keep): String {
    if (text.length <= maxChars) return text
    val dropped = text.length - maxChars
    return when (keep) {
        Keep.HEAD -> text.take(maxChars) + "\n[... 已截断,丢弃末尾 $dropped 字符 ...]"
        Keep.TAIL -> "[... 已截断,丢弃开头 $dropped 字符 ...]\n" + text.takeLast(maxChars)
    }
}
